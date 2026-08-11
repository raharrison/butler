package net.ryanh.butler.runtime;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.testing.Fixture;
import net.ryanh.butler.util.Durations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The run status rules of DESIGN.md §2.1, one test each, plus what a run leaves behind.
 */
class JobRunnerTest {

    @TempDir
    Path stateDir;

    private Run run(String yaml, String job) {
        return run(yaml, job, new Event("manual", Map.of(), null));
    }

    private Run run(String yaml, String job, Event event) {
        return runner(yaml).run(config(yaml).config().jobs().get(job), event);
    }

    private JobRunner runner(String yaml) {
        StepRegistry steps = StepRegistry.discover();
        ConfigLoader.Result result = config(yaml);
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return new JobRunner(Fixture.environment(result, steps, stateDir));
    }

    private ConfigLoader.Result config(String yaml) {
        return Fixture.config(yaml, StepRegistry.discover());
    }

    private StateStore.JobState state(String job) {
        return StateStore.at(stateDir).read(job);
    }

    private static Run.Step step(Run run, String label) {
        return run.steps().stream().filter(s -> s.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("no step ran called \"" + label + "\""));
    }

    @Nested
    @DisplayName("the happy path")
    class Success {

        @Test
        void runsEveryStepInOrderAndSucceeds() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: One
                            uses: control.set
                            vars: {where: /srv/apps}
                          - name: Two
                            uses: control.assert
                            that: vars.where == "/srv/apps"
                    """, "j");

            assertEquals(Run.Status.SUCCESS, run.status());
            assertEquals(List.of("One", "Two"), run.steps().stream().map(Run.Step::label).toList());
            assertNull(run.failedStep());
        }

        @Test
        @DisplayName("persist: is evaluated and written, but only on success")
        void persistOnSuccess() {
            run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log, message: hi}]
                        persist:
                          deployed_version: ${trigger.version}
                    """, "j", new Event("manual", Map.of("version", "1.2.4"), null));

