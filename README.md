# Butler

A single-binary Java daemon that sits on a server, watches for events, and runs declarative
pipelines in response.

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
[docs/operating.md](docs/operating.md).

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

## Onboarding an existing host

This is the least obvious part of operating Butler, and worth following in order.

|   |                                                                                                                                                                                                                                                                               |
|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Install the jar and the launcher, and write `/etc/butler/butler.yaml`.                                                                                                                                                                                                        |
| 2 | `butler validate` until it is clean.                                                                                                                                                                                                                                          |
| 3 | `butler --dry-run` and **leave it for a day.** It starts every watcher, runs every `discover:` block for real and prints what each firing would do, without changing anything. Read the log. This is the safe way to point Butler at a server that is already running things. |
| 4 | `butler adopt` once. It runs each job's `discover:` block, records what the host is actually serving as state, and records the dedupe key of whatever artifact is already sitting in the watch directory. Without it, the first event after startup looks like new work.      |
| 5 | `systemctl enable --now butler`.                                                                                                                                                                                                                                              |

Step 4 is what stops a fresh install from redeploying an application that is already running the
right version. Step 3 is what tells you whether step 4 will do what you expect.

## The CLI

```
butler [--config <file>] [--dry-run]     # no subcommand: run as the daemon
butler validate                          # exit 1 listing every error, for CI
butler check                             # validate, then print the effective config
butler trigger <job> [--set k=v]         # run one job once, or rehearse it with --dry-run
butler adopt [<job>]                     # discovery only: record state, execute nothing
butler steps [<name>]                    # the registered step types and their parameters
butler generate-completion               # bash/zsh completion script
```

`--config` defaults to `/etc/butler/butler.yaml` and is on every command, so they all read the same
config the daemon will. Exit codes are `0` ok, `1` failure or validation errors, `2` bad usage.

Logs go to stderr; stdout carries whatever you asked for, so `butler trigger api --dry-run | less`
shows the plan and nothing else.

## Privileges, plainly

**`shell.run` executes with the daemon's privileges, and so does anything inside a `discover:`
block.** A `discover:` step runs even under `--dry-run` - it has to, or a dry run would report a
decision made against stale memory.

That makes the config file **as trusted as the daemon itself**. Keep it at `0640 root:butler`, put
secrets in the environment or a separate secrets file rather than inline, and treat write access to
it as equivalent to running commands as the `butler` user.

Butler itself runs unprivileged. Steps that need root go through a narrow `NOPASSWD` sudoers
allowlist, one line per unit and verb - never `systemctl *`. `butler --dry-run` warns when a
`systemd.*` step has no matching rule, which turns a 3am failure into a dry-run warning. There is
[a sample snippet](packaging/butler.sudoers) to copy.

Secrets are **not redacted** from logs or captured process output in v1. The guidance is not to
echo them; see DESIGN.md §11.

## Documentation

|                                                |                                                                                            |
|------------------------------------------------|--------------------------------------------------------------------------------------------|
| [docs/configuration.md](docs/configuration.md) | The config reference: every key, the expression language, the step and trigger vocabulary. |
| [docs/operating.md](docs/operating.md)         | systemd, install layout, sudoers, logging, the state directory, plugins.                   |
| [DESIGN.md](DESIGN.md)                         | Why it is shaped this way. Authoritative for the model.                                    |
| [plans/](plans/README.md)                      | The milestone plans, and which are built.                                                  |

`butler steps` is generated from the registry, so it documents the vocabulary this build actually
has rather than the one the docs were written against.

## Building

```bash
./gradlew build                 # compile and test
./gradlew shadowJar             # build/libs/butler-1.0-SNAPSHOT-all.jar
```

Java 25, Gradle 9. Five dependencies: picocli, Jackson, SLF4J, Logback and (test-only) JUnit and
ArchUnit. `ArchitectureTest` pins the package layering that keeps the runtime ignorant of what any
individual step does.
