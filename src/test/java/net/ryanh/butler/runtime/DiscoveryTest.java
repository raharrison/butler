package net.ryanh.butler.runtime;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.testing.FakeProcessRunner;
import net.ryanh.butler.testing.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The discovery rules of DESIGN.md §6.2, which make {@code state.*} mean what the host says rather
 * than what Butler remembers.
 */
class DiscoveryTest {

    @TempDir
    Path stateDir;

    private final FakeProcessRunner processes = new FakeProcessRunner();

    private ConfigLoader.Result config(String yaml) {
        ConfigLoader.Result result = Fixture.config(yaml, StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return result;
    }

    private RunEnvironment environment(ConfigLoader.Result config) {
        return Fixture.environment(config, StepRegistry.discover(), stateDir, processes);
    }

    private void persisted(String job, Map<String, Object> values) throws IOException {
        StateStore.at(stateDir).write(job,
                new StateStore.JobState(null, Instant.now(), null, values));
    }

    /**
     * Reports whatever the test said the host would say.
     */
    private static final String ASK_THE_HOST = """
            jobs:
              j:
                on: [{uses: manual}]
                discover:
                  - name: Ask the host
                    uses: shell.run
                    script: cat /srv/apps/api/VERSION
                    extract:
                      deployed_version: trim(stdout)
                steps:
                  - name: Deploy
                    uses: shell.run
                    script: install ${trigger.version}
            """;

    /**
     * DESIGN.md §6.2's table, which is the whole argument for running discovery on every event.
     * Each row is a situation the daemon has to get right without being told which one it is in.
     */
    @Nested
    @DisplayName("the situations discovery exists for")
    class TheTable {

        private static final String JOB = """
                jobs:
                  api:
                    on: [{uses: manual}]
                    discover:
                      - name: Ask the host
                        uses: shell.run
                        script: cat VERSION
                        extract:
                          deployed_version: trim(stdout)
                    when: semver(trigger.version) > semver(default(state.deployed_version, "0.0.0"))
                    steps:
                      - uses: control.log
                        message: deploying ${trigger.version}
                    persist:
                      deployed_version: ${trigger.version}
                """;

        private Run arrives(String artifact) {
            ConfigLoader.Result config = config(JOB);
            return new JobRunner(environment(config)).run(config.config().jobs().get("api"),
                    new Event("manual", Map.of("version", artifact), "artifact-" + artifact));
        }

        @Test
        @DisplayName("fresh install on a live host: the artifact is what is already running")
        void freshInstallOnALiveHost() {
            processes.replying(0, "1.2.3\n", "");
            assertEquals(Run.Status.SKIPPED, arrives("1.2.3").status());
        }

        @Test
        @DisplayName("never deployed: nothing to observe, so it deploys")
        void neverDeployed() {
            processes.replying(1, "", "cat: no such file");
            assertEquals(Run.Status.SUCCESS, arrives("1.2.3").status());
        }

        @Test
        @DisplayName("steady state: a newer artifact arrives")
        void steadyState() {
            processes.replying(0, "1.2.3\n", "");
            assertEquals(Run.Status.SUCCESS, arrives("1.2.4").status());
        }

        @Test
        @DisplayName("the state directory was wiped: the host is asked and nothing happens")
        void stateWiped() throws IOException {
            processes.replying(0, "1.2.3\n", "");
            arrives("1.2.3");
            Files.delete(StateStore.at(stateDir).fileFor("api"));

            assertEquals(Run.Status.SKIPPED, arrives("1.2.3").status(),
                    "losing the state directory is harmless when the host can be asked");
        }

        @Test
        @DisplayName("crashed mid-run: the old version is still serving, so it redeploys")
        void crashedMidRun() throws IOException {
            // State says 1.2.4 landed, the host says otherwise: the symlink swapped but the
            // service never restarted.
            persisted("api", Map.of("deployed_version", "1.2.4"));
            processes.replying(0, "1.2.3\n", "");

            assertEquals(Run.Status.SUCCESS, arrives("1.2.4").status(),
                    "observed reality beats the record, so the run converges");
        }

        @Test
        @DisplayName("someone rolled back by hand: the drift is corrected")
        void outOfBandRollback() throws IOException {
            persisted("api", Map.of("deployed_version", "1.2.4"));
            processes.replying(0, "1.2.2\n", "");

            assertEquals(Run.Status.SUCCESS, arrives("1.2.4").status());
        }

        @Test
        @DisplayName("discovery times out: the persisted value stands and the run is unharmed")
        void discoveryTimesOut() throws IOException {
            persisted("api", Map.of("deployed_version", "1.2.3"));
            processes.replying(c -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ProcessRunner.Completed(0, "", "", Duration.ZERO, true);
            });

            ConfigLoader.Result config = config(JOB.replace("script: cat VERSION",
                    "script: cat VERSION\n        timeout: 100ms"));
            Run run = new JobRunner(environment(config)).run(config.config().jobs().get("api"),
                    new Event("manual", Map.of("version", "1.2.3"), null));

            assertEquals(Run.Status.SKIPPED, run.status(),
                    "the host is known to be on 1.2.3 already, timeout or no timeout");
            assertEquals("1.2.3",
                    StateStore.at(stateDir).read("api").values().get("deployed_version"));
        }
    }

