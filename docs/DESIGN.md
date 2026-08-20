# Butler: Design

Butler is a single-binary daemon that sits on a server, watches for events, and runs declarative
pipelines in response.

The motivating case is rolling deployments. A CI system drops a new artifact into a directory on
the server; Butler notices the new version, stages it, repoints a symlink, restarts the service,
confirms the process is live and serving the version it should be, and reports the outcome. The
same machinery should handle a newly published Docker image, and more generally anything that
needs doing on a server in response to an event, a schedule or a hook. Nothing in the core model
is deployment-specific.

Everything is driven by a YAML config given to the daemon at startup. **The central design
problem is the altitude of that config.** Hardcode bash into it and it becomes a worse shell
script; restrict it to pre-canned deployment steps and it is useless for the next thing that
comes up. Butler aims between the two, and treats extensibility to new triggers and steps as the
primary architectural constraint (§7).

This document is authoritative for the model. Everything described here is implemented except
where §11 says it is deferred. Where the code and this document disagree, one of them is a bug.

---

## 1. Design principles

These are the tie-breakers. When a decision is close, these decide it.

1. **The config is data, not a program.** Conditions and interpolation exist so pipelines can
   react to facts. They are deliberately not powerful enough to express control flow that
   belongs in a script. If a pipeline needs loops and functions, it needs a `shell.run` step.
2. **Two altitudes, always.** Every capability area offers a high-level step that does the
   right thing (`fs.symlink` swaps atomically and remembers the old target) *and* an escape
   hatch (`shell.run`). Users start high and drop down only where needed.
3. **The step vocabulary is open, the core is closed.** Adding `docker.compose` must not
   require touching the runtime. The runtime knows about triggers, steps, results and
   conditions; it knows nothing about files, systemd or Docker.
4. **Restart is not an event, and neither is a fresh install.** A daemon that redeploys
   everything because it was restarted is worse than useless; one that does it because its state
   directory is empty is a trap laid for the day you rebuild the host. Butler decides what to do
   by observing the host, not by consulting its own memory (§6).
5. **Failures are loud and reversible.** Every run has a terminal status, an audit record and a
   notification path. Deployment steps are built so that undoing them is a one-liner.
6. **Config errors are caught before the daemon runs.** `butler validate` must catch typos,
   unknown keys and bad references with file/line/column, not at 3am on the fifth step.
7. **Every step can say what it would do without doing it.** `--dry-run` is not a nice-to-have
   bolted on later; `describe()` is a required method on the step SPI, so a step that cannot
   explain itself cannot be written (§5.5).
8. **Prefer the boring option.** Where two designs both work, take the one with less machinery.
   Several things a tool like this could do - automatic rollback, live config reload, secret
   redaction - are deliberately absent because the simple version is sufficient today (§11).

---

## 2. Core model

Five concepts. Everything else is built from them.

| Concept     | What it is                                                                       |
|-------------|----------------------------------------------------------------------------------|
| **Trigger** | A long-lived watcher that emits **Events**. Owns one virtual thread.             |
| **Event**   | An immutable bag of facts plus a **dedupe key**.                                 |
| **Job**     | A named binding of triggers to a sequence of steps, plus hooks and policy.       |
| **Step**    | One unit of work. Produces a **StepResult**. Identified by `uses:`.              |
| **Run**     | One execution of a job for one event. Has an id, a context, and an audit record. |

### 2.1 Run lifecycle

```
Trigger fires
   │
   ├─ Dedupe        last processed key for this job == event key?  → drop
   ├─ Gate          concurrency group busy? → skip / queue / cancel-previous
   ├─ Context       vars + trigger facts + persisted state + env + secrets
   ├─ discover[]    observe the host; overlay results onto state.*  (§6.2)
   ├─ when?         judged against observed reality → false: SKIPPED (state still written)
   │
   ├─ steps[]       sequential; each: when? → execute → retry? → register result
   │                   any failure (without continue_on_error) → abort remaining
   │
   ├─ on_failure[]  runs only if the run FAILED. Failures here are logged, not fatal.
   ├─ on_success[]  runs only if the run SUCCEEDED
   ├─ always[]      runs on any terminal status except CANCELLED
   │
   ├─ persist       write declared state keys           (SUCCESS only)
   ├─ record        discovered state + run record + dedupe key   (any status but CANCELLED)
   └─ notify        job-level notify policy fires
```

A run is `SUCCESS`, `FAILED`, `SKIPPED` (gated, or job-level `when:` false), or `CANCELLED`.
Three rules make that unambiguous:

- A step failing with `continue_on_error: true` records `failed` in its own result but **does not
  fail the run** - `on_success:` and `persist:` still happen. That is the whole point of the
  flag, and why `steps.x.failed` is exposed to conditions.
- Exceeding the job-level `timeout:` cancels the in-flight step and ends the run **`FAILED`**, so
  `on_failure:` gets to clean up. A timeout is a failure, not a cancellation.
- **`CANCELLED`** is only for a run displaced by `cancel_previous` or cut short by shutdown. It
  does **not** run `on_failure:`, because nothing was wrong - the work was withdrawn. A run
  withdrawn before it started does not observe either: `discover:` executes steps on the host, up
  to and including `shell.run`, and there is nobody left to tell what they found.

`always:` runs for every terminal status except `CANCELLED`.

### 2.2 Context namespaces

Everything a condition or `${}` can see:

| Namespace        | Contents                                                                                                                                      |
|------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `vars.*`         | global `vars:` merged with job `vars:`, then any `set` steps                                                                                  |
| `trigger.*`      | facts from the event, including regex named capture groups                                                                                    |
| `steps.<name>.*` | results of steps that declared `register:`                                                                                                    |
| `state.*`        | values persisted by previous successful runs, overlaid with anything the job's `discover:` block observed on this run (§6.2)                  |
| `env.*`          | process environment                                                                                                                           |
| `secret.*`       | resolved secrets, from env or secrets files. Not redacted in v1 (§11)                                                                         |
| `run.*`          | `id`, `job`, `trigger`, `started_at`, `dry_run`; and in hooks and `notify:`, also `status`, `duration`, `duration_ms`, `failed_step`, `error` |
| `butler.*`       | `version`, `host`                                                                                                                             |

Later namespaces never shadow earlier ones; the names are distinct on purpose.

`run.duration` is elapsed time written for a person to read (`20m 47s`), rounded to whole seconds,
so it is a string rather than a duration. `run.duration_ms` is the exact figure as a number, and is
what a condition compares: `run.duration_ms > 300000`.

---

## 3. Configuration DSL

### 3.1 Shape

Step type in `uses:`, parameters as sibling keys. The following keys are **reserved** on every
step and may never be used as a parameter name:

`name`, `uses`, `when`, `register`, `timeout`, `retry`, `continue_on_error`, `env`,
`working_dir`, `run_as`, and `extract` (valid only inside a `discover:` block, §6.2).

