# Configuration reference

Everything Butler does is driven by one YAML file, given to the daemon at startup. This page is the
reference; [DESIGN.md](../DESIGN.md) says why it is shaped this way.

`butler validate` checks every key on this page, and `butler steps` prints the step half of it
straight from the registry, so it never falls behind the build you are running.

---

## Document

```yaml
version: 1

settings: { ... }        # daemon-wide policy
secrets: { ... }        # where ${secret.*} comes from
vars: { ... }        # values shared by every job
notifiers: { ... }       # named notification channels
jobs: { ... }        # required: at least one
```

**YAML anchors and aliases are refused**, merge keys included. An alias binds as the anchor's *name*
rather than its value, so `copy: *base` would silently mean the string `"base"`. Put the value in
`vars:` and reference it with `${vars.name}`.

A repeated key is an error rather than last-one-wins.

### `settings`

| Key                   | Default                  | Meaning                                                                        |
|-----------------------|--------------------------|--------------------------------------------------------------------------------|
| `state_dir`           | `/var/lib/butler`        | Where per-job state and run records are written.                               |
| `log_format`          | `json`                   | `json` or `text`. Applies to the daemon; interactive commands are always text. |
| `max_concurrent_runs` | `4`                      | Global bound on runs in flight, across all jobs.                               |
| `poll_interval`       | `5s`                     | Default polling interval for polling triggers.                                 |
| `shutdown_grace`      | `2m`                     | How long a shutdown lets in-flight runs finish before cancelling them.         |
| `run_retention`       | `{count: 200, age: 30d}` | How much run history to keep. Both apply: whichever drops a record first wins. |
| `plugins_dir`         | none                     | Directory of jars holding third-party steps, triggers or notifiers.            |

### `secrets`

```yaml
secrets:
  from_env: true                     # ${secret.FOO} resolves from $FOO
  file: /etc/butler/secrets.yaml     # a flat name: value mapping
```

A named-but-absent file is not an error, since configs are routinely validated somewhere other than
the host they run on. A file that exists and cannot be parsed is.

### `vars`

A flat mapping shared by every job, readable as `${vars.name}`. Job-level `vars:` merge over it, and
a `control.set` step writes into the same namespace.

### `notifiers`

Named channels, referenced by name from a job's `notify: to:` and from the `notify.send` step. Their
parameters resolve against the run, so a webhook can come from a secret.

```yaml
notifiers:
  ops:
    uses: notify.slack
    webhook: ${secret.SLACK_WEBHOOK}
    channel: "#deploys"
```

| `uses`           | Parameters                                                                  |
|------------------|-----------------------------------------------------------------------------|
| `notify.slack`   | `webhook`, `channel`, `username`, `icon_emoji`                              |
| `notify.discord` | `webhook`, `username`                                                       |
| `notify.ntfy`    | `server` (default `https://ntfy.sh`), `topic`, `title`, `priority`, `token` |
| `notify.webhook` | `url`, `field` (default `text`), `headers`                                  |

A channel that refuses a message is logged; it never fails a run that otherwise succeeded.

---

## Jobs

| Key                                    |          | Meaning                                                             |
|----------------------------------------|----------|---------------------------------------------------------------------|
| `on`                                   | required | List of triggers.                                                   |
| `steps`                                | required | The pipeline.                                                       |
| `description`                          |          | Free text, shown by `butler check`.                                 |
| `vars`                                 |          | Job-local vars, merged over the global ones.                        |
| `env`                                  |          | Environment applied to every process-backed step in the job.        |
| `discover`                             |          | Observation steps that populate `state.*`, run before `when:`.      |
| `when`                                 |          | Run only if true, evaluated after `discover:`.                      |
| `concurrency`                          |          | `group`, `mode`, `queue_newest_only`.                               |
| `timeout`                              |          | Whole-run limit. Exceeding it fails the run.                        |
| `on_failure` / `on_success` / `always` |          | Lifecycle hooks.                                                    |
| `persist`                              |          | State keys written after a successful run.                          |
| `notify`                               |          | `to`, `on: [success, failure]`, and a message template per outcome. |

A job with only `on:` and `steps:` is valid, and that is the floor the DSL stays usable at.

### Run lifecycle

```
Trigger fires
   ├─ Dedupe        key matches the last one processed?      → drop, nothing happens
   ├─ Gate          concurrency group busy?                  → queue / skip / cancel-previous
   ├─ Context       vars + trigger facts + state + env + secrets
   ├─ discover[]    observe the host; overlay onto state.*
   ├─ when?         judged against observed reality          → false: SKIPPED
   ├─ steps[]       sequential; any failure aborts the rest
   ├─ on_failure[] / on_success[] / always[]
   ├─ persist       declared state keys                      (SUCCESS only)
   ├─ record        state + dedupe key + run record          (any status but CANCELLED)
   └─ notify
```

A run ends `SUCCESS`, `FAILED`, `SKIPPED` or `CANCELLED`.