    @Test
    @DisplayName("the job's timeout bounds discovery as well, so an untimed probe cannot hang a run")
    void theJobTimeoutBoundsDiscovery() {
        processes.replying(c -> {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ProcessRunner.Completed(0, "", "", Duration.ZERO, true);
        });

        ConfigLoader.Result config = config("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    timeout: 200ms
                    discover:
                      - name: Ask the host
                        uses: shell.run
                        script: cat VERSION
                        extract:
                          deployed_version: trim(stdout)
                    steps:
                      - name: Deploy
                        uses: control.log
                        message: deploying
                """);
        Instant started = Instant.now();
        Run run = new JobRunner(environment(config)).run(config.config().jobs().get("j"),
                new Event("manual", Map.of(), null));

        assertEquals(Run.Status.FAILED, run.status());
        assertEquals("the job's timeout of 200ms was exceeded", run.message());
        assertTrue(Duration.between(started, Instant.now()).compareTo(Duration.ofSeconds(10)) < 0,
                "the run outlived its deadline waiting for a discovery step with no timeout of its "
                        + "own");
    }

    @Test
    @DisplayName("what the host says beats what was persisted")
    void discoveredValuesOverlayPersistedOnes() throws IOException {
        persisted("j", Map.of("deployed_version", "1.0.0"));
        processes.replying(0, "1.2.3\n", "");

        ConfigLoader.Result config = config(ASK_THE_HOST);
        Run run = new JobRunner(environment(config))
                .run(config.config().jobs().get("j"), new Event("manual", Map.of(), null));

        assertEquals("1.2.3", StateStore.at(stateDir).read("j").values().get("deployed_version"));
        assertEquals(2, processes.commands().size(), "discovery, then the pipeline");
        assertEquals(Run.Status.SUCCESS, run.status());
    }

    @Test
    @DisplayName("a failing discovery step is not a failing run, and the persisted value stands")
    void discoveryFailureLeavesPersistedStateAlone() throws IOException {
        persisted("j", Map.of("deployed_version", "1.0.0"));
        // The host cannot say what is deployed, but installing still works.
        processes.replying(c -> c.display().contains("cat")
                ? new ProcessRunner.Completed(1, "", "cat: no such file", Duration.ZERO, false)
                : new ProcessRunner.Completed(0, "", "", Duration.ZERO, false));

        ConfigLoader.Result config = config(ASK_THE_HOST);
        Run run = new JobRunner(environment(config))
                .run(config.config().jobs().get("j"), new Event("manual", Map.of(), null));

        assertEquals(Run.Status.SUCCESS, run.status(),
                "a health endpoint being briefly down must not fail the run");
        assertEquals("1.0.0", StateStore.at(stateDir).read("j").values().get("deployed_version"),
                "and must not erase what was last known either");
        assertNotNull(run.discover().getFirst().error());
    }

    @Test
    @DisplayName("discovery runs for real under --dry-run; the pipeline does not")
    void discoveryRunsInADryRun() {
        processes.replying(0, "1.2.3\n", "");

        ConfigLoader.Result config = config(ASK_THE_HOST);
        Plan plan = PlanBuilder.build(environment(config), config.config().jobs().get("j"),
                new Event("manual", Map.of("version", "1.2.4"), null), config.diagnostics());

        assertEquals(1, processes.commands().size(),
                "exactly one process ran: the discovery step, not the pipeline");
        assertTrue(processes.last().display().contains("cat /srv/apps/api/VERSION"),
                processes.last().display());
        assertEquals("state.deployed_version = \"1.2.3\"",
                plan.discover().getFirst().body().getFirst());
        assertTrue(PlanRenderer.render(plan).contains("discover  (executed for real)"));
    }

    @Test
    @DisplayName("a dry run leaves no trace: no state, no dedupe key")
    void aDryRunWritesNothing() {
        processes.replying(0, "1.2.3\n", "");
        ConfigLoader.Result config = config(ASK_THE_HOST);
        PlanBuilder.build(environment(config), config.config().jobs().get("j"),
                new Event("manual", Map.of("version", "1.2.4"), "artifact-1.2.4"),
                config.diagnostics());

        assertTrue(StateStore.at(stateDir).read("j").values().isEmpty());
        assertNull(StateStore.at(stateDir).read("j").dedupeKey());
    }

    @Test
    @DisplayName("extract: evaluates against the step's own result fields")
    void extractSeesTheStepsOwnOutputs() {
        processes.replying(0, "  v1.2.3  \n", "");

        ConfigLoader.Result config = config("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    discover:
                      - uses: shell.run
                        script: myapp --version
                        extract:
                          deployed_version: match(stdout, 'v?(\\d+\\.\\d+\\.\\d+)', 1)
                          exit: exit_code
                    steps: [{uses: control.log, message: hi}]
                """);
        new JobRunner(environment(config))
                .run(config.config().jobs().get("j"), new Event("manual", Map.of(), null));

        Map<String, Object> state = StateStore.at(stateDir).read("j").values();
        assertEquals("1.2.3", state.get("deployed_version"));
        assertEquals(0, ((Number) state.get("exit")).intValue());
    }