Step types are namespaced (`fs.symlink`, not `symlink`), which keeps the parameter space clean
and makes the registry self-documenting. `butler steps` prints every registered type and its
schema.

**YAML anchors and aliases are refused**, merge keys included. The parser reports an alias as a
scalar holding the anchor's *name*, so `copy: *base` would silently bind the string "base";
resolving aliases instead means replaying events, which costs every later diagnostic its true
line and column. Neither is acceptable, so the loader reports the alias and points at `vars:`.
Job templates (§11) are the intended answer to repetition.

### 3.2 Canonical example

The motivating use case, end to end.

```yaml
version: 1

settings:
  state_dir: /var/lib/butler
  log_format: json           # json | text
  max_concurrent_runs: 4
  poll_interval: 5s          # default for polling triggers
  shutdown_grace: 2m         # how long a drain lets in-flight runs finish
  run_retention: { count: 200, age: 30d }
  plugins_dir: /var/lib/butler/plugins

secrets:
  from_env: true             # ${secret.FOO} resolves from $FOO
  file: /etc/butler/secrets.yaml

vars:
  releases_root: /srv/apps

notifiers:
  ops:
    uses: notify.slack
    webhook: ${secret.SLACK_WEBHOOK}
    channel: "#deploys"

jobs:

  api:
    description: Rolling deploy of the API on new artifact

    on:
      - uses: file.appeared
        dir: /srv/artifacts/api
        match: 'api-(?<version>\d+\.\d+\.\d+)\.jar'
        settle: 10s                  # size+mtime stable this long before firing
        order_by: semver(version)    # fire only for the greatest version seen
        on_startup: latest           # latest | none | all

    concurrency:
      group: api
      mode: queue                    # queue | skip | cancel_previous
      queue_newest_only: true

    timeout: 10m

    discover:
      - name: Ask the running service what it is
        uses: http.request
        url: http://localhost:8080/health
        timeout: 3s
        extract:
          deployed_version: json.version

      - name: Fall back to the symlink if the service is down
        uses: fs.readlink
        path: ${vars.releases_root}/api/current
        when: not exists(state.deployed_version)
        extract:
          deployed_version: basename(value)

    when: semver(trigger.version) > semver(default(state.deployed_version, "0.0.0"))

    steps:
      - name: Stage the release
        uses: fs.copy
        from: ${trigger.path}
        to: ${vars.releases_root}/api/releases/${trigger.version}/api.jar
        mode: "0640"
        mkdirs: true

      - name: Point current at the new release
        uses: fs.symlink
        link: ${vars.releases_root}/api/current
        target: ${vars.releases_root}/api/releases/${trigger.version}
        atomic: true
        register: symlink            # exposes steps.symlink.previous_target

      - name: Restart the service
        uses: systemd.restart
        unit: api.service
        wait_active: 30s

      - name: Wait for health
        uses: http.wait
        url: http://localhost:8080/health
        until: status == 200 and json.version == ${trigger.version}
        timeout: 90s
        interval: 2s
        register: health

      - name: Prune old releases
        uses: fs.prune
        dir: ${vars.releases_root}/api/releases
        keep: 5
        continue_on_error: true

    on_failure:
      - name: Roll back symlink
        uses: fs.symlink
        link: ${vars.releases_root}/api/current
        target: ${steps.symlink.previous_target}
        atomic: true
        when: exists(steps.symlink.previous_target)

      - uses: systemd.restart
        unit: api.service

    persist:
      deployed_version: ${trigger.version}
      current_release: ${vars.releases_root}/api/releases/${trigger.version}

    notify:
      to: ops
      on: [ success, failure ]
      success: ":rocket: api ${trigger.version} deployed in ${run.duration}"
      failure: ":fire: api ${trigger.version} FAILED at step ${run.failed_step}"
```

Note what is *not* in there: no bash, no version comparison logic, no retry loops, no "is it up
yet" polling script. That is the altitude the DSL is aiming at. And the escape hatch is always
one key away:

```yaml
      - name: Anything not covered
        uses: shell.run
        shell: /bin/bash
        working_dir: ${vars.releases_root}/api/current
        timeout: 2m
        script: |
          set -euo pipefail
          ./bin/migrate --to ${trigger.version}
        register: migrate            # .stdout .stderr .exit_code
```

### 3.3 Job keys

The complete job schema, for reference:

| Key                                    |          | Meaning                                                           |
|----------------------------------------|----------|-------------------------------------------------------------------|
| `on`                                   | required | list of triggers                                                  |
| `steps`                                | required | the pipeline                                                      |
| `description`                          |          | free text, used in logs and `butler check`                        |
| `vars`                                 |          | job-local vars, merged over global `vars:`                        |
| `env`                                  |          | environment applied to every process-backed step in the job       |
| `discover`                             |          | observation steps that populate `state.*` (§6.2)                  |
| `when`                                 |          | run only if true, evaluated after `discover`                      |
| `concurrency`                          |          | `group`, `mode`, `queue_newest_only` (§5.4)                       |
| `timeout`                              |          | whole-run limit; exceeding it fails the run                       |
| `on_failure` / `on_success` / `always` |          | lifecycle hooks (§2.1)                                            |
| `persist`                              |          | state keys written after a successful run                         |
| `notify`                               |          | `to`, `on: [success, failure]`, and per-outcome message templates |

Everything except `on` and `steps` is optional, and a job with only those two is valid - that
is the floor the DSL should stay usable at.

### 3.4 Reserved step keys

```yaml
- name: Human label for logs and notifications
  uses: http.request
  when: <condition>                  # skip this step if false
  register: probe                    # expose result as steps.probe.*
  timeout: 30s
  retry:
    attempts: 3
    delay: 5s
    backoff: exponential             # fixed | exponential
    on: failure                      # failure | timeout | always
  continue_on_error: false
  env: { TOKEN: "${secret.API_TOKEN}" }   # quoted: a bare } would close the flow mapping
  working_dir: /srv/apps/api/current
  run_as: appuser
```

### 3.5 StepResult

Every step produces the same result shape, which is what `register:` exposes:

| Field                           | Notes                                              |
|---------------------------------|----------------------------------------------------|
| `status`                        | `ok` / `failed` / `skipped`                        |
| `ok`, `failed`, `skipped`       | booleans, for readable conditions                  |
| `duration`                      |                                                    |
| `attempts`                      | how many tries it took                             |
| `stdout`, `stderr`, `exit_code` | process-backed steps only                          |
| *(step-specific)*               | e.g. `previous_target`, `status`, `json`, `sha256` |

A result may additionally carry **`vars`**: values that land in the `vars.*` namespace rather than
under the step's own name. That is how `control.set` reaches `vars.*` without the runtime knowing
which step type did it, and it is why `simulate()` (§7.1) hands back a whole result.

Where a step's own output shares a name with a common field, as `http.request`'s `status` does,
the step's wins. `ok`, `failed` and `skipped` still say how the step itself went.

### 3.6 One config, several files