- `continue_on_error: true` on a failing step records `failed` in that step's own result but does
  **not** fail the run, so `on_success:` and `persist:` still happen.
- Exceeding the job `timeout:` ends the run **`FAILED`**, so `on_failure:` gets to clean up. A
  timeout is a failure, not a cancellation.
- `CANCELLED` is only for a run displaced by `cancel_previous` or cut short by shutdown. It runs no
  hooks and writes nothing, because nothing was wrong: the work was withdrawn.
- `always:` runs on any terminal status except `CANCELLED`.

### `concurrency`

```yaml
concurrency:
  group: api                 # default: the job name
  mode: queue                # queue | skip | cancel_previous
  queue_newest_only: true
```

One run of a group happens at a time. `queue_newest_only` is the interesting default: if 1.2.3 and
1.2.4 land two seconds apart, the 1.2.3 run finishes, the event waiting behind it is replaced by
1.2.4, and the host converges on the newest version rather than deploying twice. The displaced event
never runs and is logged as such.

`skip` drops the arriving event; `cancel_previous` withdraws the run in flight, which then ends
`CANCELLED`.

### `discover`

Observation-only steps that run **before `when:`, on every event**, and whose `extract:` values
populate `state.*`.

```yaml
discover:
  - name: Ask the running service what it is
    uses: http.request
    url: http://localhost:8080/health
    timeout: 3s
    extract:
      deployed_version: json.version

  - name: Fall back to the symlink if the service is down
    uses: fs.readlink
    path: /srv/apps/api/current
    when: not exists(state.deployed_version)
    extract:
      deployed_version: basename(value)
```

Five rules that matter:

1. `state.*` is persisted state **overlaid with** whatever discovery observed. A job never has to
   care which one it got.
2. **Discovery failure is not run failure.** A step that errors or times out contributes nothing and
   the persisted value stands. An `extract:` that produces no value is the same case: a health
   endpoint that changed shape must not read as "nothing is deployed".
3. **Any step may be used**, including `shell.run`. Keeping the host read-only during discovery is
   the config author's responsibility.
4. **Discovery runs for real under `--dry-run`**, or the decision a dry run reports would be wrong.
   The plan labels the section `(executed for real)`.
5. A run that ends `SKIPPED` still writes discovered state and records the dedupe key, or every poll
   would rediscover and re-skip forever.

`butler validate` warns when a job's `when:` references `state.*` but the job declares no
`discover:` block, because that combination is almost always a first-run bug.

For apps with no HTTP endpoint, in rough order of preference: `fs.readlink` on the current release
symlink, `fs.read` on a version file, `fs.list` over a releases directory ordered by semver, or
`shell.run` with `myapp --version` and a `match()` over its stdout.

---

## Steps

Step type in `uses:`, parameters as sibling keys.

```yaml
- name: Human label, used in logs and notifications
  uses: http.request
  when: <condition>                  # skip this step if false
  register: probe                    # expose the result as steps.probe.*
  timeout: 30s
  retry:
    attempts: 3
    delay: 5s
    backoff: exponential             # fixed | exponential
    on: failure                      # failure | timeout | always
  continue_on_error: false
  env: { TOKEN: ${ secret.API_TOKEN } }
  working_dir: /srv/apps/api/current
  run_as: appuser
```

Those keys are **reserved** on every step and may never be a parameter name, along with `extract`,
which is valid only inside a `discover:` block.

### Results

Every step produces the same shape, which is what `register:` exposes:

| Field                           |                                          |
|---------------------------------|------------------------------------------|
| `status`                        | `ok` / `failed` / `skipped`              |
| `ok`, `failed`, `skipped`       | booleans, for readable conditions        |
| `duration`, `attempts`          |                                          |
| `stdout`, `stderr`, `exit_code` | process-backed steps only                |
| *step-specific*                 | `previous_target`, `json`, `sha256`, ... |

Where a step's own output shares a name with a common field, as `http.request`'s `status` does, the
step's wins.

### The vocabulary

`butler steps` prints this with every parameter. Summarised:

| Namespace | Steps                                                                                         |
|-----------|-----------------------------------------------------------------------------------------------|
| `control` | `log`, `set`, `assert`, `sleep`, `fail`                                                       |
| `shell`   | `run` (through a shell), `exec` (argv, no shell)                                              |
| `fs`      | `copy`, `move`, `symlink`, `readlink`, `read`, `list`, `exists`, `mkdir`, `template`, `prune` |
| `systemd` | `restart`, `start`, `stop`, `reload`, `wait_active`, `status`                                 |
| `http`    | `request`, `wait`                                                                             |
| `notify`  | `send`                                                                                        |

Three worth knowing in detail:

- **`fs.symlink`** swaps atomically by default (temp symlink beside the target, then an atomic move)
  so a reader never observes a missing link, and outputs `previous_target`. That is what makes an
  `on_failure:` rollback a four-line step. Its `simulate()` reads the current link for real, so a
  dry run reports the true previous target.
