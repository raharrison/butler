# Butler: Design

Butler is a single-binary daemon that sits on a server, watches for events, and runs declarative
pipelines in response. The motivating case is a rolling deployment: CI drops an artifact into a
directory, Butler notices the new version, stages it, repoints a symlink, restarts the service,
confirms it is serving the version it should be, and reports the outcome. Nothing in the core
model is deployment-specific.

Everything is driven by a YAML config given to the daemon at startup. **The central design
problem is the altitude of that config.** Hardcode bash into it and it becomes a worse shell
script; restrict it to pre-canned deployment steps and it is useless for the next thing that
comes up. Butler aims between the two, and treats extensibility to new triggers and steps as the
primary architectural constraint (§7).

This document is the rationale: why the product is shaped this way, and what it deliberately does
not do. It is authoritative for the model, and everything here is implemented except where §11
says otherwise. For what you can actually write, see the reference docs:

|                                      |                                                                           |
|--------------------------------------|---------------------------------------------------------------------------|
| [CONFIGURATION.md](CONFIGURATION.md) | Every config key, trigger, step, notifier and expression.                 |
| [OPERATING.md](OPERATING.md)         | Install, systemd, privileges, logs, the state directory, troubleshooting. |
| [README](../README.md)               | The tour, the CLI and worked recipes.                                     |

Where the code and this document disagree, one of them is a bug.

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
6. **Config errors are caught before the daemon runs.** `butler validate` catches typos,
   unknown keys and bad references with file, line and column, not at 3am on the fifth step.
7. **Every step can say what it would do without doing it.** `describe()` is a required method
   on the step SPI, so a step that cannot explain itself cannot be written (§5.4).
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

A run is `SUCCESS`, `FAILED`, `SKIPPED` or `CANCELLED`. Three rules make that unambiguous, and
each is a decision rather than an implementation detail:

- A step failing with `continue_on_error: true` records `failed` in its own result but **does not
  fail the run**, so `on_success:` and `persist:` still happen. That is the whole point of the
  flag, and why `steps.x.failed` is exposed to conditions.
- Exceeding the job-level `timeout:` ends the run **`FAILED`** rather than cancelling it, so
  `on_failure:` gets to clean up. A timeout is a failure.
- **`CANCELLED`** is only for a run displaced by `cancel_previous` or cut short by shutdown. It
  runs no hooks and writes nothing, because nothing was wrong: the work was withdrawn. A run
  withdrawn before it started does not observe either, since `discover:` executes real commands
  on the host and there is nobody left to tell what they found.