`--config` may be repeated. Each file is a whole document; they are read in order and merged
before validation, so cross-references resolve however the files are split. `jobs:`, `notifiers:`
and `vars:` accumulate and a name may be defined only once, while `settings:` and `secrets:`
configure the daemon and so belong to a single file.

Later files add rather than override: overriding would need a precedence order in the reader's
head, and the point is one job to a file, not environment layering. There is still one config,
one state directory and one run history; only the diagnostics know how many files there were.

---

## 4. Expression language

A small hand-written grammar. No dependency, no surprises. About 1000 lines across lexer,
parser, evaluator, templates and the function set - more than the back-of-envelope estimate that
justified writing it, but still a closed, fully-tested surface with no config-as-code escape
hatch, which was the actual point.

```
expr    := or
or      := and ( 'or' and )*
and     := not ( 'and' not )*
not     := 'not' not | cmp
cmp     := term ( ('=='|'!='|'<'|'<='|'>'|'>='|'matches'|'contains') term )?
term    := literal | call | path | '(' expr ')'
call    := IDENT '(' [ expr ( ',' expr )* ] ')'
path    := IDENT ( '.' IDENT | '[' INT ']' )*
literal := STRING | NUMBER | DURATION | 'true' | 'false' | 'null'
```

`DURATION` is `\d+(ms|s|m|h|d)` - `10s`, `5m`, `2h`. The same literal is what `timeout:`,
`settle:`, `interval:`, `delay:` and `wait_active:` take throughout the config, parsed by one
shared converter so there is exactly one duration syntax to learn.

Built-in functions, v1: `semver(s)`, `exists(path)`, `default(a, b)`, `len(x)`, `int(x)`,
`lower(s)`, `upper(s)`, `trim(s)`, `basename(p)`, `dirname(p)`, `match(s, re[, group])`,
`file_exists(p)`, `now()`.

**A double-quoted string takes escapes; a single-quoted one is raw**, exactly as in YAML. That is
what makes a regex readable: `match(stdout, 'v?(\d+\.\d+\.\d+)', 1)` says what it means, where the
escaped form doubles every backslash for no gain. A literal single quote therefore needs the
double-quoted form.

`semver()` returns a comparable value, so `semver(a) > semver(b)` does the obviously right
thing and string comparison never silently ranks `1.10.0` below `1.9.0`.

**Two evaluation contexts:**

- **Condition context** (`when:`, `until:`, `assert`) takes a bare expression. `${x}` is
  accepted as a synonym for `x` because it reads better inline; it is stripped at parse time.
- **String context** (every other value) is a literal with `${expr}` holes. Holes evaluate and
  stringify. `$${` escapes a literal `${`.

Which of a step's own parameters are conditions is the step's own business, declared by
`StepType.conditions()` (§7.1) - `until` for `http.wait`, `that` for `control.assert`. A trigger
says the same thing the same way through `TriggerType.conditions()`: `order_by` for
`file.appeared`, which ranks by whatever its own regex captured and so is judged for syntax alone. The runtime
hands those through unrendered, because interpolating `json.version == ${trigger.version}` first
would leave the unparseable text `json.version == 1.2.4`. It is also the one source of truth for
the question: `butler validate` asks the registry rather than keeping a list of its own.

Some steps inject **locals** into a condition's scope, declared by `StepType.locals()` (§7.1).
`http.wait`'s `until:` sees `status`, `headers`, `body` and `json` for the probe in flight. The same
mechanism carries `extract:` (§6.2), whose expressions evaluate against the step's own result
fields - `json.version` for `http.request`, `value` for `fs.readlink`. This is the only scoping
special case, and it is per step rather than global: `message: ${json.version}` on `control.log` is
a validation error, because nothing puts a `json` there.

`extract:` is a reserved step key valid **only inside a `discover:` block**, where it maps state
key to an expression over the step's result. Elsewhere `register:` already covers the need, and
allowing both would be two ways to do one thing.

Unknown paths evaluate to `null` rather than throwing, but referencing an unknown *namespace*
is a validation error. That catches `${triger.version}` at load time while still allowing
`default(state.deployed_version, "0.0.0")` on a first run.

**Null is not silently ordered.** `==` and `!=` accept null on either side, and `matches` and
`contains` treat it as no-match, because an absent path is an ordinary state of affairs. Ordering
(`<`, `>`, `<=`, `>=`) against null is an error instead, since there is no defensible answer and
guessing one would let a first-run deploy decision turn on a value nobody supplied. Use
`default()` or `exists()` to say what should happen when the value is missing.

---

## 5. Execution model

### 5.1 Threading

One virtual thread per trigger watcher, one per run. Sleeps are plain `Thread.sleep` on virtual
threads; there is no scheduled executor anywhere in the codebase.

```java
Thread.ofVirtual().name("trigger-" + id).start(() -> {
    while (running) {
        poll();
        Thread.sleep(interval);      // parks the carrier-free vthread
    }
});
```

`Runtime.availableProcessors()` never enters into it. Concurrency is bounded by
`settings.max_concurrent_runs` via a semaphore, and per-job by the concurrency group, both of
which are policy rather than a thread-pool artefact. There is no executor service: threads are
created per watcher and per run, which is the thing virtual threads make reasonable.

**Timeouts use the same mechanism.** A step with a timeout runs on its own virtual thread and is
interrupted if it overstays, so the limit is enforced by the runtime rather than by each step
individually. The job-level `timeout:` is not a second racing mechanism: it is a deadline that
caps what each step is given, so a job with five minutes left never hands a step ten. A
`discover:` step is a step for this purpose (§6.2), or a probe with no timeout of its own could
hold a run open forever. Interruption
is cooperative, so a step that blocks on nothing outlives its timeout; everything Butler ships
blocks on a process, a sleep or a socket, and a plugin that does not is reported and abandoned. An
abandoned step goes on holding its view of the `Context` while the run that moved on writes to it,
so `vars`, `steps` and `state` are synchronized maps: not to make anything it does meaningful, but
so a read from the thread nobody is waiting for cannot catch a map mid-resize.

A step that turns its interrupt into a result of its own keeps it. That is how a killed process
still reports what it printed: the runtime supplies the "timed out after 30s" message and the step
supplies the tail, which is the part worth reading.

### 5.2 Language features

- **Virtual threads**, so watchers and runs are written in blocking style (`poll(); sleep();`)
  rather than as a callback-driven scheduled executor. The simpler code is the point.
- **Records** for the config AST and every step param type. Jackson binds YAML straight into
  them.
- **Sealed interface + exhaustive switch** for the expression AST, where the node types are a
  genuinely closed set, so adding one makes the compiler point at every site to update. Not used
  for steps or triggers, which are deliberately open.
- **Text blocks** for shell scripts in tests and multi-line `describe()` output.

No `ScopedValue` (there is no ambient context - `RunContext` is an explicit parameter on every
SPI method) and no `StructuredTaskScope` (nothing in v1 is parallel, and it is still preview).
The build targets 25 because it is current, but nothing here needs above 21.

### 5.3 Process execution

