# Configuration reference

Everything Butler does is driven by YAML, given to the daemon at startup: one file, or
[several](#several-files) read as one. This page is the reference; [DESIGN.md](DESIGN.md) says why
it is shaped this way, and the [README](../README.md) is the guide to getting one running.

`butler validate` checks every key on this page, and `butler steps` prints the step half of it
straight from the registry, so it never falls behind the build you are running.

**Contents:** [Document](#document) · [Jobs](#jobs) · [Steps](#steps) ·
[Trigger reference](#trigger-reference) · [Step reference](#step-reference) ·
[Notifier reference](#notifier-reference) · [Expressions](#the-expression-language)

---

## Document

```yaml
version: 1

settings: { ... }        # daemon-wide policy
secrets: { ... }         # where ${secret.*} comes from
vars: { ... }            # values shared by every job
notifiers: { ... }       # named notification channels
jobs: { ... }            # required: at least one
```

**YAML anchors and aliases are refused**, merge keys included. An alias binds as the anchor's *name*
rather than its value, so `copy: *base` would silently mean the string `"base"`. Put the value in
`vars:` and reference it with `${vars.name}`.

A repeated key is an error rather than last-one-wins.

### Several files

`--config` may be given more than once. The files are read in order and merged into one config, so
a job in the last file uses `vars:` from the first, and `notify: {to: ops}` finds a notifier
wherever it was defined.

```bash
butler --config /etc/butler/butler.yaml \
       --config /etc/butler/jobs/api.yaml \
       --config /etc/butler/jobs/backup.yaml
```

| Key                                          | Across files                                          |
|----------------------------------------------|-------------------------------------------------------|
| `jobs`, `notifiers`, `vars`, `secrets.files` | Accumulate. Defining a name twice is an error.        |
| `settings`, `secrets.from_env`               | Policy: one file only. Setting one twice is an error. |
| `version`                                    | Any file may carry it; each must say `1`.             |

Later files add, never override. Validation judges the whole, so a file holding only `vars:` is
fine and a set of files that between them define no jobs is not. Each problem names the file it is
in, with that file's line and column.

### `settings`

| Key                   | Default                  | Meaning                                                                                              |
|-----------------------|--------------------------|------------------------------------------------------------------------------------------------------|
| `state_dir`           | `/var/lib/butler`        | Where per-job state and run records are written.                                                     |
| `log_format`          | `json`                   | `json` or `text`. Applies to the daemon; interactive commands are always text.                       |
| `max_concurrent_runs` | `4`                      | Global bound on runs in flight, across all jobs. At least 1.                                         |
| `poll_interval`       | `5s`                     | Default polling interval for polling triggers. Must be more than zero.                               |
| `shutdown_grace`      | `2m`                     | How long a shutdown lets in-flight runs finish before cancelling them.                               |
| `run_retention`       | `{count: 200, age: 30d}` | Default run history per job. Both apply: whichever drops a record first wins. A job may override it. |
| `plugins_dir`         | none                     | Directory of jars holding third-party steps, triggers or notifiers.                                  |

### `secrets`

```yaml
secrets:
  from_env: true                    # ${secret.FOO} resolves from $FOO
  files: /etc/butler/secrets.yaml   # a flat name: value mapping
```

A named-but-absent file is not an error, since configs are routinely validated somewhere other than
the host they run on. A file that exists and cannot be parsed is.

`files:` takes one path or a list of them, read in order and merged:

```yaml
secrets:
  files:
    - /etc/butler/secrets.yaml
    - /etc/butler/secrets.d/api.yaml
```

A name may be defined in only one of them: a duplicate is an error rather than one credential
silently shadowing another. The list also accumulates across [config files](#several-files), so a
config file holding one app's jobs can name that app's secrets file beside them. Naming the same
file twice reads it once.

### `vars`

A flat mapping shared by every job, readable as `${vars.name}`. Job-level `vars:` merge over it, and
a `control.set` step writes into the same namespace. Job vars resolve after the global ones, so they
may refer to them:

```yaml
vars:
  releases_root: /srv/apps
jobs:
  api:
    vars:
      release_dir: ${vars.releases_root}/api/releases
```

### `notifiers`

Named channels, referenced by name from a job's `notify: to:` and from the `notify.send` step. Their
parameters resolve against the run, so a webhook can come from a secret. See the
[notifier reference](#notifier-reference).

```yaml
notifiers:
  ops:
    uses: notify.slack
    webhook: ${secret.SLACK_WEBHOOK}
    channel: "#deploys"
```

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
| `run_retention`                        |          | `count`, `age`. Overrides `settings.run_retention` for this job.    |

A job with only `on:` and `steps:` is valid, and that is the floor the DSL stays usable at.

### `run_retention`

History is kept per job, so a job that fires every ten seconds cannot crowd out one that deploys
once a week. `settings.run_retention` is the budget each job gets; a job setting its own overrides
the fields it names and inherits the rest:

```yaml
settings:
  run_retention: { count: 200, age: 30d }

jobs:
  heartbeat:
    run_retention: { count: 20 }     # 20 records, still dropped after 30d
```

This is how far back `butler runs <job>` can see. Records outside either bound are deleted, and
the index line naming them goes with them.

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
populate `state.*`. `extract:` maps a state key to an expression over the step's own result.

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
   endpoint that changed shape must not read as "nothing is deployed". That is what lets the
   fallbacks above chain safely.
3. **Any step may be used**, including `shell.run`. Keeping the host read-only during discovery is
   the config author's responsibility.
4. **Discovery runs for real under `--dry-run`**, or the decision a dry run reports would be wrong.
   The plan labels the section `(executed for real)`.
5. A run that ends `SKIPPED` still writes discovered state and records the dedupe key, or every poll
   would rediscover and re-skip forever.

An `extract:` expression sees the step's own outputs - `json` and `status` for an HTTP probe, `value`
for a symlink read, `stdout` for a command - beside the eight namespaces.

`butler validate` warns when a job's `when:` references `state.*` but the job declares no
`discover:` block, because that combination is almost always a first-run bug.

### `persist`

Values written to `state.*` after a **successful** run, readable by the next one.

```yaml
persist:
  deployed_version: ${trigger.version}
  current_release: ${vars.releases_root}/api/releases/${trigger.version}
```

Stored as JSON scalars, exactly as the run report showed them. There is no `state.put` step: state
mutation scattered through a pipeline is how half-written state survives a mid-run failure.

### `notify`

```yaml
notify:
  to: ops                          # a name from the notifiers: block
  on: [ success, failure ]         # which outcomes fire; both by default
  success: ":rocket: api ${trigger.version} deployed in ${run.duration}"
  failure: ":fire: api ${trigger.version} FAILED at ${run.failed_step}"
```

Messages see `run.status`, `run.duration`, `run.duration_ms`, `run.failed_step` and `run.error` as
well as the usual namespaces. An outcome with no message template sends nothing.

`run.duration` is elapsed time for a person to read, rounded to whole seconds with zero units
omitted: `47s`, `20m 47s`, `1h 1s`. `run.duration_ms` is the exact figure as a number, for a
message that wants millisecond precision or a condition that wants to compare
(`run.duration_ms > 300000`).

**A message is rendered after the hooks have run**, and a hook step registers like any other, so
the failure message can say whether the rollback took:

```yaml
on_failure:
  - name: Roll back symlink
    uses: fs.symlink
    link: /srv/apps/api/current
    target: ${steps.symlink.previous_target}
    register: rollback

notify:
  to: ops
  on: [ failure ]
  failure: ":fire: api FAILED at ${run.failed_step}, rollback ${steps.rollback.status}"
```

That reads `ok`, `failed` or `skipped`, which is three different answers: it worked, it did not, or
there was nothing to undo. `run.status` and `run.failed_step` are fixed before the hooks start, so
they always describe the pipeline. A hook step that fails is logged and leaves the run's own status
standing, and the only place that shows up is `steps.<name>.*`.

To send something the moment a run fails, before any cleanup, use a [`notify.send`](#notifysend)
step at the top of `on_failure:`. That is two messages, deliberately.

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
  env: { TOKEN: "${secret.API_TOKEN}" }
  working_dir: /srv/apps/api/current
  run_as: appuser
```

Those keys are **reserved** on every step and may never be a parameter name, along with `extract`,
which is valid only inside a `discover:` block.

| Reserved key        | Meaning                                                                                          |
|---------------------|--------------------------------------------------------------------------------------------------|
| `name`              | Label for logs, the plan and `${run.failed_step}`. Defaults to the step type.                    |
| `uses`              | Required. The step type.                                                                         |
| `when`              | A condition. False skips the step without failing the run.                                       |
| `register`          | Exposes the result as `steps.<name>.*`. A usable identifier, unique across the job.              |
| `timeout`           | How long the step may take. The runtime enforces it by interrupting the step.                    |
| `retry`             | `attempts` (at least 1), `delay`, `backoff: fixed\|exponential`, `on: failure\|timeout\|always`. |
| `continue_on_error` | A failure is recorded but does not fail the run.                                                 |
| `env`               | Environment for a process-backed step, merged over the job's `env:`.                             |
| `working_dir`       | Directory a process-backed step starts in.                                                       |
| `run_as`            | User to become, as `sudo -u <user>`. Needs its own sudoers grant.                                |

**A `steps.<name>` reference has to be one that could exist by the time it is read**, and
`butler validate` says so where it is not. A step may only name a step that already ran, so a
forward reference is an error rather than an empty value. `persist:` and the `notify:` messages are
rendered after the hooks, so each may name whatever its own outcome ran: a `failure:` message can
read an `on_failure:` register, a `success:` message and `persist:` can read an `on_success:` one,
and either can read `always:`. Naming across that line is an error, because it could only ever
render as nothing.

A step's own `timeout:` and the job's `timeout:` are not two racing mechanisms: the job's is a
deadline that caps what each step is given, so a job with five minutes left never hands a step ten.

### Results

Every step produces the same shape, which is what `register:` exposes:

| Field                     |                                               |
|---------------------------|-----------------------------------------------|
| `status`                  | `ok` / `failed` / `skipped`                   |
| `ok`, `failed`, `skipped` | booleans, for readable conditions             |
| `duration`, `attempts`    |                                               |
| `message`                 | why it failed, when there is something to say |
| *step-specific*           | the **Outputs** of each step below            |

Where a step's own output shares a name with a common field, as `http.request`'s `status` does, the
step's wins. `ok`, `failed` and `skipped` still say how the step itself went.

```yaml
- uses: http.request
  url: http://localhost:8080/health
  register: probe
- uses: control.assert
  that: steps.probe.ok and steps.probe.json.version == trigger.version
```

---

## Trigger reference

A trigger's parameters are **never templated**: a watcher is started before any event exists, so
there is no run to resolve a `${}` against. Everything a trigger parses for itself - the regex, the
cron expression, the `order_by:` comparator - is settled before the watch thread starts, and refused
by `butler validate` before that.

Every event carries a **dedupe key**. A run only starts when the key differs from the last one the
job processed, which is what makes a restart cheap. A trigger with no key is always new work.

A parameter marked **required** is refused by `butler validate`, and again by the trigger before its
watch thread starts, so a watcher never dies leaving the daemon reporting that it watches the job.

### `file.appeared`

Fires when a new file, or with `kind: dir` a new directory, settles in a directory. The trigger the
main use case rests on.

```yaml
- uses: file.appeared
  dir: /srv/artifacts/api
  match: 'api-(?<version>\d+\.\d+\.\d+)\.jar'
  settle: 10s
  order_by: semver(version)
  on_startup: latest
```

| Parameter       | Type                        | Default                  |                                                                                     |
|-----------------|-----------------------------|--------------------------|-------------------------------------------------------------------------------------|
| `dir`           | path                        |                          | **required.** Directory to watch. Not recursive.                                    |
| `kind`          | `file` \| `dir`             | `file`                   | Whether to watch for files or for directories.                                      |
| `match`         | regex                       | everything               | Matched against the whole name. Named groups become facts.                          |
| `settle`        | duration                    | `10s`                    | Size and mtime must be unchanged this long before firing.                           |
| `order_by`      | *condition*                 |                          | Ranks candidates over their own facts. Only the greatest fires.                     |
| `on_startup`    | `latest` \| `none` \| `all` | `latest`                 | What to do about candidates already there when the daemon starts.                   |
| `poll_interval` | duration                    | `settings.poll_interval` | How often to re-scan `dir`. Overrides the daemon-wide default for this one watcher. |

**Facts:** `path`, `name`, `dir`, `size`, `modified`, plus every named capture group in `match`.
**Dedupe key:** absolute path, size and mtime, so the same file rewritten is new work. A directory
adds its entry count.

- **Polling is the primary mechanism.** A `WatchService` misses files written before startup and
  coalesces under load; a 5s poll of one directory costs nothing.
- **Settle detection is mandatory**, because deploying a half-uploaded jar is the most likely
  first-week failure.
- **`order_by:` means only the greatest candidate fires**, so dropping an old artifact into the
  directory cannot trigger a downgrade. It is an expression over the captured facts rather than a
  field name, which is what lets it read `semver(version)`.

#### Watching for directories

For a release that arrives unpacked rather than as one file. `match:` and `order_by:` read the
directory's own name, so nothing else about the trigger changes:

```yaml
- uses: file.appeared
  dir: /srv/artifacts/api
  kind: dir
  match: 'api-(?<version>\d+\.\d+\.\d+)'
  settle: 10s
  order_by: semver(version)
```

- **`size` and `modified` are aggregates**: the total bytes of the regular files anywhere beneath
  the directory, and the newest mtime in the whole tree. A directory's own size is a constant and
  its own mtime moves only when an entry is added or removed directly in it, so neither notices a
  large file three levels down still being written, and settle detection built on them would fire
  on a half-copied release.
- **An empty directory never fires.** A `mkdir` that a slow-starting copy has not reached yet would
  otherwise settle and trigger a run against nothing.
- **The tree is walked on every poll**, one `lstat` per entry, rather than the single `stat` a file
  costs. That is nothing for a release directory; widen `poll_interval:` before pointing this at a
  tree with a million files in it. Symlinks are not followed.
- **`file.changed` has no directory mode.** Its model is one path's content hash, and hashing a
  tree is a different feature with different failure modes.

### `file.changed`

Fires when the contents of one file change, by content hash rather than mtime, so a
config-management tool rewriting a file every hour with the same contents redeploys nothing.

```yaml
- uses: file.changed
  path: /etc/nginx/nginx.conf
  settle: 5s
```

| Parameter       | Type                        | Default                  |                                                                                       |
|-----------------|-----------------------------|--------------------------|---------------------------------------------------------------------------------------|
| `path`          | path                        |                          | **required.** The one file to watch.                                                  |
| `settle`        | duration                    | `10s`                    | Size and mtime must be unchanged this long before it is read.                         |
| `on_startup`    | `latest` \| `none` \| `all` | `latest`                 | `none` waits for a change; the other two report what is already there.                |
| `poll_interval` | duration                    | `settings.poll_interval` | How often to re-check `path`. Overrides the daemon-wide default for this one watcher. |

**Facts:** `path`, `name`, `dir`, `size`, `modified`, `sha256`.
**Dedupe key:** absolute path and content hash.

### `schedule.every`

Fires on a fixed interval, each one counted from the last. The first firing is one interval away
rather than immediate, because a daemon that ran every job the moment it started would turn a
restart into a deployment.

```yaml
- uses: schedule.every
  interval: 15m
```

| Parameter  | Type     | Default |
|------------|----------|---------|
| `interval` | duration | `1h`    |

The interval has to be more than zero, or the trigger would fire in a loop rather than on a
schedule.

**Facts:** `fired_at`. **Dedupe key:** none, so every firing is new work.

### `schedule.cron`

Fires on a five-field cron expression: minute, hour, day of month, month, day of week.

```yaml
- uses: schedule.cron
  expression: 0 3 * * *
  timezone: Europe/London
```

| Parameter    | Type           | Default         |                            |
|--------------|----------------|-----------------|----------------------------|
| `expression` | cron           |                 | **required.** Five fields. |
| `timezone`   | IANA zone name | the host's zone |                            |

Each field takes `*`, `a` or `a-b`, any of them with a trailing `/step`, comma-separated. Months
(`jan`) and days (`mon`) may be named. Day `0` and day `7` are both Sunday. With both day fields
restricted, either matches, as every crontab does: `0 0 1 * mon` fires on the first of the month and
on every Monday. A local time the clock skips at a daylight-saving boundary is moved past the gap
rather than dropped, so a nightly job stays nightly.

**Facts:** `fired_at`. **Dedupe key:** none.

### `manual`

Fires only when `butler trigger <job>` asks it to. The testing workhorse, and the right trigger for a
job that only ever runs by hand.

```yaml
- uses: manual
```

No parameters. **Facts:** whatever `--set k=v` supplies. **Dedupe key:** none, so a run asked for by
hand is never suppressed as already done.

---

## Step reference

`butler steps` prints this from the registry, and `butler steps <name>` prints one of them. Every
step also takes the [reserved keys](#steps) above.

**Required** below means `butler validate` refuses a config that omits it. Presence is what is
checked, so a required value may be a `${...}` only a run can resolve. Every parameter is
interpolated before the step sees it, except the ones marked *condition*, which are parsed instead.

### `control`

#### `control.log`

Write a message into the run log.

| Parameter | Type                                   | Default |
|-----------|----------------------------------------|---------|
| `message` | text                                   | empty   |
| `level`   | `debug` \| `info` \| `warn` \| `error` | `info`  |

#### `control.set`

Set variables the rest of the run can read. The values land in `vars.*` rather than under the step's
own name.

| Parameter | Type    | Default |
|-----------|---------|---------|
| `vars`    | mapping | empty   |

```yaml
- uses: control.set
  vars:
    release_path: ${vars.releases_root}/${trigger.version}
```

#### `control.assert`

Fail the run unless a condition holds.

| Parameter | Type        | Default                    |
|-----------|-------------|----------------------------|
| `that`    | *condition* | **required**               |
| `message` | text        | `assertion failed: <that>` |

#### `control.sleep`

Wait for a fixed duration, for when something needs a moment and there is nothing to poll.

| Parameter  | Type     | Default |
|------------|----------|---------|
| `duration` | duration | `0s`    |

#### `control.fail`

Fail the run with a message. Paired with `when:` it is the end of a branch a pipeline should never
reach.

| Parameter | Type | Default        |
|-----------|------|----------------|
| `message` | text | `control.fail` |

### `shell`

Both steps report **Outputs:** `stdout`, `stderr`, `exit_code`. A non-zero exit fails the step, with
the last line of output in the message. Output is captured into a bounded buffer that keeps the
tail, so a chatty process costs nothing.

A script that starts something in the background hands it the same pipes, so the output is still
open after the script itself has finished. The step waits a moment for the last of it, then reports
what arrived and logs that it did; it does not wait for the service to stop.

#### `shell.run`

Run a script through a shell. The escape hatch of the whole design, and it runs with the daemon's
privileges unless `run_as:` says otherwise.

| Parameter | Type | Default      |
|-----------|------|--------------|
| `script`  | text | **required** |
| `shell`   | text | `/bin/sh`    |

The script is interpolated like any other value, so a shell variable is written `$${HOME}`.

#### `shell.exec`

Run one program with an explicit argument list and no shell. Preferred wherever an argument comes
from an event: a path holding a space is passed through untouched rather than re-split by a shell.

| Parameter | Type | Default      |
|-----------|------|--------------|
| `argv`    | list | **required** |

```yaml
- uses: shell.exec
  argv: [ /usr/bin/rsync, -a, "${trigger.path}", /srv/backup/ ]
```

### `fs`

#### `fs.copy`

Copy a file, creating parent directories and setting its mode and owner.

| Parameter   | Type       | Default     |                                    |
|-------------|------------|-------------|------------------------------------|
| `from`      | path       |             | **required**                       |
| `to`        | path       |             | **required**                       |
| `mode`      | text       | leave as-is | Octal, quoted: `"0640"`.           |
| `owner`     | text       | leave as-is | User name, applied after `mode`.   |
| `group`     | text       | leave as-is | Group name.                        |
| `mkdirs`    | true/false | `false`     | Create the directories above `to`. |
| `overwrite` | true/false | `true`      |                                    |

**Outputs:** `path`, `bytes`.

See [owner and group](#owner-and-group) for what happens where the host has neither.

#### `fs.move`

Move a file or directory. Atomic where both sides are on one filesystem, a copy-and-delete
otherwise, as `mv` is.

| Parameter   | Type       | Default     |              |
|-------------|------------|-------------|--------------|
| `from`      | path       |             | **required** |
| `to`        | path       |             | **required** |
| `mode`      | text       | leave as-is |              |
| `owner`     | text       | leave as-is |              |
| `group`     | text       | leave as-is |              |
| `mkdirs`    | true/false | `false`     |              |
| `overwrite` | true/false | `true`      |              |

**Outputs:** `path`.

#### `fs.symlink`

Point a symlink at a target and report the target it replaced. That is what makes an `on_failure:`
rollback a four-line step.

| Parameter | Type       | Default |                                                                                         |
|-----------|------------|---------|-----------------------------------------------------------------------------------------|
| `link`    | path       |         | **required.** The link to create or repoint.                                            |
| `target`  | path       |         | **required.** What it should point at.                                                  |
| `atomic`  | true/false | `true`  | Temp symlink beside it, then an atomic move, so a reader never observes a missing link. |

**Outputs:** `previous_target` (null if there was no link), `link`, `target`.

Reading the link changes nothing, so a dry run reports the true previous target and the rollback
branch describes correctly.

#### `fs.readlink`

Report what a symlink points at. Fails if the path is not a symlink, which inside a `discover:`
block leaves the persisted value standing.

| Parameter | Type | Default |              |
|-----------|------|---------|--------------|
| `path`    | path |         | **required** |

**Outputs:** `value`.

#### `fs.read`

Read a file's contents. They arrive exactly as they are on disk, trailing newline included; a config
that wants otherwise writes `trim(value)`.

| Parameter   | Type   | Default   |                                                                                       |
|-------------|--------|-----------|---------------------------------------------------------------------------------------|
| `path`      | path   |           | **required**                                                                          |
| `max_bytes` | number | `1048576` | Refuse anything larger, since the contents reach the run's memory and its state file. |

**Outputs:** `value`, `bytes`.

#### `fs.list`

List a directory, ordered and filtered. Entries come back least first, so `last` is the greatest.

| Parameter  | Type                             | Default     |                                                              |
|------------|----------------------------------|-------------|--------------------------------------------------------------|
| `dir`      | path                             |             | **required**                                                 |
| `match`    | regex                            | every entry | Matched against the whole entry name.                        |
| `order_by` | `name` \| `semver` \| `modified` | `name`      | A name that is not a version sorts below every name that is. |
| `limit`    | number                           | all         | Keep this many, counting back from the greatest.             |

**Outputs:** `entries` (a list of names), `count`, `first`, `last`.

#### `fs.exists`

Report whether a path exists, and what it is. Succeeds either way, because "no" is an answer; a job
that wants a missing path to end the run asserts on `exists`.

| Parameter | Type | Default |              |
|-----------|------|---------|--------------|
| `path`    | path |         | **required** |

**Outputs:** `exists`, `type` (`file`, `directory`, `symlink`, `other` or `missing`).

#### `fs.mkdir`

Create a directory.

| Parameter | Type       | Default     |                                        |
|-----------|------------|-------------|----------------------------------------|
| `path`    | path       |             | **required**                           |
| `mode`    | text       | leave as-is |                                        |
| `owner`   | text       | leave as-is | The directory itself, not its parents. |
| `group`   | text       | leave as-is |                                        |
| `parents` | true/false | `true`      | Create the directories above it too.   |

**Outputs:** `path`, `created` (false if it was already there).

#### `fs.template`

Write a file whose `${...}` holes are filled in from the run. Takes `from:` or `content:`, not both.

| Parameter | Type       | Default     |                                                         |
|-----------|------------|-------------|---------------------------------------------------------|
| `to`      | path       |             | **required**                                            |
| `from`    | path       |             | A template file, read and rendered by this step.        |
| `content` | text       |             | The template inline, rendered like any other parameter. |
| `mode`    | text       | leave as-is |                                                         |
| `owner`   | text       | leave as-is |                                                         |
| `group`   | text       | leave as-is |                                                         |
| `mkdirs`  | true/false | `false`     |                                                         |

**Outputs:** `path`, `bytes`.

#### `fs.prune`

Delete all but the newest entries of a directory.

| Parameter  | Type                             | Default    |                                                                     |
|------------|----------------------------------|------------|---------------------------------------------------------------------|
| `dir`      | path                             |            | **required**                                                        |
| `keep`     | number                           |            | **required.** A missing number must not read as zero.               |
| `order_by` | `name` \| `semver` \| `modified` | `modified` |                                                                     |
| `protect`  | list of paths                    | none       | A bare name means an entry of `dir`; an absolute path means itself. |

**Outputs:** `deleted`, `kept`, `protected`, all lists of names.

**It never deletes what something still points at.** Anything named by `protect:`, or targeted by a
symlink beside the pruned directory, is kept whatever the `keep:` arithmetic says, and the step
reports what it spared. After a rollback by hand the running release is an old one, and deleting it
takes the application down.

#### `fs.delete`

Delete one named path. `fs.prune` decides which entries of a directory to keep; this deletes the
thing you name and nothing else.

| Parameter   | Type       | Default |                                                           |
|-------------|------------|---------|-----------------------------------------------------------|
| `path`      | path       |         | **required**                                              |
| `recursive` | true/false | `false` | Permission to delete a directory that has anything in it. |

**Outputs:** `path`, `deleted` (false if there was nothing there).

Three rules, all of them about the paths a config did not mean to name:

1. **A directory with anything in it needs `recursive: true`.** Without it the step fails and
   deletes nothing, so a path that turns out to be more than you expected stops the run.
2. **A symlink is removed as the link it is**, leaving what it points at alone.
3. **The root of a filesystem is refused**, because an unset var is how `${vars.root}/releases`
   becomes `/`. An empty `path:` is refused for the same reason: it would mean the daemon's
   working directory.

Deleting what is not there **succeeds** and reports `deleted: false`. Cleanup runs after work that
may not have got far enough to leave anything behind, and a hook that fails on the second run is
worse than useless. A dry run counts what a recursive delete would take:

```
    3  Clear staging                  fs.delete
         would delete /srv/apps/api/staging
               a directory holding 41 entries
```

#### `fs.unpack`

Unpack a tar archive into a directory, for a release that ships as a tarball rather than as one
file.

| Parameter          | Type       | Default |                                             |
|--------------------|------------|---------|---------------------------------------------|
| `from`             | path       |         | **required.** The archive.                  |
| `to`               | path       |         | **required.** The directory to unpack into. |
| `strip_components` | number     | `0`     | Leading path elements to drop.              |
| `mkdirs`           | true/false | `true`  | Create `to` if it is missing.               |

**Outputs:** `path`, `stdout`, `stderr`, `exit_code`.

This is the one `fs.*` step that starts a process: it runs `tar`, which detects the compression
itself, so `.tar`, `.tar.gz`, `.tar.bz2` and `.tar.xz` all work, and which refuses a member whose
name would escape `to:`. `strip_components: 1` is for the usual archive that wraps everything in
one directory named after the version.

Extraction passes `--no-same-owner`: the numeric ids in an archive built somewhere else mean
nothing here, and unpacking as root would otherwise scatter a build machine's numbering across the
host. Set the ownership you want with `fs.mkdir` on `to:` beforehand, or with the `owner:` on
whatever step puts the release in place.

#### owner and group

`fs.copy`, `fs.move`, `fs.mkdir` and `fs.template` each take `owner:` and `group:` beside `mode:`,
so landing a release as `app:app` does not need a `shell.run chown`.

```yaml
- uses: fs.copy
  from: ${trigger.path}
  to: ${vars.releases_root}/api/releases/${trigger.version}/api.jar
  mode: "0640"
  owner: app
  group: app
```

Names, not numbers, and applied after `mode:`. Changing a file's owner needs privilege the daemon
usually has to be given: see [privileges](OPERATING.md#privileges).

- **A name the host does not know fails the step.** The file exists but is not the file the config
  asked for, and a service that cannot read its own release is worse than a deploy that stopped.
  `butler validate` cannot catch it, since the config is normally validated somewhere other than
  the host it runs on, but a dry run **on the host** warns.
- **Where the filesystem has no notion of ownership it is skipped**, as `mode:` is. A config
  describes a Linux host whatever machine reads it.
- `fs.mkdir` applies them to the directory it names, not to any parents `parents: true` created.

### `systemd`

The verbs that mutate a unit put `sudo` in front by default, matching the sudoers allowlist in
[OPERATING.md](OPERATING.md#privileges). That is separate from `run_as:`, which says which user to
become rather than that root is required. `is-active` and `show`, which `wait_active`, `status` and
every preflight check use, are read-only and run as the daemon's own user.

#### `systemd.restart`, `systemd.start`, `systemd.reload`

| Parameter     | Type       | Default     |                                                     |
|---------------|------------|-------------|-----------------------------------------------------|
| `unit`        | text       |             | **required**, e.g. `api.service`                    |
| `wait_active` | duration   | do not wait | Poll `is-active` until the unit is active, or fail. |
| `sudo`        | true/false | `true`      | `false` for a user unit.                            |

**Outputs:** `active_state` (when waiting), `stdout`, `stderr`, `exit_code`.

`systemctl restart` returns once systemd has accepted the job, not once the service is up, so a
health check that follows it immediately may be testing the old process. `wait_active:` is the fix.

#### `systemd.stop`

| Parameter       | Type       | Default     |              |
|-----------------|------------|-------------|--------------|
| `unit`          | text       |             | **required** |
| `wait_inactive` | duration   | do not wait |              |
| `sudo`          | true/false | `true`      |              |

**Outputs:** `active_state` (when waiting), `stdout`, `stderr`, `exit_code`.

#### `systemd.wait_active`

Wait for a unit to reach a state, without changing anything itself.

| Parameter  | Type     | Default  |                                                                     |
|------------|----------|----------|---------------------------------------------------------------------|
| `unit`     | text     |          | **required**                                                        |
| `state`    | text     | `active` | The state to wait for.                                              |
| `wait_for` | duration | `30s`    | Reads `wait_for:`, since a record component cannot be named `wait`. |

**Outputs:** `active_state`.

#### `systemd.status`

Report a unit's state, sub-state and main PID. Fails if systemd has never heard of the unit.

| Parameter | Type | Default |              |
|-----------|------|---------|--------------|
| `unit`    | text |         | **required** |

**Outputs:** `load_state`, `active_state`, `sub_state`, `pid` (null when there is no process).

### `http`

Both steps report **Outputs:** `status`, `headers` (names lowercased), `body`, and `json` where the
body parses as JSON. A body that is not JSON leaves `json` null rather than failing the step.

#### `http.request`

Make one HTTP request and report the response. The request is given whatever the step's own
`timeout:` allows, so there is one timeout to set rather than two that can disagree.

| Parameter       | Type            | Default |                                            |
|-----------------|-----------------|---------|--------------------------------------------|
| `url`           | text            |         | **required**                               |
| `method`        | text            | `GET`   |                                            |
| `headers`       | mapping         | none    |                                            |
| `body`          | text            | none    |                                            |
| `expect_status` | list of numbers | any 2xx | One value or a list: `expect_status: 200`. |

#### `http.wait`

Poll a URL until `until:` holds, and fail when it runs out of time.

| Parameter  | Type        | Default |                                                                                    |
|------------|-------------|---------|------------------------------------------------------------------------------------|
| `url`      | text        |         | **required**                                                                       |
| `until`    | *condition* |         | **required.** Sees `status`, `headers`, `body` and `json` for the probe in flight. |
| `interval` | duration    | `2s`    |                                                                                    |
| `method`   | text        | `GET`   |                                                                                    |
| `headers`  | mapping     | none    |                                                                                    |

**Outputs:** the four above, plus `probes` and `elapsed`.

There is no timeout parameter: the step's reserved `timeout:` is the limit, and the step turns being
cut off into an account of how far it got - probes, elapsed, last status and body. A refused
connection is a probe that did not hold rather than a failure, because that is what a service being
restarted looks like. With no `timeout:` it would poll for as long as it takes, which `--dry-run`
warns about.

### `notify`

#### `notify.send`

Send a message through a channel declared under `notifiers:`, from the middle of a pipeline.
Announcing how a run ended is the job-level `notify:` policy's work.

| Parameter | Type | Default |                                                   |
|-----------|------|---------|---------------------------------------------------|
| `to`      | text |         | **required.** A name from the `notifiers:` block. |
| `message` | text | empty   |                                                   |

---

## Notifier reference

Declared under `notifiers:` and referenced by name. Parameters resolve per send, so a webhook can
come from `${secret.*}`.

#### `notify.slack`

Posts to a Slack incoming webhook.

| Parameter    | Type |              |                                                       |
|--------------|------|--------------|-------------------------------------------------------|
| `webhook`    | text | **required** | The URL is a secret: write `${secret.SLACK_WEBHOOK}`. |
| `channel`    | text |              | Quoted, so `#deploys` is not read as a comment.       |
| `username`   | text |              |                                                       |
| `icon_emoji` | text |              |                                                       |

#### `notify.discord`

Posts to a Discord webhook, which takes the message in `content`.

| Parameter  | Type |              | |
|------------|------|--------------|-|
| `webhook`  | text | **required** | |
| `username` | text |              | |

#### `notify.ntfy`

Posts to an ntfy topic, which takes the message as the body and everything else as headers.

| Parameter  | Type | Default           |                                     |
|------------|------|-------------------|-------------------------------------|
| `topic`    | text |                   | **required**                        |
| `server`   | text | `https://ntfy.sh` |                                     |
| `title`    | text |                   |                                     |
| `priority` | text |                   |                                     |
| `token`    | text |                   | Access token for a protected topic. |

#### `notify.webhook`

Posts the message as JSON to any URL, for a service with no notifier of its own.

| Parameter | Type    | Default |                                     |
|-----------|---------|---------|-------------------------------------|
| `url`     | text    |         | **required**                        |
| `field`   | text    | `text`  | The JSON field the message goes in. |
| `headers` | mapping | none    |                                     |

#### `notify.pushover`

Posts to the [Pushover](https://pushover.net) API, which takes the message and everything else as
form fields rather than JSON. There is one API endpoint, so unlike the other channels here there is
no `server:`/`url:` to point elsewhere.

| Parameter  | Type |              |                                             |
|------------|------|--------------|---------------------------------------------|
| `token`    | text | **required** | The application's API token.                |
| `user`     | text | **required** | The user or group key to notify.            |
| `title`    | text |              |                                             |
| `priority` | text |              | Pushover's `-2` to `2` priority, as text.   |
| `sound`    | text |              | One of Pushover's notification sound names. |

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

A hole that is the whole value keeps its type, so `keep: ${vars.keep}` stays a number rather than
becoming `"5"`.

**Namespaces**, and nothing else:

|                  |                                                                                                                                          |
|------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `vars.*`         | global `vars:` merged with job `vars:`, then any `control.set` step                                                                      |
| `trigger.*`      | facts from the event, including regex capture groups                                                                                     |
| `steps.<name>.*` | results of steps that declared `register:`                                                                                               |
| `state.*`        | persisted values, overlaid with what `discover:` observed                                                                                |
| `env.*`          | process environment                                                                                                                      |
| `secret.*`       | resolved secrets                                                                                                                         |
| `run.*`          | `id`, `job`, `trigger`, `started_at`, `dry_run`; in hooks and `notify:` also `status`, `duration`, `duration_ms`, `failed_step`, `error` |
| `butler.*`       | `version`, `host`                                                                                                                        |

An unknown *path* evaluates to `null`; an unknown *namespace* is a validation error, so
`${triger.version}` is caught at load time while `default(state.deployed_version, "0.0.0")` still
works on a first run.

### Operators

|                      |                                                                                              |
|----------------------|----------------------------------------------------------------------------------------------|
| `and`, `or`, `not`   | `and` binds tighter than `or`; parenthesise where it matters.                                |
| `==`, `!=`           | Accept null on either side. A number and its text compare equal, so `status == "200"` holds. |
| `<`, `<=`, `>`, `>=` | Ordering. Against null it is an error rather than a guess.                                   |
| `matches`            | Regex search over the right-hand side. Null is no match.                                     |
| `contains`           | Substring, list membership, or map key. Null is no match.                                    |

### Functions

|                                   |                                                                                          |
|-----------------------------------|------------------------------------------------------------------------------------------|
| `semver(s)`                       | A comparable version, so `1.10.0` never ranks below `1.9.0`. A leading `v` is tolerated. |
| `exists(path)`                    | Whether the path resolved to anything.                                                   |
| `default(a, b)`                   | `a` unless it is null.                                                                   |
| `len(x)`                          | Length of a string, list or map.                                                         |
| `int(x)`                          | A whole number, from text or from a duration's milliseconds.                             |
| `lower(s)`, `upper(s)`, `trim(s)` |                                                                                          |
| `basename(p)`, `dirname(p)`       | Path halves, without touching the filesystem.                                            |
| `match(s, re[, group])`           | The first match, or a capture group of it, or null.                                      |
| `file_exists(p)`                  | Whether the file is there, on this host, now.                                            |
| `now()`                           | The current instant.                                                                     |

```yaml
when: semver(trigger.version) > semver(default(state.deployed_version, "0.0.0"))
when: exists(steps.symlink.previous_target)
when: not (trigger.name matches '-rc\d+\.jar$')
until: status == 200 and json.version == ${trigger.version}
that: len(steps.releases.entries) > 0
extract:
  deployed_version: match(stdout, 'v?(\d+\.\d+\.\d+)', 1)
```

**A double-quoted string takes escapes; a single-quoted one is raw**, exactly as in YAML. That is
what makes a regex readable: `match(stdout, 'v?(\d+\.\d+\.\d+)', 1)`.

**Null is not silently ordered.** `==` and `!=` accept null on either side, and `matches` and
`contains` treat it as no-match. Ordering (`<`, `>`, `<=`, `>=`) against null is an error: there is
no defensible answer, and guessing one would let a first-run deploy decision turn on a value nobody
supplied. Use `default()` or `exists()` to say what should happen when the value is missing.

**Durations** are `\d+(ms|s|m|h|d)` everywhere: `timeout:`, `settle:`, `interval:`, `delay:`,
`wait_active:` and the duration literal in an expression all take the same form. A bare number is an
error, because `timeout: 30` must never silently mean 30 milliseconds.