    @Test
    @DisplayName("an extract that produces nothing leaves the persisted value standing")
    void anExtractYieldingNullObservesNothing() throws IOException {
        persisted("j", Map.of("deployed_version", "1.2.3"));
        // The command worked; its output just did not hold a version. Observing that null would
        // erase what the host is known to be running and deploy over it.
        processes.replying(0, "some unexpected banner\n", "");

        ConfigLoader.Result config = config("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    discover:
                      - uses: shell.run
                        script: myapp --version
                        extract:
                          deployed_version: match(stdout, 'v?(\\d+\\.\\d+\\.\\d+)', 1)
                    when: semver(trigger.version) > semver(default(state.deployed_version, "0.0.0"))
                    steps: [{uses: control.fail, message: this must not deploy}]
                """);
        Run run = new JobRunner(environment(config)).run(config.config().jobs().get("j"),
                new Event("manual", Map.of("version", "1.0.0"), null));

        assertEquals(Run.Status.SKIPPED, run.status(),
                "1.0.0 is older than the 1.2.3 on the host, so there is nothing to do");
        assertEquals("1.2.3", StateStore.at(stateDir).read("j").values().get("deployed_version"),
                "and the persisted value survives rather than being overwritten with null");
        assertTrue(run.discover().getFirst().body().getFirst().contains("not extracted"),
                run.discover().getFirst().body().toString());
    }

    @Test
    @DisplayName("a discover step's when: is judged before it runs")
    void discoverStepsHonourTheirCondition() throws IOException {
        persisted("j", Map.of("deployed_version", "1.0.0"));

        ConfigLoader.Result config = config("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    discover:
                      - name: Only if nothing is known
                        uses: shell.run
                        script: cat VERSION
                        when: not exists(state.deployed_version)
                    steps: [{uses: control.log, message: hi}]
                """);
        Run run = new JobRunner(environment(config))
                .run(config.config().jobs().get("j"), new Event("manual", Map.of(), null));

        assertTrue(processes.commands().isEmpty(), "the fallback was not needed");
        assertEquals("skipped: when=false", run.discover().getFirst().skipped());
    }
}