`shell.run`, `systemd.*` and friends all funnel through one `ProcessRunner`:

- `ProcessBuilder` with an explicit argv (no shell) unless the step is `shell.run`
- stdout/stderr drained on separate virtual threads into bounded ring buffers, so a chatty
  process cannot exhaust memory and a full pipe cannot deadlock the child. The wait for the last
  of that output is bounded too: a pipe closes when everything holding it is gone, which is not
  the same moment the process exits if it left a service running in the background, and a step
  must not hang for as long as that service does
- `Process.waitFor(timeout, unit)` for the step timeout - a blocking call on a virtual thread,
  no `CompletableFuture` composition needed. On timeout, `destroy()`, then `destroyForcibly()`
  after a grace period, walking `toHandle().descendants()` so the whole tree goes. An interrupt
  kills the tree the same way and comes back as a timed-out result rather than an exception, so
  cancelling a step does not throw away the output it was cancelled over (§5.1)
- exit code, captured output and duration land in the StepResult

**`ProcessRunner` is an interface on the SPI**, implemented by `runtime/ForkingProcessRunner`. A
step may not depend on the runtime and a test must not fork, so a step asks
`RunContext.processes()` for a runner and `RunContext.command()` for its own process settings -
`working_dir:`, its `env:` merged over the job's, `run_as:` and the timeout it is allowed - already
filled in. That is also why `run_as:` is applied by the implementation rather than by each step:
replacing `sudo -u` later touches one class (§10.2).

### 5.4 Concurrency policy

Default `mode: queue` with `queue_newest_only: true`. If 1.2.3 and 1.2.4 land two seconds
apart, the 1.2.3 run completes, the queued 1.2.3-follow-up is replaced by 1.2.4, and the host
converges on the newest version. `skip` and `cancel_previous` are available for jobs where that
is wrong.

`ConcurrencyGate` serialises a group and is entered **before** the global
`max_concurrent_runs` permit, not after: an event waiting its turn within its own group has no
business occupying a slot another job could use. What the gate turns away is not a run - nothing is
discovered, no hook fires and nothing is written, exactly as for an event dropped by dedupe (§6.4) -
so it is a log line rather than a record. `CANCELLED` is for a run that had actually started: the
one `cancel_previous` displaces, and the one shutdown cuts short (§10.3).

Waiting for a turn is the longest thing between an event arriving and its run starting, so it is
where a shutdown has to be able to reach. A queued event carries the same `Cancellation` its run
would, and cancelling one wakes it where it is parked rather than leaving it to find out when its
turn finally comes.

### 5.5 Dry run

`--dry-run` produces a complete, fully resolved account of what a run would do, and changes
nothing. It is the review and testing mechanism for pipelines, so it is a first-class execution
mode rather than a flag each step interprets for itself.

**The SPI makes it unavoidable.** `describe()` is a required method (§7.1), so a step that
cannot explain its effect cannot be written. The runtime, not the step, decides whether to call
`execute()` or `describe()`, so a step author cannot accidentally opt out or leak a side effect
into dry-run.

Rules:

|                    | Under `--dry-run`                                                                                                                                                                     |
|--------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `discover:` steps  | **executed for real** - otherwise the decision reported is wrong (§6.2 rule 5)                                                                                                        |
| `when:` conditions | evaluated for real, against discovered state                                                                                                                                          |
| every other step   | `execute()` never called; `describe()` printed; `preflight()` warnings collected                                                                                                      |
| step results       | `simulate()` supplies the result later steps read. Often genuinely real: `fs.symlink` reads the current link target without writing, so the `on_failure` rollback describes correctly |
| `persist:`         | computed and shown, not written                                                                                                                                                       |
| notifications      | rendered and shown, not sent                                                                                                                                                          |
| dedupe key         | not recorded - a dry run leaves no trace and can be run against a live daemon's config                                                                                                |

Every value in the output is resolved: no `${}` survives, because an unexpanded variable is
precisely the kind of mistake a dry run exists to catch. The report is plain ASCII, because a plan
is read through a pipe, a redirect or a CI log as often as on a terminal.

```
$ butler trigger api --dry-run

DRY RUN  job=api  trigger=file.appeared  path=/srv/artifacts/api/api-1.2.4.jar  name=api-1.2.4.jar  dir=/srv/artifacts/api  size=48210311  modified=2026-08-09T02:58:11Z  version=1.2.4

  discover  (executed for real)
    1  Ask the running service what it is  http.request
         state.deployed_version = "1.2.3"
    -  Fall back to the symlink            fs.readlink
         skipped: when=false

  when   semver("1.2.4") > semver("1.2.3")   ->   true

  steps
    1  Stage the release         fs.copy
         would copy   /srv/artifacts/api/api-1.2.4.jar
               to     /srv/apps/api/releases/1.2.4/api.jar
               mode 0640, creating 1 parent directory
    2  Point current at release  fs.symlink
         would repoint (atomic) /srv/apps/api/current
               from   /srv/apps/api/releases/1.2.3
               to     /srv/apps/api/releases/1.2.4
    3  Restart the service       systemd.restart
         would run    sudo systemctl restart api.service
               then wait up to 30s for the unit to become active
    4  Wait for health           http.wait
         would poll   GET http://localhost:8080/health every 2s, up to 90s
               until  status == 200 and json.version == "1.2.4"
    5  Prune old releases        fs.prune
         would delete /srv/apps/api/releases/1.2.0
                      /srv/apps/api/releases/1.2.1
               keeping the newest 5 of 7

  on_failure                                          not shown: reached only on failure
  persist   (not written)   deployed_version = "1.2.4"
                            current_release  = "/srv/apps/api/releases/1.2.4"
  notify    (not sent)      ops <- ":rocket: api 1.2.4 deployed in <duration>"

  1 warning
    step 3      no NOPASSWD sudoers rule matches `systemctl restart api.service`
```

A step is numbered when it would run, `-` when its `when:` is false, and `!` when it could not be
resolved at all.

`preflight()` is what makes this more than an echo of the config. It performs the read-only
checks a step can do cheaply - source file exists and is readable, parent directory is
writable, unit is known to systemd, URL parses, sudoers rule exists - and reports them as
warnings without mutating anything. That warning above is a real deployment failure caught
before it happens.

**Honest limitation, stated up front:** a dry run cannot predict effects that depend on the
mutations it skipped. Step 4 above will genuinely poll for 1.2.4 during a real run, but in a
dry run the restart never happened, so its `until:` is described rather than evaluated. Dry run
answers "what would this do", not "would this succeed".

---

## 6. State, discovery and idempotency

Restart safety is the difference between a useful daemon and a hazard.

### 6.1 Persisted state is a cache, not the truth

A daemon that has never run before has no memory, and "no memory" is indistinguishable from
"nothing has ever been deployed" unless Butler can go and look. The two cases below are
identical in Butler's state directory and opposite in what they require:

