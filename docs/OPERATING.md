# Operating Butler

Running the daemon on a server: install, the unit file, privileges, logs, what it writes where, and
what to do when something is wrong.

The [README](../README.md) is the guide to writing a config;
[CONFIGURATION.md](CONFIGURATION.md) is the reference for every key.

**Contents:** [Install](#install) · [The systemd unit](#the-systemd-unit) ·
[Privileges](#privileges) · [Logging](#logging) · [The state directory](#the-state-directory) ·
[Monitoring](#monitoring) · [Upgrading](#upgrading) · [Tuning](#tuning) ·
[Plugins](#plugins) · [Troubleshooting](#troubleshooting)

---

## Install

| Path                         | Owner           | Mode   |                                                            |
|------------------------------|-----------------|--------|------------------------------------------------------------|
| `/usr/lib/butler/butler.jar` | `root:root`     | `0644` | The shaded jar. The only artifact that matters.            |
| `/usr/bin/butler`            | `root:root`     | `0755` | Launcher script, `exec java -jar`.                         |
| `/etc/butler/butler.yaml`    | `root:butler`   | `0640` | The config. Readable by the daemon, writable only by root. |
| `/etc/butler/secrets.yaml`   | `root:butler`   | `0640` | Optional, if `secrets: files:` names it.                   |
| `/etc/sudoers.d/butler`      | `root:root`     | `0440` | The allowlist, if any step needs root.                     |
| `/var/lib/butler`            | `butler:butler` | `0750` | State directory: per-job state and run history.            |

```bash
sudo useradd --system --home-dir /var/lib/butler --shell /usr/sbin/nologin butler

sudo install -D -m0644 butler.jar /usr/lib/butler/butler.jar
sudo install -D -m0755 packaging/butler /usr/bin/butler
sudo install -d -m0750 -o butler -g butler /var/lib/butler

sudo install -D -m0640 -o root -g butler butler.yaml /etc/butler/butler.yaml
sudo install -D -m0644 packaging/butler.service /etc/systemd/system/butler.service
```

Then, before enabling it, work through the onboarding sequence in the
[README](../README.md#onboarding-an-existing-host): validate, watch in `--dry-run` for a day, then
`butler adopt` once. On a host that is already serving, `adopt` is what stops the first event from
redeploying an application that is already running the right version.

The launcher honours `BUTLER_JAR` and `BUTLER_JAVA_OPTS` if you need to point it somewhere else or
give the JVM a heap ceiling.

Butler needs a JRE 25 and nothing else. It writes only its state directory.

## The systemd unit

[`packaging/butler.service`](../packaging/butler.service), verbatim:

```ini
[Service]
Type=simple
User=butler
Group=butler
ExecStart=/usr/bin/butler --config /etc/butler/butler.yaml
Restart=on-failure
RestartSec=5s

KillSignal=SIGTERM
TimeoutStopSec=150

NoNewPrivileges=false
ProtectSystem=full
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/var/lib/butler
StateDirectory=butler
```

Three of those need explaining:

- **`TimeoutStopSec` must exceed `settings.shutdown_grace`.** On `SIGTERM` Butler stops its
  watchers, lets in-flight runs finish for the grace period (2 minutes by default), then cancels
  what is left. If systemd's stop timeout is shorter than the grace period, it `SIGKILL`s the daemon
  part-way through the drain, which is precisely the half-finished deploy the grace period exists to
  avoid. 150 seconds pairs with the default; raise both together if your jobs are longer, and
  remember a job's own `timeout:` already bounds it.
- **`NoNewPrivileges=false`** is required for `sudo`, which every mutating `systemd.*` step uses by
  default. Set it `true` if your config never escalates, and drop the sudoers file with it.
- **`Restart=on-failure`** restarts the daemon if it dies, but not if it exits cleanly. A config
  with errors exits `1`, which *is* a failure, so systemd will retry it every 5 seconds until the
  config is fixed. The errors are in the journal each time.

`ProtectSystem=full` makes `/usr` and `/etc` read-only for the daemon and everything it forks.
Anything a job writes - release directories, symlinks - needs to be under `ReadWritePaths`.

`ExecStart` may repeat `--config` to read several files as one config
([Several files](CONFIGURATION.md#several-files)). Each must be readable by the `butler` user, and
`butler validate` needs the same list, or CI is judging a different config.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now butler
journalctl -u butler -f
```

**Config changes require a restart.** There is no `SIGHUP` reload: a restart is a few seconds of not
watching, and the first event after it is judged against observed reality rather than against
whatever the old config believed.

## Privileges

Butler runs as an unprivileged `butler` user. Steps that need root go through a narrow sudoers
allowlist, one line per unit and verb:

```
butler ALL=(root) NOPASSWD: /usr/bin/systemctl restart api.service, \
                            /usr/bin/systemctl reload  api.service
```

Only the verbs that change a unit need a grant. `systemctl is-active` and `systemctl show` are
read-only and Butler runs them as itself, so `systemd.wait_active`, `systemd.status` and every
preflight check work without one.

To work out what a config needs: every `systemd.restart`, `start`, `stop` and `reload` step needs a
line for its own unit and verb, unless it sets `sudo: false`. Nothing else does, except a step with
`run_as:`.

Do **not** grant `/usr/bin/systemctl *`. That is a root shell with extra steps: `systemctl link` and
`systemctl edit` will run whatever you point them at. [`packaging/butler.sudoers`](../packaging/butler.sudoers)
is a copyable starting point.

`butler --dry-run` warns when a `systemd.*` step has no `NOPASSWD` rule matching the command it
would run, so a missing entry is a dry-run warning rather than a 3am password prompt:

```
  1 warning
    step 3      no NOPASSWD sudoers rule matches `systemctl restart api.service`
```

`run_as: appuser` on a step is implemented as `sudo -u appuser`, and needs its own grant:

```
butler ALL=(appuser) NOPASSWD: ALL
```

### What the config file is worth

**`shell.run` executes with the daemon's privileges, and so does anything in a `discover:` block** -
including under `--dry-run`, where discovery deliberately runs for real. So the config file is as
trusted as the daemon: `0640 root:butler`, and write access to it is equivalent to running commands
as `butler`.

Secrets come from the environment or a separate secrets file rather than inline. They are **not
redacted** in v1 from logs, from captured process output, or from the run records under
`state_dir`; the guidance is not to echo them. A step that fails carries the tail of its output into
its run record, so a script that prints a secret writes it to disk.

## Logging

Logs go to **stderr**. Stdout carries what a command was asked for - a plan, a run report, the
effective config - so `butler trigger api --dry-run | less` shows the plan and nothing else.

Every line of a run carries `run_id`, `job` and `step`, which is what makes a run greppable end to
end. `settings.log_format` picks the daemon's format:

- **`json`** (the default) uses Logback's built-in `JsonEncoder`:

  ```json
  {"timestamp":1786556450272,"level":"INFO","threadName":"run-api",
   "loggerName":"net.ryanh.butler.runtime.JobRunner",
   "mdc":{"job":"api","step":"Wait for health","run_id":"20260812T174050-89ac"},
   "formattedMessage":"ok in 1s","throwable":null}
  ```

  ```bash
  journalctl -u butler -o cat | jq -r 'select(.mdc.job == "api") | .formattedMessage'
  journalctl -u butler -o cat | jq -r 'select(.mdc.run_id == "20260812T174050-89ac")'
  journalctl -u butler -o cat | jq -r 'select(.level == "ERROR")'
  ```

- **`text`** is a readable pattern, and is what interactive commands always use whatever the setting
  says. `butler validate` in a terminal should be readable; the daemon under systemd should be
  greppable.

  ```
  17:40:50.272 INFO  20260812T174050-89ac api [Wait for health] ok in 1s
  ```

Butler logs at `INFO`. A run produces a line per step plus one for the outcome, so a busy host is a
few hundred lines a day rather than a stream.

## The state directory

```
/var/lib/butler/
  jobs/api.json                          { dedupe_key, last_run, state: { ... } }
  runs/2026-08-09/<job>-<run-id>.json    one full record per run
  runs/index.jsonl                       append-only, one line per run
```

Plain JSON, written to a temporary file and moved into place, so a crash mid-write leaves the
previous document rather than half of the new one. No database: the write volume is a handful of
records a day, and being able to `grep` and `jq` the state is worth more than anything an embedded
store would add.

### `jobs/<job>.json`

What Butler remembers between runs: the last dedupe key it processed, when it last ran, and the
values `persist:` and `discover:` produced.

```json
{
  "dedupe_key" : null,
  "last_run" : "2026-08-12T23:37:59.540467500Z",
  "state" : {
    "deployed_version" : "1.2.4"
  }
}
```

Persisted values sit under their own `state` key, so a job may `persist:` a value called
`dedupe_key` without overwriting the bookkeeping. They are JSON scalars, written exactly as the run
report showed them: a value with no JSON form of its own, such as a `semver()` or a duration, is
stored as its text, so `30s` reads back as `30s` rather than in some other syntax.

State is a **cache of host reality, not the truth**. Deleting the directory is safe: discovery
re-derives what matters on the next event, which is exactly why an unreadable state file is a log
line rather than a refusal to start. On a host whose jobs have no `discover:` block, deleting it
means the next event looks like new work.

### `runs/`

The audit trail. Each record holds the whole run, so "what happened at 3am" is answerable
without the logs:

```bash
butler runs                       # every job, newest first
butler runs api --failed          # just this job's failures
butler runs --since 24h --last 5  # the last five of the past day
butler show 20260812T233759-e744  # one run in full, as it reported itself at the time
```

`butler runs` reads `index.jsonl` and `butler show` reads the one record, so both answer from
the state directory alone with the daemon stopped. `butler show` renders through the same code
the run printed with, so a record and the report `butler trigger` showed at the time are the
same text.

The records themselves are plain JSON, for anything those two do not answer:

```json
{
  "id" : "20260812T233759-e744",
  "job" : "api",
  "trigger" : "manual",
  "status" : "success",
  "started_at" : "2026-08-12T23:37:59.512934800Z",
  "duration" : "27ms",
  "duration_ms" : 27,
  "failed_step" : null,
  "message" : null,
  "facts" : { "version" : "1.2.4" },
  "discover" : [ {
    "label" : "Ask what is deployed",
    "uses" : "control.set",
    "observed" : [ "state.deployed_version = \"1.2.3\"" ],
    "skipped" : null,
    "error" : null
  } ],
  "when" : {
    "source" : "semver(trigger.version) > semver(default(state.deployed_version, \"0.0.0\"))",
    "explained" : "semver(\"1.2.4\") > semver(\"1.2.3\")",
    "result" : true,
    "error" : null
  },
  "steps" : [ {
    "section" : "step",
    "label" : "Stage the release",
    "uses" : "control.log",
    "status" : "ok",
    "duration" : "1ms",
    "attempts" : 1,
    "message" : null
  } ],
  "persisted" : { "deployed_version" : "1.2.4" },
  "notified" : null
}
```

| Field       |                                                                                                               |
|-------------|---------------------------------------------------------------------------------------------------------------|
| `status`    | `success`, `failed` or `skipped`. A `cancelled` run records nothing at all.                                   |
| `facts`     | The `trigger.*` namespace the run saw.                                                                        |
| `discover`  | What each observation step reported, and what it taught `state.*`.                                            |
| `when`      | The decision with **both sides resolved**, so it can be checked rather than taken on trust.                   |
| `steps`     | Every step, with its `section` (`step`, `on_failure`, `on_success`, `always`), status, duration and attempts. |
| `persisted` | What was written to `state.*`. Empty unless the run succeeded.                                                |
| `notified`  | The channel and the rendered message, or null.                                                                |

`runs/index.jsonl` carries the same head fields as each record, one line per run, so the two cannot
disagree:

```json
{
  "id": "20260812T233759-e744",
  "job": "api",
  "trigger": "manual",
  "status": "success",
  "started_at": "...",
  "duration": "27ms",
  "duration_ms": 27,
  "failed_step": null,
  "message": null
}
```

Retention is by count and age together, enforced after each run and **per job**: a job's records
are counted against its own budget, never another's. That budget is `settings.run_retention`, or
the job's own `run_retention:`. Records outside either bound are deleted and their index lines with
them, at the cost of a directory listing per run.

## Monitoring

The two things worth alerting on are a failed run and a daemon that is not running.

```bash
butler runs --failed --since 24h     # anything that failed yesterday
butler show <id>                     # what that one did, step by step
```

For a question those do not shape, the index is one JSON object per line:

```bash
# The last run of each job, and how it went.
jq -rs 'group_by(.job)[] | max_by(.started_at) | "\(.job) \(.status) \(.started_at)"' \
   /var/lib/butler/runs/index.jsonl

# Runs that took longer than a minute.
jq -r 'select(.duration_ms > 60000) | "\(.job) \(.duration) \(.id)"' \
   /var/lib/butler/runs/index.jsonl
```

A job's own `notify:` policy is the first line: it fires on the outcome, per job, with the failing
step named. `systemctl is-active butler` covers the daemon. There is no health endpoint in v1; the
admin HTTP server is the first thing in line after it ([DESIGN.md §11](DESIGN.md)).

A job that never fires produces no records at all, which no query over `runs/` will show you. If
that matters for a job, give it a `schedule.every` heartbeat or watch `last_run` in
`jobs/<job>.json`. A heartbeat costs no other job its history: retention is per job.

## Upgrading

```bash
butler validate -c /etc/butler/butler.yaml     # with the NEW jar, before installing it
sudo install -D -m0644 butler.jar /usr/lib/butler/butler.jar
sudo systemctl restart butler
```

Validate with the new jar first: the vocabulary is what the build ships, so a step or parameter
that changed is caught before the daemon restarts on a config it cannot run. `butler steps` prints
what the new jar actually has.

The restart drains: in-flight runs finish within `shutdown_grace`. Nothing else needs doing - the
state directory format is read defensively, and a state file this build cannot parse degrades to
empty rather than refusing to start.

## Tuning

| Setting                | When to change it                                                                                                                                      |
|------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `poll_interval`        | The cadence of every polling trigger. `5s` on one directory costs nothing; raise it for a network filesystem.                                          |
| `settle` (per trigger) | Raise it if artifacts arrive slowly. Too low deploys a half-uploaded file, which settle exists to prevent.                                             |
| `max_concurrent_runs`  | Total runs in flight across all jobs. Raise it only if separate jobs genuinely need to overlap; within a job the concurrency group already serialises. |
| `shutdown_grace`       | Raise it, and `TimeoutStopSec` with it, if a deploy legitimately takes longer than 2 minutes.                                                          |
| `run_retention`        | Count and age together, per job. Records are small; the default keeps each job 200 or 30 days.                                                         |
| `BUTLER_JAVA_OPTS`     | A heap ceiling on a small VPS: `-Xmx128m` is ample. Butler holds one run's captured output at a time, bounded at 256KB per stream.                     |

Threads are not a knob. One virtual thread per watcher and per run, so a hundred jobs cost a
hundred parked threads and no pool to size.

## Shell completion

```bash
butler generate-completion | sudo tee /etc/bash_completion.d/butler > /dev/null
```

The script is generated from the real command tree, so it cannot fall behind a new subcommand.

## Plugins

`settings.plugins_dir` names a directory of jars. Every jar in it is loaded into one child
classloader before the registries are built, so a third-party `StepType`, `TriggerType` or
`Notifier` registered in its own `META-INF/services` joins the vocabulary and is validated like any
other.

```yaml
settings:
  plugins_dir: /var/lib/butler/plugins
```

A named-but-absent directory is not an error, for the same reason a named-but-absent secrets file is
not: a config is routinely validated somewhere other than the host it runs on. Restart to pick up a
new jar. A plugin's steps show up in `butler validate` and `butler check`; `butler steps` lists what
the jar itself ships, since it reads no config and so does not know where the plugins are.

## Troubleshooting

|                                                           |                                                                                                                                                                                                                                                                                         |
|-----------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **The daemon exits immediately with "refusing to start"** | The config has errors, listed above the message with `file:line:col`. A daemon that starts on a broken config and silently does nothing is worse than one that refuses. Under systemd it will retry every `RestartSec` and log them each time.                                          |
| **A job never fires**                                     | `butler --dry-run` prints every firing. Check `settle:` against how long the upload takes, and `match:` against the real filename - it is matched against the whole name. A trigger that would not start at all is logged as an error at startup and the other jobs keep watching.      |
| **A job fires but skips**                                 | Its `when:` is false, or the event was already processed. The plan and the run report both show the condition with its operands replaced by the values they evaluated to, so `semver("1.2.4") > semver("1.2.3")` can be checked rather than taken on trust.                             |
| **A job fires every poll**                                | Its trigger has no dedupe key, or the file keeps changing. `file.appeared` keys on path, size and mtime, so a file rewritten with the same contents but a new mtime is new work; `file.changed` keys on the content hash and does not have that problem.                                |
| **A fresh install redeployed everything**                 | `butler adopt` was not run at install time. It records what the host is serving *and* the dedupe key of whatever artifact is already present.                                                                                                                                           |
| **A `systemd.*` step fails with a password prompt**       | No matching `NOPASSWD` rule. `butler --dry-run` warns about this before it happens.                                                                                                                                                                                                     |
| **Two artifacts landed and only the newer deployed**      | Working as intended: `queue_newest_only` collapses the queue so the host converges on the newest version instead of deploying twice.                                                                                                                                                    |
| **A step times out but the process keeps running**        | It does not: the timeout kills the whole process tree. If the step reports a timeout with no output, the process wrote nothing before it was killed.                                                                                                                                    |
| **A step logs that something still holds its stdout**     | Its script started something in the background, which inherited the pipes and is still holding them. The step reports the output that had arrived rather than waiting for that service to stop. Redirect the background process's output if you want the step's capture to be complete. |
| **`${...}` appears literally in the output**              | Trigger parameters are never templated, and `$${` is an escape for a literal `${`. `butler trigger --dry-run` resolves everything, so anything left there is either of those two.                                                                                                       |
| **A run record is missing**                               | A `cancelled` run records nothing - it was displaced by a newer event or cut short by shutdown, and no work was done. Retention may also have dropped it.                                                                                                                               |