The author-facing description of the same sequence is
[CONFIGURATION.md](CONFIGURATION.md#run-lifecycle).

### 2.2 Context namespaces

Eight namespaces are visible to every condition and `${}` hole; the full contents of each are in
[CONFIGURATION.md](CONFIGURATION.md#the-expression-language). Three properties of the set are
design decisions:

- **Later namespaces never shadow earlier ones.** The names are distinct on purpose, so a
  reference means one thing wherever it appears.
- **Elapsed time is offered twice.** `run.duration` is written for a person to read (`20m 47s`),
  rounded to whole seconds, so it is a string. `run.duration_ms` is the exact figure as a number,
  and is what a condition compares: `run.duration_ms > 300000`. One value could not be both
  without a formatting function nobody would reach for in a message.
- **`run.previous_status` is how the last run that did work ended**, which is what makes a
  recovery expressible. A run skipped by `when:` leaves it alone: it did nothing, so it is not
  what the job last did.

---

## 3. Configuration DSL

### 3.1 Shape

Step type in `uses:`, parameters as sibling keys. A fixed set of keys is **reserved** on every
step and may never be a parameter name (§3.3), which is what lets the loader lift them out before
a step type sees the rest.

Step types are namespaced (`fs.symlink`, not `symlink`), which keeps the parameter space clean
and makes the registry self-documenting. `butler steps` prints every registered type and its
schema.

**YAML anchors and aliases are refused**, merge keys included. The parser reports an alias as a
scalar holding the anchor's *name*, so `copy: *base` would silently bind the string "base";
resolving aliases instead means replaying events, which costs every later diagnostic its true
line and column. Neither is acceptable, so the loader reports the alias and points at `vars:`.
Job templates (§11) are the intended answer to repetition.

### 3.2 Canonical example

The motivating use case, end to end. This is
`src/test/resources/configs/canonical.yaml` verbatim, and the build fails if the two drift.

```yaml
version: 1

settings:
  state_dir: /var/lib/butler
  log_format: json           # json | text
  max_concurrent_runs: 4
  poll_interval: 5s          # default for polling triggers
  shutdown_grace: 2m         # how long a drain lets in-flight runs finish
  default_job_timeout: 1h    # bound on a job that sets no timeout: of its own
  run_retention: { count: 200, age: 30d }
  plugins_dir: /var/lib/butler/plugins
  process_capture_bytes: 262144    # kept per stream (stdout/stderr) for a process-backed step

secrets:
  from_env: true             # ${secret.FOO} resolves from $FOO
  files: /etc/butler/secrets.yaml

vars:
  releases_root: /srv/apps

notifiers:
  ops:
    uses: notify.slack
    webhook: ${secret.SLACK_WEBHOOK}
    channel: "#deploys"
  oncall:
    uses: notify.webhook
    url: https://alerts.example.com/hooks/butler

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

    run_retention: { age: 90d }      # a quarter of deploys; count stays settings' 200

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
        register: rollback           # so notify: can report whether it took

      - uses: systemd.restart
        unit: api.service

    persist:
      deployed_version: ${trigger.version}
      current_release: ${vars.releases_root}/api/releases/${trigger.version}

    notify:
      to: [ ops, oncall ]           # one name or a list
      on: [ success, failure, recovered ]
      success: ":rocket: api ${trigger.version} deployed in ${run.duration}"
      failure: ":fire: api ${trigger.version} FAILED at ${run.failed_step}, rollback ${steps.rollback.status}"
      recovered: ":white_check_mark: api is back on ${trigger.version} after ${run.previous_status}"
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

### 3.3 Job and step keys

The complete schemas are in CONFIGURATION.md: [job keys](CONFIGURATION.md#jobs),
[reserved step keys](CONFIGURATION.md#steps) and the [result shape](CONFIGURATION.md#results)
every step produces. Four things about them are design decisions rather than reference material:

- **Everything except `on:` and `steps:` is optional.** A job with only those two is valid, and
  that is the floor the DSL should stay usable at.
- **A step's reserved keys are the runtime's, its parameters are its own.** The loader lifts the
  reserved set out before a step type sees the mapping, so a step can never redefine `retry:` and
  the runtime never has to know what `unit:` means. `StepRegistry` refuses a step whose parameter
  is named after a reserved key, because that parameter could never receive a value.
- **A result may carry `vars`**, which land in the `vars.*` namespace rather than under the
  step's own name. That is how `control.set` reaches `vars.*` without the runtime knowing which
  step type did it, and it is why `simulate()` (§7.1) hands back a whole result rather than a
  map of outputs.
- **A step's own output wins a name collision** with a common result field, as `http.request`'s
  `status` does. `ok`, `failed` and `skipped` still say how the step itself went, so the question
  "did this work" is always answerable the same way.

### 3.4 One config, several files

`--config` may be repeated. Each file is a whole document; they are read in order and merged
before validation, so cross-references resolve however the files are split. Collections
accumulate and policy belongs to a single file
([the split](CONFIGURATION.md#several-files)).

Later files add rather than override: overriding would need a precedence order in the reader's
head, and the point is one job to a file, not environment layering. There is still one config,
one state directory and one run history; only the diagnostics know how many files there were.

---

## 4. Expression language

A small hand-written grammar, written rather than pulled in so that the surface stays closed:
there is no dependency to track and no config-as-code escape hatch. The
[grammar, operators and functions](CONFIGURATION.md#the-expression-language) are the reference;
what follows is why the language stops where it does.

One duration syntax, `\d+(ms|s|m|h|d)`, is shared by the expression lexer and every config key
that takes one, through a single converter. A second syntax would be a second thing to learn for
no gain.

**There are two evaluation contexts** - a bare condition, and a string with `${expr}` holes -
because they cannot be collapsed. Interpolating a condition first would leave the unparseable text
`json.version == 1.2.4`, so a condition is parsed and never rendered.

Which of a step's own parameters are conditions is the step's own business, declared by
`StepType.conditions()` (§7.1) - `until` for `http.wait`, `that` for `control.assert`. A trigger
says the same thing through `TriggerType.conditions()`. That declaration is the single source of
truth for the question: `butler validate` asks the registry rather than keeping a list of its own
that could drift.

Some steps inject **locals** into a condition's scope, declared by `StepType.locals()`.
`http.wait`'s `until:` sees `status`, `headers`, `body` and `json` for the probe in flight. The
same mechanism carries `extract:` (§6.2), whose expressions evaluate against the step's own result
fields. This is the only scoping special case, and it is per step rather than global:
`message: ${json.version}` on `control.log` is a validation error, because nothing puts a `json`
there.

Three rules about absent values, each chosen so that a first run cannot turn on a value nobody
supplied:

- **An unknown path is `null`; an unknown namespace is a validation error.** That catches
  `${triger.version}` at load time while still allowing
  `default(state.deployed_version, "0.0.0")` on a first run.
- **`steps.<name>` is the exception** and is checked against the names the job actually
  registers, because it is the one path whose absence is always a mistake rather than a first
  run: nothing will ever put it there. A step may only name one that already ran. `persist:` and
  the `notify:` messages are rendered after the hooks (§2.1), so each is judged against the
  sections its own outcome runs - which is what lets a `failure:` message report whether the
  `on_failure:` rollback took while a `success:` message naming the same register is refused.
- **Null is not silently ordered.** Equality and matching accept it, because an absent path is an
  ordinary state of affairs. Ordering against it is an error, because there is no defensible
  answer and guessing one would let a first-run deploy decision turn on a value nobody supplied.

A notification is the one expression a dry run cannot rehearse: the failure template is first
rendered by a real failure, which is the worst moment to find out it says nothing.

---

## 5. Execution model

### 5.1 Threading and timeouts

One virtual thread per trigger watcher, one per run. Sleeps are plain `Thread.sleep` on virtual
threads; there is no scheduled executor and no thread pool anywhere in the codebase, which is
what lets a watcher be written in blocking style:

```java
Thread.ofVirtual().name("trigger-" + id).start(() -> {
    while (running) {
        poll();
        Thread.sleep(interval);      // parks the carrier-free vthread
    }
});
```

`Runtime.availableProcessors()` never enters into it. Concurrency is bounded by
`settings.max_concurrent_runs` via a semaphore and per-job by the concurrency group, both of
which are policy rather than a thread-pool artefact.

**Timeouts use the same mechanism.** A step with a timeout runs on its own virtual thread and is
interrupted if it overstays, so the limit is enforced by the runtime rather than by each step
individually. The job-level `timeout:` is not a second racing mechanism: it is a deadline that
caps what each step is given, so a job with five minutes left never hands a step ten. A
`discover:` step is a step for this purpose (§6.2), or a probe with no timeout of its own could
hold a run open forever.

**Every run is bounded.** A job that names no `timeout:` inherits `settings.default_job_timeout`,
`1h` unless the config says otherwise, so there is always a deadline. That is not politeness: a
run holds its concurrency group and one of the `max_concurrent_runs` permits for as long as it
lasts, so `max_concurrent_runs` hung jobs stop the daemon doing anything else. A job that
legitimately takes longer raises its own `timeout:`, which is the visible place to say so.

Interruption is cooperative, so a step that blocks on nothing outlives its timeout; everything
Butler ships blocks on a process, a sleep or a socket, and a plugin that does not is reported and
abandoned. An abandoned step goes on holding its view of the `Context` while the run that moved
on writes to it, so `vars`, `steps` and `state` are synchronized maps: not to make anything it
does meaningful, but so a read from the thread nobody is waiting for cannot catch a map
mid-resize.

A step that turns its interrupt into a result of its own keeps it. That is how a killed process
still reports what it printed: the runtime supplies the "timed out after 30s" message and the step
supplies the tail, which is the part worth reading.

### 5.2 Process execution

`shell.run`, `systemd.*` and friends all funnel through one `ProcessRunner`:

- `ProcessBuilder` with an explicit argv (no shell) unless the step is `shell.run`
- stdout/stderr drained on separate virtual threads into bounded ring buffers, so a chatty
  process cannot exhaust memory and a full pipe cannot deadlock the child. The wait for the last
  of that output is bounded too: a pipe closes when everything holding it is gone, which is not
  the same moment the process exits if it left a service running in the background, and a step
  must not hang for as long as that service does
- `Process.waitFor(timeout, unit)` for the step timeout - a blocking call on a virtual thread, no
  `CompletableFuture` composition needed. On timeout, `destroy()`, then `destroyForcibly()` after
  a grace period, walking `toHandle().descendants()` so the whole tree goes. An interrupt kills
  the tree the same way and comes back as a timed-out result rather than an exception, so
  cancelling a step does not throw away the output it was cancelled over (§5.1)
- exit code, captured output and duration land in the StepResult

**`ProcessRunner` is an interface on the SPI**, implemented by `runtime/ForkingProcessRunner`. A
step may not depend on the runtime and a test must not fork, so a step asks
`RunContext.processes()` for a runner and `RunContext.command()` for its own process settings -
`working_dir:`, its `env:` merged over the job's, `run_as:` and the timeout it is allowed - already
filled in. That is also why `run_as:` is applied by the implementation rather than by each step:
replacing `sudo -u` later touches one class (§10.2).

### 5.3 Concurrency policy

Default `mode: queue` with `queue_newest_only: true`. If 1.2.3 and 1.2.4 land two seconds apart,
the 1.2.3 run completes, the queued 1.2.3-follow-up is replaced by 1.2.4, and the host converges
on the newest version. `skip` and `cancel_previous` are available for jobs where that is wrong.

`ConcurrencyGate` serialises a group and is entered **before** the global `max_concurrent_runs`
permit, not after: an event waiting its turn within its own group has no business occupying a slot
another job could use. What the gate turns away is not a run - nothing is discovered, no hook fires
and nothing is written, exactly as for an event dropped by dedupe (§6.4) - so it is a log line
rather than a record. `CANCELLED` is for a run that had actually started: the one `cancel_previous`
displaces, and the one shutdown cuts short (§10.3).

Waiting for a turn is the longest thing between an event arriving and its run starting, so it is
where a shutdown has to be able to reach. A queued event carries the same `Cancellation` its run
would, and cancelling one wakes it where it is parked rather than leaving it to find out when its
turn finally comes.

### 5.4 Dry run

`--dry-run` produces a complete, fully resolved account of what a run would do, and changes
nothing. It is the review and testing mechanism for pipelines, so it is a first-class execution
mode rather than a flag each step interprets for itself.

**The SPI makes it unavoidable.** `describe()` is a required method (§7.1), so a step that cannot
explain its effect cannot be written. The runtime, not the step, decides whether to call
`execute()` or `describe()`, so a step author cannot accidentally opt out or leak a side effect
into a dry run.

|                    | Under `--dry-run`                                                                                                                                                                     |
|--------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `discover:` steps  | **executed for real** - otherwise the decision reported is wrong (§6.2)                                                                                                               |
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
  notify    (not sent)      ops, oncall <- ":rocket: api 1.2.4 deployed in <duration>"

  1 warning
    step 3      no NOPASSWD sudoers rule matches `systemctl restart api.service`
```

A step is numbered when it would run, `-` when its `when:` is false, and `!` when it could not be
resolved at all.

`preflight()` is what makes this more than an echo of the config. It performs the read-only checks
a step can do cheaply - source file exists and is readable, parent directory is writable, unit is
known to systemd, URL parses, sudoers rule exists - and reports them as warnings without mutating
anything. That warning above is a real deployment failure caught before it happens.

**Honest limitation, stated up front:** a dry run cannot predict effects that depend on the
mutations it skipped. Step 4 above will genuinely poll for 1.2.4 during a real run, but in a dry
run the restart never happened, so its `until:` is described rather than evaluated. Dry run answers
"what would this do", not "would this succeed".

---

## 6. State, discovery and idempotency

Restart safety is the difference between a useful daemon and a hazard.

### 6.1 Persisted state is a cache, not the truth

A daemon that has never run before has no memory, and "no memory" is indistinguishable from
"nothing has ever been deployed" unless Butler can go and look. The two cases below are identical
in Butler's state directory and opposite in what they require:

|                              | Host reality                                 | Correct action |
|------------------------------|----------------------------------------------|----------------|
| Fresh install on a live host | `api` 1.2.3 running, `api-1.2.3.jar` present | do nothing     |
| First ever deploy            | nothing running, `api-1.2.3.jar` present     | deploy 1.2.3   |

Dedupe keys cannot separate them, because the difference is not in the event stream. So state is
treated as a cache of host reality, and jobs are given a way to populate it from the host.

### 6.2 Discovery

A job may declare a `discover:` block: observation-only steps that run **before `when:` is
evaluated, on every event**, and whose extracted values populate `state.*`. The author-facing
rules are in [CONFIGURATION.md](CONFIGURATION.md#discover); three of them exist for reasons worth
recording.

**Discovery failure is not run failure**, and neither is an `extract:` that produces no value. If
the health endpoint is briefly down, or has changed shape, Butler falls back to what it last
recorded rather than concluding the app is gone. Overwriting a good value with null would turn a
monitoring blip into a redeploy.

**Any step may be used for discovery, including `shell.run`.** Plenty of apps are not HTTP
services, and the only way to learn their version is to read a file, list a release directory or
run a binary with `--version`. Restricting discovery to a read-only whitelist would push exactly
those cases out to a workaround. Keeping the host read-only during discovery is therefore the
config author's responsibility, stated plainly in the docs rather than enforced by the registry.

**Discovery runs for real under `--dry-run`.** A dry run that skipped it would not know the
current version and would report the wrong decision, which defeats the point. This is the one
place a dry run executes user-supplied commands, and it is labelled as such in the output (§5.4)
so nobody is surprised by it.

Running discovery on *every* event rather than only when state is missing costs one HTTP call per
new artifact and buys considerably more than first-run correctness:

| Situation                                                        | Discovered                        | `when:` | Outcome                               |
|------------------------------------------------------------------|-----------------------------------|---------|---------------------------------------|
| Fresh install, host on 1.2.3, artifact 1.2.3                     | 1.2.3                             | false   | skipped, state seeded, **no restart** |
| Never deployed, artifact 1.2.3                                   | null                              | true    | deploys                               |
| Steady state, artifact 1.2.4 arrives                             | 1.2.3                             | true    | deploys                               |
| State directory wiped or host rebuilt                            | 1.2.3                             | false   | skipped; state loss is harmless       |
| Butler crashed mid-run: symlink swapped, service never restarted | 1.2.3 (old version still serving) | true    | redeploys, converges                  |
| Someone rolled back by hand out of band                          | 1.2.2                             | true    | drift corrected                       |

The last three rows are the real payoff, and every row of this table is a test. Discovery is not a
first-run special case bolted on; it is what makes the daemon self-healing after a crash, a
rebuild, or a human.

### 6.3 When the host cannot be asked

Most apps are not HTTP services with a version endpoint. In rough order of preference:

- **`fs.readlink` on the current release symlink.** Works for the whole symlink-swap deploy
  pattern with zero cooperation from the deployed app. This is the documented default for anything
  that does not expose a version endpoint.
- **`fs.read` on a version file**, or **`fs.list`** over a releases directory ordered by semver to
  find the newest present.
- **`shell.run`**, for the cases nothing else reaches - `myapp --version`, a `dpkg -s` query, a
  value buried in a properties file. [Worked examples](../README.md#ask-a-host-what-it-is-running-without-an-http-endpoint).
- **`butler adopt`** records current reality as state without executing any steps, *and* records
  the dedupe key of whatever is already present, so an artifact sitting in the watch directory
  does not fire the moment the daemon starts. This is the explicit onboarding path for an existing
  host: run it once at install time and the first real event behaves correctly.
- **No `discover:` block at all** falls back to state-only behaviour. That is correct for jobs
  where re-running is harmless (cache warms, notifications, idempotent syncs) and wrong for
  anything that restarts a service. `butler validate` **warns** when a job's `when:` references
  `state.*` but the job declares no `discover:` block, because that combination is almost always
  the bug described in §6.1.

### 6.4 On-disk layout

The state directory holds one JSON file per job and one record per run; the formats are documented
in [OPERATING.md](OPERATING.md#the-state-directory). Plain JSON written temp-then-`ATOMIC_MOVE`,
no SQLite and no embedded DB: the write volume is a handful of records per day and the operator
benefit of greppable state files is real.

**Dedupe.** Each event carries a key (`file.appeared`: absolute path, size and mtime). A run only
starts if the key differs from the job's last processed key, and the key is recorded for skipped
runs as well as successful ones. A dropped event is **not a run**: nothing is discovered, no hooks
fire and nothing is written, because the state on disk is already the state the run would write.
It is reported as `SKIPPED` so the caller has something to print. On a *restart* this is what makes
`on_startup: latest` cheap: the newest artifact's key is already recorded and no run begins. On a
*first* boot there is no key to match, so the event does fire and correctness falls to discovery
(§6.2). That is the division of labour: dedupe suppresses repeated work, discovery decides whether
work is needed at all.

**Persistence** is declarative via the job's `persist:` block, evaluated after a successful run.
There is no `state.put` step, because state mutation scattered through a pipeline is how you get
half-written state on a mid-run failure.

**Run history.** One document per run holds the whole of it, so "what happened at 3am" is
answerable from the state directory with `jq` and no logs. The append-only `runs/index.jsonl`
carries the same summary fields as the head of each record, so the two cannot describe a run
differently, and is written one object per line so that a listing costs one file read whatever the
history holds. `butler show` renders a record through the same code the run printed with, so a
record and the report shown at the time are the same text rather than two accounts that can drift.

Retention is by count and age together, and **per job**: on a budget shared across jobs, a
heartbeat firing every ten seconds evicts the deploy history `runs/` exists for. The job is in the
file name, so "this job's records" is a directory listing. It runs after each write on the run's
own thread: a listing and a few deletions, and handing it to a thread nobody waits for only bought
that back at the cost of never running at all under `butler trigger`, where the process exits
first. Failing to write a record never fails the run that produced it: the work was done, and
losing the note about it is worth a log line and no more.

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

A step is a record plus a class, and that is the whole cost of extending Butler. Schemas are
derived from the record's components, so validation, `butler steps` output and the docs all come
from one source and cannot drift. `step/fs/SymlinkStep` is the worked example: it implements
`execute`, `describe`, `preflight` and a `simulate` that reads the current link target for real,
which is what lets a dry run describe the `on_failure:` rollback correctly.

**A trigger's parameters are not templated**, because a watcher is started before any event exists
and there is no run to resolve `${}` against. They bind through the same path a step's do, and
anything with a syntax of its own is typed as that syntax: `match:` is a `Pattern`, `expression:` a
`Cron`, `timezone:` a `ZoneId`. Binding is therefore where a malformed one is caught, with a file,
line and column, rather than at startup. Whatever a trigger still parses for itself belongs in
`start()`, on the caller's thread: thrown from the watch thread it would leave the daemon reporting
that it watches a job that is dead.

`current()` is what makes `butler trigger` a rehearsal against the real event rather than a
synthetic one, and what gives `butler adopt` the dedupe key of whatever is already present
(§10.1). A trigger with nothing to observe - `manual`, a schedule - has no candidates.

Third-party plugins drop jars into `settings.plugins_dir`, loaded into one child classloader before
the registries are built, so a config naming a third-party step is validated against a vocabulary
that has it. One loader for all of them rather than one each: they are a single vocabulary, and
isolating them from each other would buy nothing.

### 7.2 Vocabulary conventions

The shipped vocabulary is in [CONFIGURATION.md](CONFIGURATION.md#step-reference) and in
`butler steps`, which is generated from the registry. Four conventions it settled on, which the
next namespace added should follow:

- **`order_by:` is an expression where it ranks facts and a name where it ranks files.**
  `file.appeared` takes `semver(version)`, because it ranks by whatever its regex captured and no
  fixed name could reach that. `fs.list` and `fs.prune` take `name`, `semver` or `modified`,
  because those three are the whole of what ranking a directory means, and a config that needs
  more has `shell.run`.
- **A step that reads something into the run caps how much.** `fs.read` and both buffering HTTP
  steps take `max_bytes`, defaulting to 1 MiB, because a body reaches the run's memory, its
  expressions and its record.
- **`fs.unpack` is the one `fs.*` step that starts a process.** Every Linux host has a tar that
  detects the compression itself and refuses a member whose name would escape the destination, so
  a tar reader of our own would be a second implementation of it. It goes through
  `spi/ProcessRunner` like any other command, so a test asserts on the command it built rather
  than forking one.
- **The `systemd` verbs that mutate a unit put `sudo` in front by default**, matching the sudoers
  allowlist of §10.2. That is separate from `run_as:`, which says which user to become rather than
  that root is required; `sudo: false` turns it off for a user unit.

**`file.appeared` carries the main use case**, and is the one trigger whose behaviour is mostly
decisions rather than parameters ([the full reference](CONFIGURATION.md#fileappeared)). Two are
worth recording:

- **Polling is the primary mechanism**, `WatchService` an optional accelerator. `WatchService`
  misses files written before startup, behaves inconsistently on network and overlay filesystems,
  and coalesces events under load. A 5s poll of one directory costs nothing, and correctness
  beats latency for something that fires a deployment.
- **Settle detection is mandatory, not optional.** Deploying a half-uploaded jar is the single
  most likely first-week failure, so avoiding it is the default rather than a key the author has
  to know to reach for. `kind: dir` has to define settling differently - a directory's own size
  and mtime do not move while a file three levels down is still being written - so a directory
  candidate is snapshotted as an aggregate over its tree instead.

---

## 8. Package layout

```
net.ryanh.butler
  Main          picocli bootstrap
  cli/          the daemon command plus validate | check | trigger | adopt | runs | show
                | steps | generate-completion, a shared --config mixin, and log format selection
  config/       ConfigLoader (YAML -> generic tree -> model), Cursor, ConfigValidator,
                Vocabulary, Diagnostics, SourceMap, Secrets, model/
  expr/         Lexer, sealed Node AST, Parser, Evaluator, Template, Scope, Functions
  util/         Durations, Semver, Cron, Literals, Suggestions - one implementation each
  spi/          StepType, TriggerType, Notifier, StepResult, RunContext, ProcessRunner,
                Notifications, Event, EventSink, Watcher, TriggerContext: the public surface
  runtime/      Butler (the daemon loop), Context, registries, Params, StepResolver,
                StepExecution, Discovery, Plan/PlanBuilder/PlanRenderer, JobRunner/Run/RunRenderer,
                ConcurrencyGate, Cancellation, StateStore, RunRecorder, Atomically, Plugins,
                ForkingProcessRunner
  step/         one package per namespace: control, shell, fs, systemd, http, notify
  trigger/      one package per family: manual, file, schedule
  notify/       the channels and the one way to POST one
  admin/        (deferred) com.sun.net.httpserver: /healthz, /runs, /trigger
```

The layering is the whole extensibility argument, and `ArchitectureTest` keeps it honest:

- `util` depends on nothing; `expr` depends only on `util`; `config` depends on `expr` and `util`.
- `spi` depends on nothing at all. `step/`, `trigger/` and `notify/` depend on `spi` and `util` -
  forbidding `util` would mean every step that formats a timeout or ranks a release inventing its
  own.
- `runtime` depends on `spi`, `config` and `expr` but **never** imports a concrete step, trigger or
  notifier. They arrive by `ServiceLoader`.

**Config loading does not use databind.** The document is bound to a generic tree and walked with
`Cursor`, because databind throws on the first mismatch and the entire point is reporting every
problem at once. Databind is still what binds step parameters to a step's own config record, where
failing fast on one step is correct. `Cursor` remembers every key it was asked for, so leftover
keys are by definition unknown and the asked-for set is exactly the suggestion candidates:
unknown-key detection falls out of the reader rather than needing a schema listing.

---

## 9. Dependencies

Deliberately small. A daemon that runs on a VPS should be a jar you can reason about. The versions
live in `build.gradle.kts`; what matters is the shortness of the list.

| Dependency                                        | Why                                                                                                                                |
|---------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| picocli                                           | Subcommands, option parsing, generated help, `--version`, shell completion.                                                        |
| Jackson **3** (`tools.jackson.*`) databind + YAML | YAML binding straight into records, plus a streaming pass for `path -> line:col`, since databind does not retain source locations. |
| SLF4J + Logback                                   | Logging. Logback's built-in `JsonEncoder` covers structured output with no extra dependency.                                       |
| JUnit, ArchUnit                                   | Test scope. ArchUnit pins the layering of §8, which is otherwise a convention nobody notices breaking.                             |

Not used, on purpose: any DI framework, any HTTP server framework (`java.net.http` and
`com.sun.net.httpserver` are in the JDK), any expression library, any embedded database.

---

## 10. Production

### 10.1 CLI

Butler is a daemon that happens to be a good command-line tool. The bare invocation starts the
daemon; the subcommands are how you author, test and inspect a config, and they matter as much as
the daemon does - a config you cannot rehearse is a config you find out about in production. The
[README](../README.md#the-cli) is the reference for all of them.

Default-to-daemon means the systemd unit is just `ExecStart=/usr/bin/butler --config …`, while
everything a human does interactively is a subcommand. In picocli that is a top-level `@Command`
that is itself `Callable`, with the rest registered as `subcommands`.

Four decisions behind that surface:

- **`--config` is on a shared mixin**, so every command reads the same config the daemon will.
  Repeating it reads several files as one config (§3.4).
- **`--dry-run` works on every command that would otherwise change something**, including the
  daemon: `butler --dry-run` starts all the watchers and reports what each firing would do,
  indefinitely, touching nothing. That is the safest way to introduce Butler to a server already
  running things - leave it in dry run for a day and read the log.
- **`butler trigger <job>` evaluates the job's own configured triggers once** through
  `TriggerType.current()` (§7.1) and runs against the event they would produce, rather than
  inventing a synthetic one. That is what makes it a genuine rehearsal: `${trigger.path}` and
  `${trigger.version}` hold what they would hold in production. A run asked for by hand carries no
  dedupe key, so it is never suppressed as already done.
- **`butler runs` and `butler show` answer from the state directory alone**, with the daemon
  stopped, which is why the run history is files rather than a process to ask.

Exit codes matter, because half of these run in CI: `0` success, `1` failure or validation errors,
`2` bad usage (picocli's default).

### 10.2 Privileges

Butler runs as an unprivileged `butler` user under systemd. Steps that need root go through a
narrow sudoers allowlist; the copyable version is in
[OPERATING.md](OPERATING.md#privileges). Only the verbs that mutate a unit need a grant:
`is-active` and `show`, which `systemd.wait_active`, `systemd.status` and every preflight check
use, are read-only and run as the daemon's own user.

`run_as:` is implemented as `sudo -u <user>` wrapping the command. A privilege-dropping
`ProcessBuilder` would be cleaner but is real work for a feature that is rarely needed; `sudo -u`
is behind the SPI, so replacing it later touches one class.

**`shell.run` executes with the daemon's privileges**, and so does anything a `discover:` block
runs, including under `--dry-run`. A config file is therefore as trusted as the daemon, which is
why it lives at `0640 root:butler` and why secrets come from env or separate secrets files rather
than inline.

### 10.3 Observability

- **JSON logs** with `run_id`, `job` and `step` on every line, from Logback's own `JsonEncoder`,
  which is why structured logging costs no dependency. `settings.log_format` chooses; the daemon
  honours it and an interactive command is always text, because `butler validate` in a terminal
  should be readable and the daemon under systemd should be greppable.
- **Run records on disk**, queryable with `jq` and later over the admin API (§11).
- **SIGTERM drains**: stop watchers, let in-flight runs finish within `settings.shutdown_grace`,
  then cancel what outlasts it, which ends `CANCELLED`. The grace period is one deadline for the
  whole drain rather than per run, and generous by default (2m) because a deploy killed halfway is
  the worst outcome available and a job's own `timeout:` already bounds it. A JVM shutdown hook is
  the whole of the signal design: it is what both the `TERM` systemd sends and the `INT` a terminal
  sends reach, so there is one path rather than two. A unit's `TimeoutStopSec` has to exceed the
  grace period, or it `SIGKILL`s the drain it asked for.
- **No secret redaction in v1.** Scanning captured stdout for known secret values is cheap to add
  later; for now the documented guidance is not to echo secrets, and logs inherit the state
  directory's permissions. Recorded in §11 so it is a decision rather than an oversight.

Config changes require a restart. There is no SIGHUP reload, because Butler already recovers its
bearings from the host on startup (§6.2): a restart is a few seconds of not watching, and the first
event after it is judged against observed reality rather than against whatever the old config
believed. Reload would buy very little and would mean reasoning about jobs whose definition changed
underneath them.

---

## 11. Deliberately deferred

Designed for, not built. Recorded here so v1 does not accidentally foreclose them. The first three
are in the order they are worth doing; the rest are decisions not to build something.

- **Admin HTTP server** and `http.webhook` triggers. The one module unlocks triggering a job and
  reading its history *remotely*, plus CI push-notification, which is why it is first in line after
  v1. Locally both are already answered, by `butler trigger` and `butler runs` (§10.1).
- **Docker steps** (`pull`, `compose`, `run`, `prune`) and registry polling as a `docker.image`
  trigger.
- **Job templates** (`extends:` + `with:`). Once there are more than two apps this becomes the main
  DRY lever. It is a pure config-expansion pass before validation, so it can be added without
  touching the runtime.
- **A trigger that watches a URL.** `http.download` is the half of pulling an artifact that needs
  no new concepts: a job already knows how to fetch one, given a version. Deciding that a *new*
  version exists means polling a URL, reading whatever shape that endpoint answers with, and a
  dedupe key over it, which is a design rather than a step. Wait for a case that a
  `schedule.every` job with a `discover:` block cannot already express.
- **More vocabulary where a case turns up**: `systemd.enable`,
  `control.parallel`/`control.group`, `git.*`, `pkg.*`, a `command.output` trigger, a
  `systemd.state` trigger. Each is a record and a class (§7.1); none needs a design.
- **Automatic rollback** by replaying completed steps in reverse. Attractive and genuinely hard to
  get right; explicit `on_failure:` covers the real cases with no hidden behaviour. Revisit only
  with evidence.
- **Secret redaction** in logs and captured process output. A scan of stdout against known secret
  values, plus a redacting log encoder. Straightforward whenever a secret actually ends up
  somewhere it shouldn't.
- **Config reload on SIGHUP.** Restart is cheap and safe for the reasons in §10.3.
- **Privilege-dropping `run_as`.** `sudo -u` is sufficient.
- **Multi-host orchestration.** Butler is a single-host agent. Coordinating hosts is a different
  product and should not leak into this model.