|                              | Host reality                                 | Correct action |
|------------------------------|----------------------------------------------|----------------|
| Fresh install on a live host | `api` 1.2.3 running, `api-1.2.3.jar` present | do nothing     |
| First ever deploy            | nothing running, `api-1.2.3.jar` present     | deploy 1.2.3   |

Dedupe keys cannot separate them, because the difference is not in the event stream. So state
is treated as a cache of host reality, and jobs are given a way to populate it from the host.

### 6.2 Discovery

A job may declare a `discover:` block: observation-only steps that run **before `when:` is
evaluated, on every event**, and whose extracted values populate `state.*`.

Resolution rules:

1. `state.*` is persisted state **overlaid with** discovered values. A job never has to care
   which one it got.
2. Discovery runs before the job-level `when:`, so `when:` decides against observed reality
   rather than against memory.
3. **Discovery failure is not run failure.** A discovery step that errors or times out
   contributes nothing and the persisted value stands. If the health endpoint is briefly down,
   Butler falls back to what it last recorded rather than concluding the app is gone. **An
   `extract:` that produces no value is the same case**: the step answered, but not with this, so
   the persisted value stands rather than being overwritten with null. A health endpoint that
   changed shape must not read as "nothing is deployed".
4. **Any step may be used for discovery, including `shell.run`.** Plenty of apps are not HTTP
   services, and the only way to learn their version is to read a file, list a release directory
   or run a binary with `--version`. Restricting discovery to a read-only step whitelist would
   push exactly those cases out to a workaround. Keeping the host read-only during discovery is
   therefore the config author's responsibility, stated plainly in the docs rather than enforced
   by the registry.
5. **Discovery runs for real under `--dry-run`.** A dry run that skipped discovery would not
   know the current version and would report the wrong decision, which defeats the point. This
   is the one place a dry run executes user-supplied commands, and it is called out in the dry
   run output (§5.5) so nobody is surprised by it.
6. A run that ends `SKIPPED` still records the dedupe key and still writes discovered state.
   Otherwise every poll would rediscover and re-skip forever.

Running discovery on *every* event rather than only when state is missing costs one HTTP call
per new artifact and buys considerably more than first-run correctness:

| Situation                                                        | Discovered                        | `when:` | Outcome                               |
|------------------------------------------------------------------|-----------------------------------|---------|---------------------------------------|
| Fresh install, host on 1.2.3, artifact 1.2.3                     | 1.2.3                             | false   | skipped, state seeded, **no restart** |
| Never deployed, artifact 1.2.3                                   | null                              | true    | deploys                               |
| Steady state, artifact 1.2.4 arrives                             | 1.2.3                             | true    | deploys                               |
| State directory wiped or host rebuilt                            | 1.2.3                             | false   | skipped; state loss is harmless       |
| Butler crashed mid-run: symlink swapped, service never restarted | 1.2.3 (old version still serving) | true    | redeploys, converges                  |
| Someone rolled back by hand out of band                          | 1.2.2                             | true    | drift corrected                       |

The last three rows are the real payoff. Discovery is not a first-run special case bolted on;
it is what makes the daemon self-healing after a crash, a rebuild, or a human.

### 6.3 When the host cannot be asked

Most apps are not HTTP services with a version endpoint. In rough order of preference:

- **`fs.readlink` on the current release symlink.** Works for the whole symlink-swap deploy
  pattern with zero cooperation from the deployed app. This should be the documented default
  for anything that does not expose a version endpoint.
- **`fs.read` on a version file**, or **`fs.list`** over a releases directory ordered by semver
  to find the newest present.
- **`shell.run`**, for the cases nothing else reaches - `myapp --version`, a `dpkg -s` query, a
  value buried in a properties file:

  ```yaml
  discover:
    - uses: shell.run
      script: /opt/myapp/bin/myapp --version
      timeout: 5s
      extract:
        deployed_version: match(stdout, 'v?(\d+\.\d+\.\d+)', 1)
  ```
- **`butler adopt <job>`** records current reality as state without executing any steps, *and*
  records the dedupe key of whatever is already present, so an artifact sitting in the watch
  directory does not fire the moment the daemon starts. This is the explicit onboarding path for
  an existing host: run it once at install time and the first real event behaves correctly.
- **No `discover:` block at all** falls back to the original state-only behaviour. That is
  correct for jobs where re-running is harmless (cache warms, notifications, idempotent syncs)
  and wrong for anything that restarts a service. `butler validate` **warns** when a job's
  `when:` references `state.*` but the job declares no `discover:` block, because that
  combination is almost always the bug described in §6.1.

### 6.4 On-disk layout

`state_dir` layout:

```
/var/lib/butler/
  jobs/api.json          { dedupe_key, last_run, state: { deployed_version, current_release } }
  runs/2026-08-09/<run-id>.json
  runs/index.jsonl       append-only, one line per run, for fast history queries
```

The persisted values sit under their own `state` key rather than beside the bookkeeping, so a job
may `persist:` a value called `dedupe_key` without overwriting anything. Still greppable, which was
the point of JSON files.

Plain JSON, written temp-then-`ATOMIC_MOVE`. No SQLite, no embedded DB; the write volume is a
handful of records per day and the operator benefit of greppable state files is real.

**Dedupe.** Each event carries a key (`file.appeared`: absolute path + size + mtime). A run is
only started if the key differs from the job's last processed key, and the key is recorded for
skipped runs as well as successful ones. A dropped event is **not a run**: nothing is discovered,
no hooks fire and nothing is written, because the state on disk is already the state the run would
write. It is reported as `SKIPPED` so the caller has something to print. On a *restart* this is what makes `on_startup: latest`
cheap: the newest artifact's key is already recorded and no run begins. On a *first* boot there
is no key to match, so the event does fire and correctness falls to discovery (§6.2) - which is
the division of labour: dedupe suppresses repeated work, discovery decides whether work is
needed at all.

**Persistence** is declarative via the job's `persist:` block, evaluated after a successful run.
There is no `state.put` step, because state mutation scattered through a pipeline is how you get
half-written state on a mid-run failure.

**Run history.** `RunRecorder` writes one document per run holding the whole of it - what discovery
observed, the decision with both sides shown, every step with its status, duration and attempts,
what was persisted and what was notified - so "what happened at 3am" is answerable from the state
directory with `jq` and no logs. The append-only `runs/index.jsonl` carries the same summary fields
as the head of each record, so the two cannot describe a run differently. Retention is by count and
age together (`settings.run_retention`), enforced after each write on the run's own thread: it is a
directory listing and a few deletions, and handing it to a thread nobody waits for only bought that
back at the cost of never running at all under `butler trigger`, where the process exits first.
Failing to write a record never fails the run that produced it: the work was done, and losing the
note about it is worth a log line and no more.

---

## 7. Extensibility

### 7.1 The SPI

Two interfaces, both discovered by `ServiceLoader`. This is the seam that keeps the runtime
ignorant of Docker, systemd and everything that comes later.