- **`fs.prune`** never deletes what something still points at. It refuses any entry named by
  `protect:` or targeted by a symlink beside the releases directory, whatever the `keep:` arithmetic
  says, and reports what it spared. `keep:` is required: a missing number must not read as zero.
- **`http.wait`** has no timeout of its own - the step's reserved `timeout:` is the limit, and the
  step turns being cut off into an account of how far it got: probes, elapsed, last status and body.
  Its `until:` sees `status`, `headers`, `body` and `json` for the probe in flight.

The `systemd` verbs that mutate a unit put `sudo` in front by default; `sudo: false` turns that off
for a user unit. That is separate from `run_as:`, which says which user to become.

---

## Triggers

A trigger's parameters are **never templated**: a watcher is started before any event exists, so
there is no run to resolve a `${}` against. Everything a trigger parses for itself - the regex, the
`order_by:` comparator - is settled before the watch thread starts, and refused by `butler validate`
before that.

### `file.appeared`

The one the main use case rests on.

```yaml
- uses: file.appeared
  dir: /srv/artifacts/api
  match: 'api-(?<version>\d+\.\d+\.\d+)\.jar'
  settle: 10s                  # size and mtime unchanged this long before firing
  order_by: semver(version)    # fire only for the greatest candidate
  on_startup: latest           # latest | none | all
```

- **Polling is the primary mechanism.** A `WatchService` misses files written before startup and
  coalesces under load; a 5s poll of one directory costs nothing.
- **Settle detection is mandatory**, because deploying a half-uploaded jar is the most likely
  first-week failure.
- **Named capture groups become `trigger.*` facts**, so `${trigger.version}` is whatever the regex
  captured. `trigger.path`, `trigger.name`, `trigger.size` are always there.
- **`order_by:` means only the greatest candidate fires**, so dropping an old artifact into the
  directory cannot trigger a downgrade. It is an expression over the captured facts.
- Dedupe key is the absolute path plus size plus mtime.

### The rest

| Trigger          | Parameters                                                          |
|------------------|---------------------------------------------------------------------|
| `file.changed`   | `path`, `settle`, `on_startup`. Fires on a content hash change.     |
| `schedule.every` | `interval` (default `1h`).                                          |
| `schedule.cron`  | `expression` (5-field), `timezone`.                                 |
| `manual`         | none. Fires only by `butler trigger`, and is the testing workhorse. |

---

## The expression language

A small hand-written grammar. No config-as-code escape hatch.

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

**Two contexts.** A *condition* (`when:`, `until:`, `that:`, `order_by:`, `extract:`) takes a bare
expression; `${x}` is accepted as a synonym for `x` and stripped at parse time. Every other value is
a *string*: literal text with `${expr}` holes, where `$${` escapes a literal `${`.

**Namespaces**, and nothing else:

|                  |                                                                                                                           |
|------------------|---------------------------------------------------------------------------------------------------------------------------|
| `vars.*`         | global `vars:` merged with job `vars:`, then any `control.set` step                                                       |
| `trigger.*`      | facts from the event, including regex capture groups                                                                      |
| `steps.<name>.*` | results of steps that declared `register:`                                                                                |
| `state.*`        | persisted values, overlaid with what `discover:` observed                                                                 |
| `env.*`          | process environment                                                                                                       |
| `secret.*`       | resolved secrets                                                                                                          |
| `run.*`          | `id`, `job`, `trigger`, `started_at`, `dry_run`; in hooks and `notify:` also `status`, `duration`, `failed_step`, `error` |
| `butler.*`       | `version`, `host`                                                                                                         |

An unknown *path* evaluates to `null`; an unknown *namespace* is a validation error, so
`${triger.version}` is caught at load time while `default(state.deployed_version, "0.0.0")` still
works on a first run.

**Functions:** `semver(s)`, `exists(path)`, `default(a, b)`, `len(x)`, `int(x)`, `lower(s)`,
`upper(s)`, `trim(s)`, `basename(p)`, `dirname(p)`, `match(s, re[, group])`, `file_exists(p)`,
`now()`.

`semver()` returns a comparable value, so `semver(a) > semver(b)` never ranks `1.10.0` below
`1.9.0`.

**A double-quoted string takes escapes; a single-quoted one is raw**, exactly as in YAML. That is
what makes a regex readable: `match(stdout, 'v?(\d+\.\d+\.\d+)', 1)`.

**Null is not silently ordered.** `==` and `!=` accept null on either side, and `matches` and
`contains` treat it as no-match. Ordering (`<`, `>`, `<=`, `>=`) against null is an error: there is
no defensible answer, and guessing one would let a first-run deploy decision turn on a value nobody
supplied. Use `default()` or `exists()` to say what should happen when the value is missing.

**Durations** are `\d+(ms|s|m|h|d)` everywhere: `timeout:`, `settle:`, `interval:`, `delay:`,
`wait_active:` and the duration literal in an expression all take the same form. A bare number is an
error, because `timeout: 30` must never silently mean 30 milliseconds.
