# Butler

[![Build](https://github.com/raharrison/butler/actions/workflows/build.yml/badge.svg)](https://github.com/raharrison/butler/actions/workflows/build.yml)
[![Java 25](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)](https://openjdk.org/)
![Single jar](https://img.shields.io/badge/deploy-single%20jar-blue)
![Runtime deps](https://img.shields.io/badge/dependencies-picocli%20%C2%B7%20Jackson-lightgrey)

A single-binary Java daemon that sits on a server, watches for events, and runs declarative
pipelines in response. No agents to install on the targets, no orchestration control plane,
no bash gluing steps together, just one jar, one YAML file and a systemd unit.

The motivating case is rolling deployments. CI drops a new artifact into a directory on the server;
Butler notices the new version, stages it, repoints a symlink, restarts the service, confirms the
process is live and serving the version it should be, and reports the outcome. The same machinery
handles anything that needs doing on a host in response to an event or a schedule. Nothing in the
core model is deployment-specific.

```yaml
jobs:
  api:
    on:
      - uses: file.appeared
        dir: /srv/artifacts/api
        match: 'api-(?<version>\d+\.\d+\.\d+)\.jar'
        settle: 10s
        order_by: semver(version)

    discover:
      - uses: http.request
        url: http://localhost:8080/health
        timeout: 3s
        extract:
          deployed_version: json.version

    when: semver(trigger.version) > semver(default(state.deployed_version, "0.0.0"))

    steps:
      - uses: fs.copy
        from: ${trigger.path}
        to: /srv/apps/api/releases/${trigger.version}/api.jar
      - uses: fs.symlink
        link: /srv/apps/api/current
        target: /srv/apps/api/releases/${trigger.version}
        register: symlink
      - uses: systemd.restart
        unit: api.service
        wait_active: 30s
      - uses: http.wait
        url: http://localhost:8080/health
        until: status == 200 and json.version == ${trigger.version}
        timeout: 90s

    on_failure:
      - uses: fs.symlink
        link: /srv/apps/api/current
        target: ${steps.symlink.previous_target}
        when: exists(steps.symlink.previous_target)

    persist:
      deployed_version: ${trigger.version}
```

No bash, no version comparison, no "is it up yet" polling loop. When the vocabulary runs out,
`shell.run` is one key away.

**What makes it more than a cron script:**

- **Every run can be rehearsed.** `--dry-run` prints a complete, fully resolved account of what
  would happen and changes nothing. `describe()` is a required method on the step interface, so a
  step that cannot explain itself cannot be written.
- **It observes the host rather than trusting its own memory.** A job's `discover:` block asks the
  running service what version it is before deciding whether to deploy. That is what makes a fresh
  install, a wiped state directory, a crash mid-run and a hand-rolled rollback all converge on the
  right answer instead of redeploying everything.
- **Config errors are caught before the daemon starts.** `butler validate` reports every problem in
  one pass with `file:line:column` and a did-you-mean suggestion, and the daemon refuses to start on
  a config it cannot run.
- **The vocabulary is open, the core is closed.** Steps, triggers and notifiers arrive by
  `ServiceLoader`; adding `docker.compose` means writing a record and a class, not touching the
  runtime.

---

## Contents

- [Install](#install) · [Getting started](#getting-started) · [The CLI](#the-cli)
- [Config in one page](#config-in-one-page)
- [What's available](#whats-available): [triggers](#triggers) · [steps](#steps) ·
  [notifiers](#notifiers) · [expressions](#expressions)
- [Recipes](#recipes)
- [Onboarding an existing host](#onboarding-an-existing-host) ·
  [Privileges](#privileges) · [Documentation](#documentation) · [Building](#building)

---

## Install

Take `butler.jar` and the launcher from the latest release, which the tag workflow builds with the
version baked into the manifest, or build it yourself:

```bash
./gradlew shadowJar          # build/libs/butler-<version>-all.jar
```

It needs a JRE 25 and nothing else.

```bash
sudo install -D -m0644 butler.jar     /usr/lib/butler/butler.jar
sudo install -D -m0755 butler         /usr/bin/butler
```

The full install layout, the systemd unit and the sudoers snippet are in
[docs/OPERATING.md](docs/OPERATING.md).

## Getting started

**1. Write a config.** The smallest valid one is a job with `on:` and `steps:`:

```yaml
# /etc/butler/butler.yaml
settings:
  state_dir: /var/lib/butler

jobs:
  hello:
    on:
      - uses: schedule.every
        interval: 1h
    steps:
      - name: Say something
        uses: control.log
        message: hello from ${butler.host}
```

**2. Check it.**

```bash
butler validate                 # exits 1 listing every problem, with file:line:col
butler check                    # the same, then prints the config as Butler understands it
```

**3. Rehearse it.** `butler trigger` runs one job once against the facts its own triggers would
have supplied, so `${trigger.path}` holds what it would hold in production:

```bash
butler trigger hello --dry-run              # print the resolved plan, change nothing
butler trigger hello --dry-run --set version=1.2.4
butler trigger hello                        # for real
```

Editing the config and reading the plan back is the authoring loop.

**4. Run it.**

```bash
butler --dry-run                # watch everything, report every firing, touch nothing
butler                          # the daemon
```

## The CLI

```
butler [--config <file>...] [--dry-run]  # no subcommand: run as the daemon
butler validate                          # exit 1 listing every error, for CI
butler check                             # validate, then print the effective config
butler trigger <job> [--set k=v]         # run one job once, or rehearse it with --dry-run
butler adopt [<job>]                     # discovery only: record state, execute nothing
butler runs [<job>] [--failed]           # what has run, newest first
butler show <id>                         # one recorded run in full
butler steps [<name>]                    # the registered step types and their parameters
butler generate-completion               # bash/zsh completion script
```

| Command                 | What it is for                                                                                     |
|-------------------------|----------------------------------------------------------------------------------------------------|
| `butler`                | The daemon. Starts a watcher per trigger and runs jobs as events arrive.                           |
| `butler --dry-run`      | The same, reporting every firing instead of acting on it. Safe against a live host.                |
| `butler validate`       | CI gate. Exits 1 on any error; warnings alone still exit 0.                                        |
| `butler check`          | Answers "is that key doing what I think", with defaults filled in.                                 |
| `butler trigger <job>`  | Run or rehearse one job now. `--set k=v` supplies or overrides a trigger fact.                     |
| `butler adopt [<job>]`  | Install-time seeding on a host that is already serving. See [below](#onboarding-an-existing-host). |
| `butler runs [<job>]`   | What has run and how it went, from the history under `state_dir`.                                  |
| `butler show <id>`      | One recorded run, rendered as the report it printed at the time.                                   |
| `butler steps [<name>]` | The vocabulary this build actually has, generated from the registry.                               |

Examples:

```bash
butler steps                                # every step type and its parameters
butler steps fs.symlink                     # just one
butler trigger api --dry-run | less         # read the plan for the newest artifact present
butler trigger api --set version=1.2.4      # run against a fact you supply
butler adopt                                # seed state for every job, run nothing
butler runs --failed --since 24h            # yesterday's failures, any job
butler show 20260809T031407-a1b2            # what that one did, step by step
butler validate -c ./butler.yaml            # any command takes --config
butler validate -c base.yaml -c api.yaml    # several files, read as one config
```

`--config` defaults to `/etc/butler/butler.yaml` and is on every command, so they all read the same
config the daemon will. Exit codes are `0` ok, `1` failure or validation errors, `2` bad usage.

**Repeat `--config` to spread one config over several files.** They are read in order and merged:
`jobs:`, `notifiers:`, `vars:` and `secrets: files:` accumulate and a name may only be defined
once, while `settings:` is policy and belongs in a single file. See
[Several files](docs/CONFIGURATION.md#several-files).

Logs go to stderr; stdout carries whatever you asked for, so `butler trigger api --dry-run | less`
shows the plan and nothing else.

## Config in one page

```yaml
version: 1

settings: # all optional, defaults shown
  state_dir: /var/lib/butler     # where state and run history are written
  log_format: json               # json | text, for the daemon
  max_concurrent_runs: 4         # global bound on runs in flight
  poll_interval: 5s              # default cadence for polling triggers
  shutdown_grace: 2m             # how long a drain lets in-flight runs finish
  default_job_timeout: 1h        # bound on a job that sets no timeout: of its own
  run_retention: { count: 200, age: 30d }  # per job; a job may override it
  plugins_dir: /var/lib/butler/plugins     # jars of third-party steps

secrets:
  from_env: true                 # ${secret.FOO} resolves from $FOO
  files: /etc/butler/secrets.yaml # a flat name: value mapping

vars: # readable everywhere as ${vars.name}
  releases_root: /srv/apps

notifiers: # named channels, referenced by name
  ops:
    uses: notify.slack
    webhook: ${secret.SLACK_WEBHOOK}
    channel: "#deploys"

jobs:
  api:
    description: Free text, shown by butler check
    on: [ ... ]           # required: one or more triggers
    vars: { ... }           # job-local, merged over the global ones
    env: { ... }           # applied to every process-backed step in the job
    discover: [ ... ]           # observe the host, populate state.* (runs before when:)
    when: <condition>       # run only if true
    concurrency: { group: api, mode: queue, queue_newest_only: true }
    timeout: 10m               # whole-run limit; exceeding it fails the run
    run_retention: { age: 90d }  # overrides settings.run_retention for this job
    steps: [ ... ]           # required: the pipeline
    on_failure: [ ... ]          # hooks
    on_success: [ ... ]
    always: [ ... ]
    persist: { ... }           # state keys written after a successful run
    notify: { to: ops, on: [ success, failure ], success: "...", failure: "..." }
```

Every step takes the same reserved keys, whatever its type:

```yaml
- name: Human label, used in logs and notifications
  uses: http.request               # required: the step type
  when: <condition>                # skip this step if false
  register: probe                  # expose the result as steps.probe.*
  timeout: 30s
  retry: { attempts: 3, delay: 5s, backoff: exponential, on: failure }
  continue_on_error: false
  env: { TOKEN: "${secret.API_TOKEN}" }
  working_dir: /srv/apps/api/current
  run_as: appuser
  # ...then the step's own parameters as sibling keys
```

Those names are reserved and can never be a step's own parameter, along with `extract:`, which is
valid only inside a `discover:` block.

`docs/CONFIGURATION.md` is the full reference for all of it.

---

## What's available

### Triggers

| Trigger                                                 | Fires when                                     | Key parameters                                                        |
|---------------------------------------------------------|------------------------------------------------|-----------------------------------------------------------------------|
| [`file.appeared`](docs/CONFIGURATION.md#fileappeared)   | a new file or directory settles in a directory | `dir` (required), `kind`, `match`, `settle`, `order_by`, `on_startup` |
| [`file.changed`](docs/CONFIGURATION.md#filechanged)     | one file's contents change                     | `path` (required), `settle`, `on_startup`                             |
| [`schedule.every`](docs/CONFIGURATION.md#scheduleevery) | a fixed interval elapses                       | `interval` (default `1h`)                                             |
| [`schedule.cron`](docs/CONFIGURATION.md#schedulecron)   | a 5-field cron expression comes round          | `expression` (required), `timezone`                                   |
| [`manual`](docs/CONFIGURATION.md#manual)                | `butler trigger` asks it to                    | none                                                                  |

A trigger's parameters are never templated: a watcher starts before any event exists, so there is
no run to resolve `${...}` against.

### Steps

`butler steps` prints this from the registry with every parameter, so it never falls behind the
build you are running. Full parameter tables, defaults and outputs are in
[docs/CONFIGURATION.md](docs/CONFIGURATION.md#step-reference).

| Step                  | What it does                                                                    |
|-----------------------|---------------------------------------------------------------------------------|
| `control.log`         | Write a message into the run log.                                               |
| `control.set`         | Set variables the rest of the run can read.                                     |
| `control.assert`      | Fail the run unless a condition holds.                                          |
| `control.sleep`       | Wait for a fixed duration.                                                      |
| `control.fail`        | Fail the run with a message.                                                    |
| `shell.run`           | Run a script through a shell. The escape hatch.                                 |
| `shell.exec`          | Run a program with explicit arguments, no shell.                                |
| `fs.copy`             | Copy a file, creating parent directories and setting its mode and owner.        |
| `fs.move`             | Move a file or directory.                                                       |
| `fs.symlink`          | Point a symlink at a target, atomically. Reports `previous_target`.             |
| `fs.readlink`         | Report what a symlink points at.                                                |
| `fs.read`             | Read a file's contents.                                                         |
| `fs.list`             | List a directory, ordered and filtered.                                         |
| `fs.exists`           | Report whether a path exists, and what it is.                                   |
| `fs.mkdir`            | Create a directory.                                                             |
| `fs.template`         | Write a file, filling in `${...}` from the run.                                 |
| `fs.prune`            | Delete all but the newest entries of a directory. Never deletes what is in use. |
| `fs.delete`           | Delete one named path. A non-empty directory needs `recursive: true`.           |
| `fs.unpack`           | Unpack a tar archive into a directory.                                          |
| `systemd.restart`     | Restart a unit, waiting for it to become active.                                |
| `systemd.start`       | Start a unit, waiting for it to become active.                                  |
| `systemd.stop`        | Stop a unit, waiting for it to become inactive.                                 |
| `systemd.reload`      | Ask a unit to reload its configuration.                                         |
| `systemd.wait_active` | Wait for a unit to reach a state, changing nothing.                             |
| `systemd.status`      | Report a unit's state, sub-state and main PID.                                  |
| `http.request`        | Make one HTTP request and report the response.                                  |
| `http.download`       | Fetch a file, checking the sha256 before it lands.                              |
| `http.wait`           | Poll a URL until a condition holds.                                             |
| `notify.send`         | Send a message through a declared notifier.                                     |

### Notifiers

| `uses`            | Parameters                                                                             |
|-------------------|----------------------------------------------------------------------------------------|
| `notify.slack`    | `webhook` (required), `channel`, `username`, `icon_emoji`                              |
| `notify.discord`  | `webhook` (required), `username`                                                       |
| `notify.ntfy`     | `topic` (required), `server` (default `https://ntfy.sh`), `title`, `priority`, `token` |
| `notify.webhook`  | `url` (required), `field` (default `text`), `headers`                                  |
| `notify.pushover` | `token` (required), `user` (required), `title`, `priority`, `sound`                    |

### Expressions

Conditions (`when:`, `until:`, `that:`) take a bare expression. Every other value is text with
`${expr}` holes.

| Namespace        | Holds                                                                                                                      |
|------------------|----------------------------------------------------------------------------------------------------------------------------|
| `vars.*`         | global `vars:` merged with job `vars:`, then any `control.set` step                                                        |
| `trigger.*`      | facts from the event, including regex capture groups                                                                       |
| `steps.<name>.*` | results of steps that declared `register:`                                                                                 |
| `state.*`        | persisted values, overlaid with what `discover:` observed                                                                  |
| `env.*`          | process environment                                                                                                        |
| `secret.*`       | resolved secrets                                                                                                           |
| `run.*`          | `id`, `job`, `trigger`, `started_at`, `dry_run`; in hooks also `status`, `duration`, `duration_ms`, `failed_step`, `error` |
| `butler.*`       | `version`, `host`                                                                                                          |

Operators: `and`, `or`, `not`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `matches`, `contains`.

Functions: `semver`, `exists`, `default`, `len`, `int`, `lower`, `upper`, `trim`, `basename`,
`dirname`, `match`, `file_exists`, `now`.

```yaml
when: semver(trigger.version) > semver(default(state.deployed_version, "0.0.0"))
when: exists(steps.symlink.previous_target)
when: steps.probe.ok and steps.probe.status == 200
until: status == 200 and json.version == ${trigger.version}
that: trim(steps.version.stdout) == trigger.version
message: staged at ${vars.releases_root}/${trigger.version}
```

An unknown *path* is `null`; an unknown *namespace* is a validation error, so `${triger.version}`
is caught at load time. Ordering against null is an error rather than a guess: use `default()` or
`exists()`.

---

## Recipes

### Deploy a jar when CI drops it

The [canonical example](docs/DESIGN.md#32-canonical-example), complete and annotated.
`src/test/resources/configs/canonical.yaml` is the same config, and is validated on every build.

### Restart a service when its config file changes

```yaml
jobs:
  nginx:
    on:
      - uses: file.changed
        path: /etc/nginx/nginx.conf
        settle: 5s
    steps:
      - name: Check the config before loading it
        uses: shell.exec
        argv: [ /usr/sbin/nginx, -t ]
      - uses: systemd.reload
        unit: nginx.service
    notify:
      to: ops
      on: [ failure ]
      failure: "nginx config is bad, not reloaded: ${run.error}"
```

### A nightly job

```yaml
jobs:
  backup:
    on:
      - uses: schedule.cron
        expression: 0 3 * * *
        timezone: Europe/London
    timeout: 1h
    steps:
      - uses: shell.run
        script: /usr/local/bin/backup.sh
        timeout: 55m
        register: backup
      - name: A zero exit is not the same as a finished backup
        uses: control.assert
        that: steps.backup.stdout contains "backup complete"
        message: the script exited 0 without finishing
```

A non-zero exit already fails `shell.run`, so the assertion is there for the case that does not.

### Ask a host what it is running, without an HTTP endpoint

Any step can be a `discover:` step. In rough order of preference:

```yaml
discover:
  # The symlink a deploy repoints, which needs nothing from the app.
  - uses: fs.readlink
    path: /srv/apps/api/current
    extract:
      deployed_version: basename(value)

  # A version file.
  - uses: fs.read
    path: /srv/apps/api/VERSION
    when: not exists(state.deployed_version)
    extract:
      deployed_version: trim(value)

  # Or ask the binary.
  - uses: shell.run
    script: /opt/myapp/bin/myapp --version
    timeout: 5s
    when: not exists(state.deployed_version)
    extract:
      deployed_version: match(stdout, 'v?(\d+\.\d+\.\d+)', 1)
```

A discovery step that fails contributes nothing and the persisted value stands, so the fallbacks
chain safely.

### Keep the last five releases

```yaml
- name: Prune old releases
  uses: fs.prune
  dir: /srv/apps/api/releases
  keep: 5
  order_by: semver
  continue_on_error: true
```

It never deletes what a symlink beside the directory points at, whatever `keep:` works out to.

### Retry something flaky

```yaml
- uses: http.request
  url: https://registry.example/api/publish
  method: POST
  body: ${vars.payload}
  expect_status: [ 200, 201 ]
  retry: { attempts: 3, delay: 5s, backoff: exponential }
  timeout: 30s
```

### Run one step as another user

```yaml
- uses: shell.run
  run_as: appuser
  working_dir: /srv/apps/api/current
  script: ./bin/migrate --to ${trigger.version}
  timeout: 2m
```

`run_as:` is `sudo -u appuser` and needs its own sudoers grant.

### Escape hatch

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

A shell variable is written `$${HOME}`, since `${...}` belongs to the config.

---

## Onboarding an existing host

This is the least obvious part of operating Butler, and worth following in order.

|   |                                                                                                                                                                                                           |
|---|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Install the jar and the launcher, and write `/etc/butler/butler.yaml`.                                                                                                                                    |
| 2 | `butler validate` until it is clean.                                                                                                                                                                      |
| 3 | `butler --dry-run` and **leave it for a day.** It starts every watcher, runs every `discover:` block for real and prints what each firing would do, without changing anything. Read the log.              |
| 4 | `butler adopt` once. It runs each job's `discover:` block, records what the host is actually serving as state, and records the dedupe key of whatever artifact is already sitting in the watch directory. |
| 5 | `systemctl enable --now butler`.                                                                                                                                                                          |

Step 4 is what stops a fresh install from redeploying an application that is already running the
right version. Step 3 is what tells you whether step 4 will do what you expect.

## Privileges

**`shell.run` executes with the daemon's privileges, and so does anything inside a `discover:`
block.** A `discover:` step runs even under `--dry-run` - it has to, or a dry run would report a
decision made against stale memory.

That makes the config file **as trusted as the daemon itself**. Keep it at `0640 root:butler`, put
secrets in the environment or a separate secrets file rather than inline, and treat write access to
it as equivalent to running commands as the `butler` user.

Butler itself runs unprivileged. Steps that need root go through a narrow `NOPASSWD` sudoers
allowlist, one line per unit and verb - never `systemctl *`. Only the verbs that change a unit need
one; `is-active` and `show` are read-only and run as the daemon's own user. `butler --dry-run`
warns when a `systemd.*` step has no matching rule, which turns a 3am failure into a dry-run
warning. There is [a sample snippet](packaging/butler.sudoers) to copy.

Secrets are **not redacted** from logs, captured process output or run records in v1. The guidance
is not to echo them; see [DESIGN.md §11](docs/DESIGN.md).

## Documentation

|                                                |                                                                                          |
|------------------------------------------------|------------------------------------------------------------------------------------------|
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | The config reference: every key, every trigger and step with its parameters and outputs. |
| [docs/OPERATING.md](docs/OPERATING.md)         | systemd, install layout, sudoers, logging, the state directory, plugins.                 |
| [docs/DESIGN.md](docs/DESIGN.md)               | Why it is shaped this way. Authoritative for the model, and what is deliberately absent. |

`butler steps` is generated from the registry, so it documents the vocabulary this build actually
has rather than the one the docs were written against.

## Building

```bash
./gradlew build                 # compile and test
./gradlew shadowJar             # build/libs/butler-1.0-SNAPSHOT-all.jar
```

Java 25, Gradle 9. Four dependencies at runtime: picocli, Jackson, SLF4J and Logback. Tests add
JUnit and ArchUnit, and `ArchitectureTest` pins the package layering that keeps the runtime
ignorant of what any individual step does.