```java
public interface StepType<C> {
    String name();                                   // "fs.symlink"
    Class<C> configType();                           // a record; Jackson binds it

    StepResult execute(C config, RunContext ctx) throws Exception;

    /** Fully resolved account of the effect, for --dry-run. Required, deliberately. */
    String describe(C config, RunContext ctx);

    /** Read-only checks: does the source exist, is the target writable, is the unit known. */
    default List<String> preflight(C config, RunContext ctx) { return List.of(); }

    /** The result later steps should read when execute() was skipped. Often computable for real. */
    default StepResult simulate(C config, RunContext ctx) { return StepResult.ok(); }

    /** Parameters read as a bare condition rather than a template, e.g. until:, that: (§4). */
    default List<String> conditions() { return List.of(); }

    /** Names this step injects into its own expressions: status/json for a probe, value for a
        symlink read. `butler validate` judges each expression against what can reach it. */
    default List<String> locals() { return List.of(); }

    default String summary() { return ""; }          // one-liner for `butler steps`
}

public interface TriggerType<C> {
    String name();                                   // "file.appeared"
    Class<C> configType();
    Watcher start(C config, EventSink sink, TriggerContext ctx);

    /** Parameters read as a bare expression rather than a template, e.g. order_by: (§4). */
    default List<String> conditions() { return List.of(); }

    /** What this trigger can see right now, oldest first, without starting a watcher. */
    default List<Event> current(C config, TriggerContext ctx) { return List.of(); }
}
```

**A trigger's parameters are not templated**, because a watcher is started before any event
exists and there is no run to resolve `${}` against. They bind through the same path a step's do,
and anything with a syntax of its own is typed as that syntax: `match:` is a `Pattern`,
`expression:` a `Cron`, `timezone:` a `ZoneId`. Binding is therefore where a malformed one is
caught, with a file, line and column, rather than at startup. Whatever a trigger still parses for
itself belongs in `start()`, on the caller's thread: thrown from the watch thread it would leave
the daemon reporting that it watches a job that is dead.

`current()` is what makes `butler trigger` a rehearsal against the real event rather than a
synthetic one, and what gives `butler adopt` the dedupe key of whatever is already present (§10.1).
A trigger with nothing to observe - `manual`, a schedule - has no candidates.

A step is a record plus a class. That is the whole cost of extending Butler:

```java
public record SymlinkConfig(Path link, Path target, boolean atomic) {}

public final class SymlinkStep implements StepType<SymlinkConfig> {
    public String name() { return "fs.symlink"; }
    public Class<SymlinkConfig> configType() { return SymlinkConfig.class; }

    public StepResult execute(SymlinkConfig c, RunContext ctx) throws IOException {
        Path previous = current(c.link());
        // temp symlink + ATOMIC_MOVE so readers never observe a missing link
        ...
        return StepResult.ok().output("previous_target", previous);
    }

    public String describe(SymlinkConfig c, RunContext ctx) {
        return """
               would repoint%s %s
                     from   %s
                     to     %s""".formatted(c.atomic() ? " (atomic)" : "",
                                            c.link(), current(c.link()), c.target());
    }

    public List<String> preflight(SymlinkConfig c, RunContext ctx) {
        return Files.exists(c.target()) ? List.of()
             : List.of("target does not exist: " + c.target());
    }

    // read-only, so the dry run reports the true previous target and on_failure describes right
    public StepResult simulate(SymlinkConfig c, RunContext ctx) {
        return StepResult.ok().output("previous_target", current(c.link()));
    }

    private static Path current(Path link) throws IOException {
        return Files.isSymbolicLink(link) ? Files.readSymbolicLink(link) : null;
    }
}
```

Schemas are derived from the record's components, so validation, `butler steps` output and the
docs all come from one source and cannot drift.

Third-party plugins drop jars into `settings.plugins_dir`, loaded into one child classloader before
the registries are built - so a config naming a third-party step is validated against a vocabulary
that has it. One loader for all of them rather than one each: they are a single vocabulary, and
isolating them from each other would buy nothing.

### 7.2 Planned step vocabulary

Bold entries are v1.

| Namespace | Steps                                                                                                                                   |
|-----------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `fs`      | **copy**, **move**, **symlink**, **readlink**, **read**, **list**, **mkdir**, **prune**, **template**, **exists**, chmod, chown, unpack |
| `systemd` | **restart**, **start**, **stop**, **reload**, **wait_active**, **status**, enable                                                       |
| `shell`   | **run** (via shell), **exec** (argv, no shell)                                                                                          |
| `http`    | **request**, **wait**                                                                                                                   |
| `notify`  | **send** (slack, discord, generic webhook, ntfy; email later)                                                                           |
| `control` | **set**, **assert**, **sleep**, **log**, **fail**, parallel, group                                                                      |
| `docker`  | pull, compose, run, prune                                                                                                               |
| `git`     | clone, pull, checkout                                                                                                                   |
| `pkg`     | apt, snap                                                                                                                               |

Two conventions this vocabulary settled on:

- **`order_by:` is an expression where it ranks facts and a name where it ranks files.**
  `file.appeared` takes `semver(version)`, because it ranks by whatever its regex captured and no
  fixed name could reach that. `fs.list` and `fs.prune` take `name`, `semver` or `modified`,
  because those three are the whole of what ranking a directory means and a config that needs more
  has `shell.run`.
- **The `systemd` verbs that mutate a unit put `sudo` in front by default**, matching the sudoers
  allowlist of §10.2. That is separate from `run_as:`, which says which user to become rather than
  that root is required; `sudo: false` turns it off for a user unit.

### 7.3 Planned trigger vocabulary

| Trigger              | Notes                                                                                                                           |
|----------------------|---------------------------------------------------------------------------------------------------------------------------------|
| **`file.appeared`**  | new file, or with `kind: dir` a new directory, matching a regex; named groups become facts                                      |
| **`file.changed`**   | content hash change on a specific path                                                                                          |
| **`schedule.every`** | fixed interval                                                                                                                  |
| **`schedule.cron`**  | 5-field cron; small hand-written parser in `util/Cron`, so a bad expression binds as a diagnostic rather than killing a watcher |
| **`manual`**         | fired by `butler trigger <job>`; the testing workhorse                                                                          |
| `http.webhook`       | needs the admin server                                                                                                          |
| `docker.image`       | registry digest poll                                                                                                            |
| `command.output`     | run a command on an interval, fire when stdout changes                                                                          |
| `systemd.state`      | unit entered/left a state                                                                                                       |

**`file.appeared` deserves detail**, because it is the trigger the main use case rests on and
the one with the most ways to go wrong:

- **Polling is the primary mechanism**, `WatchService` an optional accelerator. `WatchService`
  misses files written before startup, behaves inconsistently on network and overlay
  filesystems, and coalesces events under load. A 5s poll of one directory costs nothing.
- **Settle detection is mandatory.** A file is a candidate only once its size and mtime have
  been unchanged for `settle:`. Deploying a half-uploaded jar is the single most likely
  first-week failure and the DSL should make avoiding it the default, not an option.
