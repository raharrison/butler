# Operating Butler

Running the daemon on a server: install layout, the unit file, privileges, logs, and what it writes
where.

---

## Install layout

| Path                         | Owner           | Mode   |                                                            |
|------------------------------|-----------------|--------|------------------------------------------------------------|
| `/usr/lib/butler/butler.jar` | `root:root`     | `0644` | The shaded jar. The only artifact that matters.            |
| `/usr/bin/butler`            | `root:root`     | `0755` | Launcher script, `exec java -jar`.                         |
| `/etc/butler/butler.yaml`    | `root:butler`   | `0640` | The config. Readable by the daemon, writable only by root. |
| `/etc/butler/secrets.yaml`   | `root:butler`   | `0640` | Optional, if `secrets: file:` names it.                    |
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

The launcher honours `BUTLER_JAR` and `BUTLER_JAVA_OPTS` if you need to point it somewhere else or
give the JVM a heap ceiling.

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

Two of those need explaining:

- **`TimeoutStopSec` must exceed `settings.shutdown_grace`.** On `SIGTERM` Butler stops its
  watchers, lets in-flight runs finish for the grace period (2 minutes by default), then cancels
  what is left. If systemd's stop timeout is shorter than the grace period, it `SIGKILL`s the daemon
  part-way through the drain, which is precisely the half-finished deploy the grace period exists to
  avoid. 150 seconds pairs with the default; raise both together if your jobs are longer, and
  remember a job's own `timeout:` already bounds it.
- **`NoNewPrivileges=false`** is required for `sudo`, which every mutating `systemd.*` step uses by
  default. Set it `true` if your config never escalates, and drop the sudoers file with it.

`ProtectSystem=full` makes `/usr` and `/etc` read-only for the daemon and everything it forks.
Anything a job writes - release directories, symlinks - needs to be under `ReadWritePaths`.

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
                            /usr/bin/systemctl status  api.service
```

Do **not** grant `/usr/bin/systemctl *`. That is a root shell with extra steps: `systemctl link` and
`systemctl edit` will run whatever you point them at. [`packaging/butler.sudoers`](../packaging/butler.sudoers)
is a copyable starting point.

`butler --dry-run` warns when a `systemd.*` step has no `NOPASSWD` rule matching the command it
would run.

`run_as: appuser` on a step is implemented as `sudo -u appuser`, and needs its own grant.

### What the config file is worth

**`shell.run` executes with the daemon's privileges, and so does anything in a `discover:` block** -
including under `--dry-run`, where discovery deliberately runs for real. So the config file is as
trusted as the daemon: `0640 root:butler`, and write access to it is equivalent to running commands
as `butler`.

Secrets come from the environment or a separate secrets file rather than inline. They are **not
redacted** from logs or captured process output in v1; the guidance is not to echo them.

## Logging

Logs go to **stderr**. Stdout carries what a command was asked for - a plan, a run report, the
effective config - so `butler trigger api --dry-run | less` shows the plan and nothing else.

`settings.log_format` picks the daemon's format:

- **`json`** (the default) uses Logback's built-in `JsonEncoder`. Every line carries `run_id`, `job`
  and `step` in its MDC, which is what makes a run greppable end to end:

  ```json
  {"timestamp":1786556450272,"level":"INFO","threadName":"run-api",
   "loggerName":"net.ryanh.butler.runtime.JobRunner",
   "mdc":{"job":"api","step":"Wait for health","run_id":"20260812T174050-89ac"},
   "formattedMessage":"ok in 1s","throwable":null}
  ```

  ```bash
  journalctl -u butler -o cat | jq -r 'select(.mdc.job == "api") | .formattedMessage'
  journalctl -u butler -o cat | jq -r 'select(.mdc.run_id == "20260812T174050-89ac")'
  ```

- **`text`** is a readable pattern, and is what interactive commands always use whatever the setting
  says. `butler validate` in a terminal should be readable; the daemon under systemd should be
  greppable.

## The state directory

```
/var/lib/butler/
  jobs/api.json                    { dedupe_key, last_run, state: { ... } }
  runs/2026-08-09/<run-id>.json    one full record per run
  runs/index.jsonl                 append-only, one line per run
```

Plain JSON, written temp-then-atomic-move. No database: the write volume is a handful of records a
day, and being able to `grep` and `jq` the state is worth more than anything an embedded store would
add.

**`jobs/<job>.json`** is what Butler remembers between runs: the last dedupe key it processed, and
the values `persist:` and `discover:` produced. Persisted values sit under their own `state` key, so
a job may `persist:` a value called `dedupe_key` without overwriting the bookkeeping.

State is a **cache of host reality, not the truth**. Deleting the directory is safe: discovery
re-derives what matters on the next event, which is exactly why an unreadable state file is a log
line rather than a refusal to start.

**`runs/`** is the audit trail. Each record holds the whole run - what discovery observed, the
decision with both sides shown, every step with its status, duration and attempts, what was
persisted and what was notified - so "what happened at 3am" is answerable without the logs:

```bash
jq -r 'select(.status == "failed") | "\(.started_at) \(.job) \(.failed_step)"' \
   /var/lib/butler/runs/index.jsonl | tail -20

jq . /var/lib/butler/runs/2026-08-09/20260809T031407-a1b2.json
```

Retention is `settings.run_retention`, by count and age together, enforced after each run on a
virtual thread. A `CANCELLED` run records nothing at all, because the work was withdrawn rather than
done.

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
new jar. `butler steps` lists what the jar itself ships; a plugin's steps show up in
`butler validate` and `butler check`.

## Troubleshooting

|                                                           |                                                                                                                                                                                                                          |
|-----------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **The daemon exits immediately with "refusing to start"** | The config has errors, listed above the message with `file:line:col`. A daemon that starts on a broken config and silently does nothing is worse than one that refuses.                                                  |
| **A job never fires**                                     | `butler --dry-run` prints every firing. Check `settle:` against how long the upload takes, and `match:` against the real filename. A trigger that would not start is logged at startup and the other jobs keep watching. |
| **A job fires but skips**                                 | Its `when:` is false. The plan and the run report both show the condition with its operands replaced by the values they evaluated to, so `semver("1.2.4") > semver("1.2.3")` can be checked rather than taken on trust.  |
| **A fresh install redeployed everything**                 | `butler adopt` was not run at install time. It records what the host is serving *and* the dedupe key of whatever artifact is already present.                                                                            |
| **A `systemd.*` step fails with a password prompt**       | No matching `NOPASSWD` rule. `butler --dry-run` warns about this before it happens.                                                                                                                                      |
| **Two artifacts landed and only the newer deployed**      | Working as intended: `queue_newest_only` collapses the queue so the host converges on the newest version instead of deploying twice.                                                                                     |