            assertEquals("1.2.4", state("j").values().get("deployed_version"));
        }

        @Test
        void persistIsNotWrittenWhenTheRunFailed() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.fail
                            message: no
                        persist:
                          deployed_version: "1.2.4"
                    """, "j");

            assertEquals(Run.Status.FAILED, run.status());
            assertNull(state("j").values().get("deployed_version"));
        }

        @Test
        @DisplayName("a step's when: false skips it without failing the run")
        void skippedStep() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Not this one
                            uses: control.fail
                            when: false
                          - name: This one
                            uses: control.log
                            message: hi
                    """, "j");

            assertEquals(Run.Status.SUCCESS, run.status());
            assertEquals(StepResult.Status.SKIPPED, step(run, "Not this one").status());
        }
    }

    @Nested
    @DisplayName("failure")
    class Failure {

        @Test
        void aFailingStepStopsTheRunAndNamesItself() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Break
                            uses: control.fail
                            message: the disk is full
                          - name: Never
                            uses: control.log
                            message: unreachable
                    """, "j");

            assertEquals(Run.Status.FAILED, run.status());
            assertEquals("Break", run.failedStep());
            assertEquals("the disk is full", run.message());
            assertEquals(1, run.steps().size(), "nothing after the failure ran");
        }

        @Test
        @DisplayName("continue_on_error records the failure but does not fail the run, so "
                + "on_success: and persist: still happen")
        void continueOnError() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Prune
                            uses: control.fail
                            message: nothing to prune
                            continue_on_error: true
                            register: prune
                          - name: Carry on
                            uses: control.assert
                            that: steps.prune.failed
                        on_success:
                          - name: Celebrate
                            uses: control.log
                            message: done
                        persist:
                          done: "yes"
                    """, "j");

            assertEquals(Run.Status.SUCCESS, run.status());
            assertEquals(StepResult.Status.FAILED, step(run, "Prune").status());
            assertEquals("yes", state("j").values().get("done"));
            assertNotNull(step(run, "Celebrate"));
        }

        @Test
        @DisplayName("a step that throws is a failed step, not a failed run loop")
        void aThrowingStepIsCaught() {
            Run run = throwing(new IllegalStateException("the host is on fire"), null);

            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(step(run, "Boom").message().contains("the host is on fire"),
                    step(run, "Boom").message());
            assertNotNull(step(run, "Cleanup"), "always: still ran");
        }

        @Test
        @DisplayName("an Error from a step is that step's failure, not the daemon's; a plugin with "
                + "a missing dependency must not take the run with it")
        void aStepThrowingAnErrorIsCaught() {
            Run run = throwing(new NoClassDefFoundError("com/example/Missing"),
                    Duration.ofSeconds(10));

            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(step(run, "Boom").message().contains("NoClassDefFoundError"),
                    step(run, "Boom").message());
            assertNotNull(step(run, "Cleanup"), "always: still ran");
            assertNotNull(state("j").lastRun(), "and the run was still recorded");
        }

        /**
         * @param timeout set to put the step on its own thread, which is where an Error would
         *                otherwise be lost rather than caught
         */
        private Run throwing(Throwable thrown, Duration timeout) {
            StepRegistry registry = StepRegistry.of(new ThrowingStep(thrown));
            ConfigLoader.Result result = Fixture.config("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Boom
                            uses: test.throw
                            %s
                        always: [{name: Cleanup, uses: control.log, message: tidying}]
                    """.formatted(timeout == null ? ""
                    : "timeout: " + Durations.format(timeout)), registry);
            return new JobRunner(Fixture.environment(result, registry, stateDir))
                    .run(result.config().jobs().get("j"), new Event("manual", Map.of(), null));
        }
    }

    @Nested
    @DisplayName("hooks")
    class Hooks {

        private static final String JOB = """
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - name: Work
                        uses: %s
                    on_failure:
                      - name: Roll back
                        uses: control.log
                        message: rolling back ${run.failed_step}
                    on_success:
                      - name: Announce
                        uses: control.log
                        message: done
                    always:
                      - name: Tidy up
                        uses: control.log
                        message: tidying
                """;

        @Test
        void onFailureAndAlwaysRunForAFailedRun() {
            Run run = run(JOB.formatted("control.fail"), "j");

            assertEquals(Run.Status.FAILED, run.status());
            assertNotNull(step(run, "Roll back"));
            assertNotNull(step(run, "Tidy up"));
            assertThrows(AssertionError.class, () -> step(run, "Announce"));
        }

        @Test
        void onSuccessAndAlwaysRunForASuccessfulRun() {
            Run run = run(JOB.formatted("control.log"), "j");

            assertEquals(Run.Status.SUCCESS, run.status());
            assertNotNull(step(run, "Announce"));
            assertNotNull(step(run, "Tidy up"));
            assertThrows(AssertionError.class, () -> step(run, "Roll back"));
        }

        @Test
        @DisplayName("a failure inside on_failure: is logged, not fatal, and the run's own status "
                + "stands")
        void aFailingHookDoesNotChangeTheOutcome() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.fail
                            message: the deploy broke
                        on_failure:
                          - name: Rollback
                            uses: control.fail
                            message: and so did the rollback
                        always:
                          - name: Tidy up
                            uses: control.log
                            message: tidying
                    """, "j");

            assertEquals(Run.Status.FAILED, run.status());
            assertEquals("the deploy broke", run.message(), "the original failure is what is "
                    + "reported, not the rollback's");
            assertNotNull(step(run, "Tidy up"), "and the rest of the hooks still ran");
        }

        @Test
        @DisplayName("hooks read run.status and run.failed_step")
        void hooksSeeTheOutcome() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Deploy
                            uses: control.fail
                            message: no
                        on_failure:
                          - name: Check
                            uses: control.assert
                            that: run.status == "failed" and run.failed_step == "Deploy"
                    """, "j");

            assertEquals(StepResult.Status.OK, step(run, "Check").status());
        }
    }

    @Nested
    @DisplayName("retry")
    class Retry {

        @Test
        void aFailingStepIsRetriedUpToTheAttemptLimit() {
            StepRegistry registry = StepRegistry.of(new FlakyStep(3));
            ConfigLoader.Result result = Fixture.config("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Flaky
                            uses: test.flaky
                            retry: {attempts: 3, delay: 1ms}
                    """, registry);
            Run run = new JobRunner(Fixture.environment(result, registry, stateDir))
                    .run(result.config().jobs().get("j"), new Event("manual", Map.of(), null));

            assertEquals(Run.Status.SUCCESS, run.status());
            assertEquals(3, step(run, "Flaky").attempts());
        }

        @Test
        @DisplayName("attempts are exhausted, not infinite")
        void retriesGiveUp() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Hopeless
                            uses: control.fail
                            message: never works
                            retry: {attempts: 2, delay: 1ms}
                    """, "j");

            assertEquals(Run.Status.FAILED, run.status());
            assertEquals(2, step(run, "Hopeless").attempts());
        }

        @Test
        @DisplayName("on: timeout does not retry a plain failure")
        void retryOnTimeoutIgnoresAFailure() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Hopeless
                            uses: control.fail
                            message: never works
                            retry: {attempts: 3, delay: 1ms, on: timeout}
                    """, "j");

            assertEquals(1, step(run, "Hopeless").attempts(),
                    "it failed rather than timed out, so the policy does not apply");
        }

        @Test
        @DisplayName("on: always retries whatever went wrong")
        void retryOnAlways() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Slow
                            uses: control.sleep
                            duration: 30s
                            timeout: 50ms
                            retry: {attempts: 2, delay: 1ms, on: always}
                    """, "j");

            assertEquals(2, step(run, "Slow").attempts(), "a timeout counts as \"whatever\"");
        }

        @Test
        @DisplayName("exponential backoff waits longer each time")
        void exponentialBackoff() {
            Instant started = Instant.now();
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Hopeless
                            uses: control.fail
                            message: never works
                            retry: {attempts: 3, delay: 100ms, backoff: exponential}
                    """, "j");

            // 100ms then 200ms, so at least 300ms in waiting alone.
            assertEquals(3, step(run, "Hopeless").attempts());
            assertTrue(Duration.between(started, Instant.now()).toMillis() >= 300,
                    "backoff should have doubled between attempts");
        }
    }

    @Nested
    @DisplayName("timeouts")
    class Timeouts {

        @Test
        @DisplayName("a step that overstays its timeout is cut off and fails")
        void stepTimeout() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Slow
                            uses: control.sleep
                            duration: 30s
                            timeout: 100ms
                    """, "j");

            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(step(run, "Slow").message().contains("timed out"),
                    step(run, "Slow").message());
        }

        @Test
        @DisplayName("on: timeout retries a timeout")
        void retryOnTimeout() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Slow
                            uses: control.sleep
                            duration: 30s
                            timeout: 50ms
                            retry: {attempts: 2, delay: 1ms, on: timeout}
                    """, "j");

            assertEquals(2, step(run, "Slow").attempts());
        }

        @Test
        @DisplayName("every line a step logs says which run, job and step it belongs to, even "
                + "when the step runs on its own thread for its timeout")
        void theStepThreadInheritsTheLogContext() {
            RecordingStep step = new RecordingStep();
            StepRegistry registry = StepRegistry.of(step);
            ConfigLoader.Result result = Fixture.config("""
                    jobs:
                      deploy:
                        on: [{uses: manual}]
                        steps:
                          - name: Look at the MDC
                            uses: test.record
                            timeout: 10s
                    """, registry);
            new JobRunner(Fixture.environment(result, registry, stateDir))
                    .run(result.config().jobs().get("deploy"), new Event("manual", Map.of(), null));

            assertEquals("deploy", step.context.get("job"));
            assertEquals("Look at the MDC", step.context.get("step"));
            assertNotNull(step.context.get("run_id"));
        }

        @Test
        @DisplayName("the job timeout fails the run, so on_failure: gets to clean up")
        void jobTimeoutIsAFailure() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        timeout: 200ms
                        steps:
                          - name: Slow
                            uses: control.sleep
                            duration: 30s
                        on_failure:
                          - name: Clean up
                            uses: control.log
                            message: cleaning up
                    """, "j");

            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(run.message().contains("timeout"), run.message());
            assertEquals(StepResult.Status.OK, step(run, "Clean up").status(),
                    "on_failure: gets to clean up after a job timeout, so it cannot be held to a "
                            + "deadline that has already passed");
        }
    }

    @Nested
    @DisplayName("state and dedupe")
    class State {

        @Test
        @DisplayName("the job's when: is judged against what discovery observed")
        void whenSeesDiscoveredState() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        discover:
                          - uses: control.set
                            vars: {observed: 1.2.4}
                            extract:
                              deployed_version: vars.observed
                        when: semver(trigger.version) > semver(default(state.deployed_version, "0.0.0"))
                        steps: [{uses: control.fail, message: should not deploy}]
                    """, "j", new Event("manual", Map.of("version", "1.2.4"), null));

            assertEquals(Run.Status.SKIPPED, run.status());
            assertTrue(run.steps().isEmpty(), "the pipeline never started");
        }

        @Test
        @DisplayName("a skipped run still records what it discovered, or every poll would "
                + "rediscover and re-skip forever")
        void aSkippedRunStillRecords() {
            run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        discover:
                          - uses: control.set
                            vars: {observed: 1.2.4}
                            extract:
                              deployed_version: vars.observed
                        when: false
                        steps: [{uses: control.log, message: hi}]
                    """, "j", new Event("manual", Map.of(), "artifact-1.2.4"));

            assertEquals("1.2.4", state("j").values().get("deployed_version"));
            assertEquals("artifact-1.2.4", state("j").dedupeKey());
        }

        @Test
        @DisplayName("an event already processed does not run again, which is what makes a "
                + "restart cheap")
        void dedupe() {
            String yaml = """
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Deploy
                            uses: control.log
                            message: deploying
                    """;
            Event event = new Event("manual", Map.of(), "artifact-1.2.4");

            Run first = run(yaml, "j", event);
            assertEquals(Run.Status.SUCCESS, first.status());

            // Same event again: a restart with the same artifact still sitting there.
            Run second = run(yaml, "j", event);
            assertEquals(Run.Status.SKIPPED, second.status());
            assertTrue(second.steps().isEmpty(), "nothing re-ran");
            assertTrue(second.message().contains("dedupe"), second.message());

            Run newer = run(yaml, "j", new Event("manual", Map.of(), "artifact-1.2.5"));
            assertEquals(Run.Status.SUCCESS, newer.status(), "a new key is new work");
        }

        @Test
        @DisplayName("a run asked for by hand carries no key and is never suppressed")
        void noKeyMeansAlwaysRun() {
            String yaml = """
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log, message: hi}]
                    """;
            assertEquals(Run.Status.SUCCESS, run(yaml, "j").status());
            assertEquals(Run.Status.SUCCESS, run(yaml, "j").status());
        }

        @Test
        @DisplayName("adopt records state and the dedupe key without executing a step")
        void adopt() {
            ConfigLoader.Result result = config("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        discover:
                          - uses: control.set
                            vars: {observed: 1.2.3}
                            extract:
                              deployed_version: vars.observed
                        steps: [{uses: control.fail, message: adopt must not run this}]
                    """);
            JobRunner runner = new JobRunner(
                    Fixture.environment(result, StepRegistry.discover(), stateDir));

            JobRunner.Adoption adopted = runner.adopt(result.config().jobs().get("j"),
                    new Event("manual", Map.of(), "already-here"));

            assertEquals("1.2.3", adopted.state().get("deployed_version"));
            assertEquals("already-here", adopted.dedupeKey());
            assertEquals("1.2.3", state("j").values().get("deployed_version"));

            // And the whole point: the event sitting there when the daemon starts is not new work.
            assertFalse(runner.isNewWork(result.config().jobs().get("j"),
                    new Event("manual", Map.of(), "already-here")));
        }
    }

    /**
     * Reports the logging context it was called with.
     */
    private static final class RecordingStep implements StepType<RecordingStep.Config> {

        public record Config() {
        }

        private final Map<String, String> context = new LinkedHashMap<>();

        @Override
        public String name() {
            return "test.record";
        }

        @Override
        public Class<Config> configType() {
            return Config.class;
        }

        @Override
        public StepResult execute(Config config, RunContext ctx) {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            if (mdc != null) {
                context.putAll(mdc);
            }
            return StepResult.ok();
        }

        @Override
        public String describe(Config config, RunContext ctx) {
            return "would look at the MDC";
        }
    }

    /**
     * Fails the first {@code n - 1} times it is called, then succeeds.
     */
    private static final class FlakyStep implements StepType<FlakyStep.Config> {

        public record Config() {
        }

        private final int succeedsOn;
        private int calls;

        FlakyStep(int succeedsOn) {
            this.succeedsOn = succeedsOn;
        }

        @Override
        public String name() {
            return "test.flaky";
        }

        @Override
        public Class<Config> configType() {
            return Config.class;
        }

        @Override
        public StepResult execute(Config config, RunContext ctx) {
            return ++calls >= succeedsOn ? StepResult.ok() : StepResult.failed("not yet");
        }

        @Override
        public String describe(Config config, RunContext ctx) {
            return "would be flaky";
        }
    }

    /**
     * Throws rather than returning a failed result, which a step written by someone else will.
     */
    private static final class ThrowingStep implements StepType<ThrowingStep.Config> {

        public record Config() {
        }

        private final Throwable thrown;

        ThrowingStep(Throwable thrown) {
            this.thrown = thrown;
        }

        @Override
        public String name() {
            return "test.throw";
        }

        @Override
        public Class<Config> configType() {
            return Config.class;
        }

        @Override
        public StepResult execute(Config config, RunContext ctx) throws Exception {
            switch (thrown) {
                case Error e -> throw e;
                case Exception e -> throw e;
                default -> throw new IllegalStateException(thrown);
            }
        }

        @Override
        public String describe(Config config, RunContext ctx) {
            return "would throw";
        }
    }
}