- **`order_by:` means only the greatest candidate fires**, so dropping an old artifact into the
  directory does not trigger a downgrade.
- **`kind: dir` watches for directories**, for a release that arrives unpacked rather than as one
  file. A directory cannot be judged settled the way a file is: its own size is a constant and its
  own mtime moves only when an entry is added or removed directly in it, so neither notices a large
  file three levels down still being written. A directory candidate is snapshotted as an aggregate
  over its tree - total bytes, newest mtime anywhere, entry count - which is also what `size:` and
  `modified:` report as facts, and an empty one is never a candidate. The cost is a walk per poll
  instead of a `stat`.

---

## 8. Package layout

```
net.ryanh.butler
  Main                      picocli bootstrap; main(String[] args)
  cli/                      ButlerCommand (daemon, the default) + validate | check | trigger
                            | adopt | steps | generate-completion subcommands, ConfigMixin,
                            Logging (text for a human, JSON for the daemon)

  config/
    model/                  records: ButlerConfig, JobDef, StepDef, TriggerDef, ...
    ConfigLoader            YAML -> generic tree -> model, collecting diagnostics
    Cursor                  typed reader over one mapping; records what it was asked for,
                            which is what makes unknown-key detection and suggestions free
    ConfigValidator         expressions, references, uniqueness
    Vocabulary              what the validator needs to know about a step or trigger type, keyed
                            by uses:, so config can judge expressions without depending on a
                            registry
    Diagnostic/Diagnostics  every problem at once, sorted by position
    SourceMap               path -> line:col, from a streaming pass over the same file
    Secrets                 resolution from env / secrets file

  expr/
    Lexer, Token            
    Node                    sealed AST + Op
    Parser                  recursive descent
    Evaluator               exhaustive switch, no default branch
    Expressions             the facade: condition() and template()
    Template                literal text + ${expr} holes, compiled once
    Scope                   root lookup for namespaces and step-injected locals
    Functions               the built-in function set

  util/
    Durations               the one duration syntax, shared by config and the expr lexer,
                            plus human() for elapsed time a person reads rather than reparses
    Semver                  the one version order, shared by the expr function and fs.list/prune
    Cron                    the one cron syntax, shared by schedule.cron and its parameter binding
    Literals                a resolved value rendered the way it would be written
    Suggestions             did-you-mean hints, on an edit distance scaled to the word

  runtime/
    Butler                  owns lifecycle: start watchers, drain events, shut down
    Context                 the RunContext implementation: namespaces + registered results
    Triggering              the TriggerContext implementation, including order_by ranking
    RunEnvironment          what a run needs from outside itself: config, registries, state,
                            processes, secrets. Both the plan and the run take one
    StepRegistry            ServiceLoader discovery keyed by name(); TriggerRegistry and
                            NotifierRegistry likewise
    Params                  binds a step's raw parameter map to its own config record
    RegistryValidator       every uses: is registered, with parameters that step knows
    Notifiers               resolves a channel by name and hands it a rendered message
    StepResolver            uses: -> type, when?, interpolate, bind. Shared by plan and run
    StepExecution           one execute() call with its timeout enforced around it
    Discovery               the discover: phase, shared by plan and run (§6.2)
    Plan, PlanBuilder       a job and an event resolved into an ordered account of the run
    PlanRenderer            the dry-run report of §5.5
    JobRunner               step loop, when/retry/timeout, hooks, persist, adopt
    Run, RunRenderer        what a run did, and the report of it
    ConcurrencyGate         groups, queue/skip/cancel semantics
    Cancellation            the one switch that produces CANCELLED, held by the gate and shutdown
    StateStore              per-job JSON read/write
    Atomically              the one temp-then-move replace everything under state_dir uses
    RunRecorder             audit records + retention
    Plugins                 the child classloader settings.plugins_dir is loaded into
    ForkingProcessRunner    the ProcessRunner implementation: fork, drain, timeout, kill tree

  spi/                      StepType, TriggerType, Notifier, StepResult, RunContext,
                            ProcessRunner, Notifications, Event, EventSink, Watcher,
                            TriggerContext: the public surface

  step/fs/  step/systemd/  step/shell/  step/http/  step/notify/  step/control/
  trigger/file/  trigger/schedule/  trigger/manual/
  notify/                   slack, webhook, discord and ntfy, plus the one way to POST one.
                            Flat rather than a package per channel: each is a record and a
                            send() over a shared helper, and four of those is not four packages

  admin/                    (deferred) com.sun.net.httpserver: /healthz, /runs, /trigger
```

`spi` depends on nothing. `step.*`, `trigger.*` and `notify.*` depend on `spi`, plus `util` for the
one duration syntax and the one version order - forbidding that would mean every step that formats
a timeout or ranks a release inventing its own.
`runtime` depends on `spi` and `config` but never on a concrete step. `util` and `expr` depend on
nothing internal beyond `util`. That dependency direction is the whole extensibility argument, and
`ArchitectureTest` keeps it honest.

**Config loading does not use databind.** The document is bound to a generic tree and walked with
`Cursor`, because databind throws on the first mismatch and the entire point is reporting every
problem at once. Databind is still what binds step parameters to a step's own config record,
where failing fast on one step is correct. `Cursor` remembers every key it was asked for, so
leftover keys are by definition unknown and the asked-for set is exactly the suggestion
candidates - unknown-key detection falls out of the reader rather than needing a schema listing.

---

## 9. Dependencies

Deliberately small. A daemon that runs on a VPS should be a jar you can reason about.

| Dependency                                                                                       | Why                                                                                                                                                                                                                                |
|--------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `info.picocli:picocli` 4.7.7                                                                     | subcommands, option parsing, generated help, `--version`, shell completion                                                                                                                                                         |
| `tools.jackson.core:jackson-databind` + `tools.jackson.dataformat:jackson-dataformat-yaml` 3.2.1 | YAML binding straight into records, plus a streaming pass for `path → line:col` since databind does not retain source locations. Note Jackson **3**: `tools.jackson.*` groupId and packages, immutable builder-constructed mappers |
| `org.slf4j:slf4j-api` 2.0.18 + `ch.qos.logback:logback-classic` 1.6.1                            | logging; Logback's built-in `JsonEncoder` covers structured output with no extra dependency                                                                                                                                        |
| JUnit 6 (present)                                                                                | tests                                                                                                                                                                                                                              |
| `com.tngtech.archunit:archunit` 1.5.0                                                            | test scope only; pins the package layout above, which is otherwise a convention nobody notices breaking                                                                                                                            |

Not used, on purpose: any DI framework, any HTTP server framework (`java.net.http` and
`com.sun.net.httpserver` are in the JDK), any expression library, any embedded database.

---

## 10. The CLI, and running in production

### 10.1 CLI

Butler is a daemon that happens to be a good command-line tool. The bare invocation starts the
daemon; the subcommands are how you author, test and inspect a config, and they matter as much
as the daemon does - a config you cannot rehearse is a config you find out about in production.

Built with **picocli**: one annotated class per command, a shared mixin for `--config` and
`--dry-run`, generated help and `--version`, and a generated completion script for free.

```
butler [--config /etc/butler/butler.yaml]... [--dry-run]  # no subcommand: run as daemon
butler validate                     # exit 1 listing every error, for CI
butler check                        # validate, then print the resolved effective config
butler trigger <job> [--set version=1.2.3]
butler adopt   [<job>]              # discovery only: record state, execute nothing
butler steps   [<name>]             # registered steps and their schemas
butler generate-completion          # picocli's own, generated from the real command tree
```

Default-to-daemon means the systemd unit is just `ExecStart=/usr/bin/butler --config …`, while
everything a human does interactively is a subcommand. In picocli that is a top-level
`@Command` that is itself `Runnable`, with the rest registered as `subcommands`.

`--config` defaults to `/etc/butler/butler.yaml` and is on the shared mixin, so every command
reads the same config the daemon will. Repeating it reads several files as one config (§3.6).

**`--dry-run` works on every command that would otherwise change something**, including the
daemon: `butler --dry-run` starts all the watchers and reports what each firing would do,
indefinitely, touching nothing. That is the safest way to introduce Butler to a server already
running things - leave it in dry run for a day and read the log.

- `butler trigger <job>` **evaluates the job's own configured triggers once** through
  `TriggerType.current()` (§7.1) and runs it against the event they would produce - the newest
  candidate artifact, say - rather than inventing a synthetic event. That is what makes it a
  genuine rehearsal: `${trigger.path}` and `${trigger.version}` hold what they would hold in
  production. `--set k=v` overrides individual facts, or supplies them all for a job whose trigger
  has no current candidate. A run asked for by hand carries no dedupe key, so it is never
  suppressed as already done. Combined with `--dry-run` this is the authoring loop.
- `butler adopt` is the install-time step on an existing host, seeding state from reality so the
  first real event is judged correctly (§6.3).
- `butler steps` is generated from the registry, so a newly added step is documented the moment
  it is registered.

Exit codes matter, because half of these run in CI: `0` success, `1` failure or validation
errors, `2` bad usage (picocli's default).

### 10.2 Privileges

Butler runs as an unprivileged `butler` user under systemd. Steps that need root go through a
narrow sudoers allowlist:

```
butler ALL=(root) NOPASSWD: /usr/bin/systemctl restart api.service, \
                            /usr/bin/systemctl reload  api.service
```

Only the verbs that mutate a unit need a grant. `is-active` and `show`, which `systemd.wait_active`,
`systemd.status` and every preflight check use, are read-only and run as the daemon's own user.

`run_as:` is implemented as `sudo -u <user>` wrapping the command. A privilege-dropping
`ProcessBuilder` would be cleaner but is real work for a feature that is rarely needed; `sudo -u`
is behind the SPI, so replacing it later touches one class.

This is stated plainly in the docs: **`shell.run` executes with the daemon's privileges**, and
so does anything a `discover:` block runs. A config file is as trusted as the daemon, so it
lives at `0640 root:butler`, and secrets come from env or separate secrets files rather than
inline. `secrets: file:` takes a list, merged the same way `--config` is (§3.6).

### 10.3 Observability

- JSON logs with `run_id`, `job`, `step` on every line, from Logback's own `JsonEncoder`, which is
  why structured logging costs no dependency. `settings.log_format` chooses; the daemon honours it
  and an interactive command is always text, because `butler validate` in a terminal should be
  readable and the daemon under systemd should be greppable
- run records on disk, queryable with `jq` and later over the admin API
- SIGTERM drains: stop watchers, let in-flight runs finish within `settings.shutdown_grace`, then
  cancel what outlasts it, which ends `CANCELLED`. The grace period is one deadline for the whole
  drain rather than per run, and generous by default (2m) because a deploy killed halfway is the
  worst outcome available and a job's own `timeout:` already bounds it. A JVM shutdown hook is the
  whole of the signal design: it is what both the `TERM` systemd sends and the `INT` a terminal
  sends reach, so there is one path rather than two. A systemd unit's `TimeoutStopSec` has to exceed
  the grace period, or it `SIGKILL`s the drain it asked for
- **no secret redaction in v1.** Scanning captured stdout for known secret values is cheap to
  add later; for now the documented guidance is not to echo secrets, and logs inherit the state
  directory's permissions. Recorded in §11 so it is a decision rather than an oversight

Config changes require a restart. There is no SIGHUP reload, because Butler already recovers its
bearings from the host on startup (§6.2) - a restart is a few seconds of not watching, and the
first event after it is judged against observed reality, not against whatever the old config
believed. Reload would buy very little and would mean reasoning about jobs whose definition
changed underneath them.

### 10.4 Testing strategy

- Steps are pure-ish units against `@TempDir`; `ProcessRunner` is an interface with a fake
- The expression evaluator gets a table-driven suite; it is the component where a subtle bug is
  most likely and least visible
- `file.appeared` gets tests for the nasty cases specifically: partial writes, settle timing,
  a lower version appearing later, restart-with-existing-artifact
- **Every row of the §6.2 table is a test.** Fresh install on a live host, first-ever deploy,
  wiped state directory, crash mid-run, out-of-band rollback, and discovery timing out. These
  are the scenarios that are tedious to reproduce by hand and expensive to get wrong in
  production, which is exactly the profile of something that should be pinned by tests early
- One end-to-end test drives a real config against a temp directory and a stub HTTP server
- **Dry-run output is snapshot-tested.** Because it is fully resolved and deterministic, the
  rendered plan for a config is a golden file. It pins interpolation, condition evaluation,
  step ordering and every `describe()` in one cheap assertion per pipeline, and a diff in that
  file is the clearest possible review artefact for a config change

---

## 11. Deliberately deferred

Designed for, not built yet. Recorded here so v1 does not accidentally foreclose them. The first
three are in the order they are worth doing; the rest are decisions not to build something.

- **Admin HTTP server** and `http.webhook` triggers. The one module unlocks manual triggering,
  run history and CI push-notification at once, which is why it is first in line after v1.
- **Docker / compose** steps and registry polling.
- **Job templates** (`extends:` + `with:`). Once there are more than two apps this becomes the
  main DRY lever. It is a pure config-expansion pass before validation, so it can be added
  without touching the runtime.
- **Automatic rollback** by replaying completed steps in reverse. Attractive and genuinely hard
  to get right; explicit `on_failure:` covers the real cases with no hidden behaviour. Revisit
  only with evidence.
- **Secret redaction** in logs and captured process output. A scan of stdout against known
  secret values, plus a redacting log encoder. Straightforward whenever a secret actually ends
  up somewhere it shouldn't.
- **Config reload on SIGHUP.** Restart is cheap and safe for the reasons in §10.3.
- **Privilege-dropping `run_as`.** `sudo -u` is sufficient.
- **Multi-host orchestration.** Butler is a single-host agent. Coordinating hosts is a different
  product and should not leak into this model.

