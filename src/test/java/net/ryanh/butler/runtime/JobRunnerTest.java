package net.ryanh.butler.runtime;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.spi.*;
import net.ryanh.butler.testing.FakeProcessRunner;
import net.ryanh.butler.testing.Fixture;
import net.ryanh.butler.testing.StubServer;
import net.ryanh.butler.util.Durations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
        return runner(yaml, Cancellation.none());
    }

    private JobRunner runner(String yaml, Cancellation cancel) {
        return runner(yaml, cancel, new FakeProcessRunner());
    }

    private JobRunner runner(String yaml, Cancellation cancel, ProcessRunner processes) {
        StepRegistry steps = StepRegistry.discover();
        ConfigLoader.Result result = config(yaml);
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return new JobRunner(Fixture.environment(result, steps, stateDir, processes), cancel);
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
        @DisplayName("a step's own outputs land on the Run, not just its status line")
        void stepOutputsAreKeptOnTheRun() {
            String yaml = """
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Greet
                            uses: shell.exec
                            argv: [ echo, hi ]
                          - name: Set nothing much
                            uses: control.log
                            message: quiet
                    """;
            FakeProcessRunner processes =
                    new FakeProcessRunner().replying(0, "line one\nline two\n", "");
            Run run = runner(yaml, Cancellation.none(), processes)
                    .run(config(yaml).config().jobs().get("j"), new Event("manual", Map.of(), null));

            assertEquals("line one\nline two\n", step(run, "Greet").outputs().get("stdout"));
            assertEquals(0L, step(run, "Greet").outputs().get("exit_code"));
            assertEquals(Map.of(), step(run, "Set nothing much").outputs(),
                    "a step with nothing to report keeps an empty map, not a missing one");
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
        @DisplayName("the runtime says how long it waited and the step says what it saw, because "
                + "the tail is the part worth reading")
        void aCutOffStepKeepsItsOwnAccount() {
            StepRegistry registry = StepRegistry.of(new StallingStep());
            ConfigLoader.Result result = Fixture.config("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Wait for health
                            uses: test.stall
                            timeout: 100ms
                    """, registry);
            Run run = new JobRunner(Fixture.environment(result, registry, stateDir))
                    .run(result.config().jobs().get("j"), new Event("manual", Map.of(), null));

            String message = step(run, "Wait for health").message();
            assertTrue(message.startsWith("timed out after 100ms: last status 503 after "),
                    message);
            assertTrue(message.endsWith(" probes"), message);
        }

        @Test
        @DisplayName("a step that merely lets the interrupt escape adds nothing to the timeout")
        void aStepWithNothingToSayAddsNothing() {
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

            assertEquals("timed out after 100ms", step(run, "Slow").message());
        }

        @Test
        @DisplayName("the timeout is reported in the syntax the config wrote it in")
        void theTimeoutReadsBackAsWritten() {
            Run run = run("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Slow
                            uses: control.sleep
                            duration: 30s
                            timeout: 1s
                    """, "j");

            assertEquals("timed out after 1s", step(run, "Slow").message());
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

        @Test
        @DisplayName("a job with no timeout: is bounded by settings.default_job_timeout")
        void defaultJobTimeoutBoundsAJobThatSetsNone() {
            Run run = run("""
                    settings:
                      default_job_timeout: 200ms
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Slow
                            uses: control.sleep
                            duration: 30s
                    """, "j");

            assertEquals(Run.Status.FAILED, run.status());
            assertEquals("the job's timeout of 200ms was exceeded", run.message(),
                    "the inherited default is reported as the job's own timeout, because that "
                            + "is what it is");
        }

        @Test
        @DisplayName("a job's own timeout: wins over the default")
        void anExplicitJobTimeoutWinsOverTheDefault() {
            Run run = run("""
                    settings:
                      default_job_timeout: 1ms
                    jobs:
                      j:
                        on: [{uses: manual}]
                        timeout: 30s
                        steps:
                          - name: Quick
                            uses: control.log
                            message: hi
                    """, "j");

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
        }
    }

    @Nested
    @DisplayName("notify")
    class Notify {

        /**
         * Two channels on one server, told apart by the path each posts to.
         */
        private String config(StubServer channels, String policy, String step) {
            return config(channels, policy, step, "");
        }

        private String config(StubServer channels, String policy, String step, String jobKeys) {
            return """
                    notifiers:
                      ops:
                        uses: notify.webhook
                        url: %s
                      oncall:
                        uses: notify.webhook
                        url: %s
                    jobs:
                      j:
                        on: [{uses: manual}]
                    %s    steps:
                          - name: Deploy
                            uses: %s
                    %s
                    """.formatted(channels.url("/ops"), channels.url("/oncall"), jobKeys, step,
                    policy);
        }

        private List<String> paths(StubServer channels) {
            return channels.received().stream().map(StubServer.Received::path).toList();
        }

        private String bodyTo(StubServer channels, String path) {
            return channels.received().stream()
                    .filter(r -> r.path().equals(path)).map(StubServer.Received::body)
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "nothing was posted to " + path + ", only " + paths(channels)));
        }

        private static final String BOTH_OUTCOMES = """
                    notify:
                      to: [ ops, oncall ]
                      on: [ success, failure ]
                      success: "deployed"
                      failure: "broke"
                """;

        @Test
        @DisplayName("every channel named gets the message")
        void everyChannelNamedIsSent() {
            try (StubServer channels = StubServer.serving(200, "")) {
                Run run = run(config(channels, BOTH_OUTCOMES, "control.log"), "j");

                assertEquals(Run.Status.SUCCESS, run.status(), run.message());
                assertEquals(List.of("ops", "oncall"), run.notification().to());
                assertEquals(List.of("/ops", "/oncall"), paths(channels));
            }
        }

        @Test
        @DisplayName("a channel that refuses does not stop the others, or fail the run")
        void oneRefusingChannelDoesNotStopTheRest() {
            try (StubServer channels = StubServer.serving(request ->
                    new StubServer.Answer(request.path().equals("/ops") ? 500 : 200, ""))) {
                Run run = run(config(channels, BOTH_OUTCOMES, "control.log"), "j");

                assertEquals(Run.Status.SUCCESS, run.status(), run.message());
                assertEquals(List.of("/ops", "/oncall"), paths(channels),
                        "the refusal is logged, and the next channel is still tried");
            }
        }

        private static final String WITH_RECOVERY = """
                    notify:
                      to: ops
                      on: [ success, failure, recovered ]
                      success: "deployed"
                      failure: "broke"
                      recovered: "back after ${run.previous_status}"
                """;

        @Test
        @DisplayName("a success whose last run failed is a recovery, and says what it recovered "
                + "from")
        void successAfterFailureIsARecovery() {
            try (StubServer channels = StubServer.serving(200, "")) {
                assertEquals(Run.Status.FAILED,
                        run(config(channels, WITH_RECOVERY, "control.fail"), "j").status());
                Run second = run(config(channels, WITH_RECOVERY, "control.log"), "j");

                assertEquals(Run.Status.SUCCESS, second.status(), second.message());
                assertEquals("back after failed", second.notification().message());
            }
        }

        @Test
        @DisplayName("a first-ever run is not a recovery")
        void aFirstRunIsNotARecovery() {
            try (StubServer channels = StubServer.serving(200, "")) {
                Run run = run(config(channels, WITH_RECOVERY, "control.log"), "j");
                assertEquals("deployed", run.notification().message());
            }
        }

        @Test
        @DisplayName("a second success in a row is not a recovery either")
        void successAfterSuccessIsNotARecovery() {
            try (StubServer channels = StubServer.serving(200, "")) {
                run(config(channels, WITH_RECOVERY, "control.log"), "j");
                Run second = run(config(channels, WITH_RECOVERY, "control.log"), "j");
                assertEquals("deployed", second.notification().message());
            }
        }

        @Test
        @DisplayName("a policy that never mentions recovered sends its success message, so "
                + "adding one is what opts a config in")
        void recoveryFallsBackToSuccess() {
            try (StubServer channels = StubServer.serving(200, "")) {
                run(config(channels, BOTH_OUTCOMES, "control.fail"), "j");
                Run second = run(config(channels, BOTH_OUTCOMES, "control.log"), "j");

                assertEquals("deployed", second.notification().message());
                assertEquals(List.of("/ops", "/oncall", "/ops", "/oncall"), paths(channels));
            }
        }

        @Test
        @DisplayName("a skipped run leaves the recorded failure standing, so the next success is "
                + "still a recovery")
        void aSkippedRunDoesNotClearAFailure() {
            try (StubServer channels = StubServer.serving(200, "")) {
                run(config(channels, WITH_RECOVERY, "control.fail"), "j");

                assertEquals(Run.Status.SKIPPED, run(config(channels, WITH_RECOVERY,
                        "control.log", "    when: false\n"), "j").status());

                Run third = run(config(channels, WITH_RECOVERY, "control.log"), "j");
                assertEquals("back after failed", third.notification().message());
            }
        }

        @Test
        @DisplayName("the rendered message is what the channel receives")
        void theChannelReceivesTheRenderedMessage() {
            try (StubServer channels = StubServer.serving(200, "")) {
                run(config(channels, BOTH_OUTCOMES, "control.fail"), "j");
                assertTrue(bodyTo(channels, "/oncall").contains("broke"),
                        bodyTo(channels, "/oncall"));
            }
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

    @Nested
    @DisplayName("cancellation")
    class Withdrawn {

        /**
         * Writes a marker, then stalls, so a cancellation can be timed to land mid-run.
         */
        private String yaml(Path marker) {
            return """
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: Start
                            uses: fs.template
                            content: started
                            to: %s
                            mkdirs: true
                          - name: Work
                            uses: control.sleep
                            duration: 30s
                        on_failure:
                          - name: Clean up
                            uses: control.log
                            message: cleaning up
                        persist:
                          done: "yes"
                    """.formatted(marker.toString().replace('\\', '/'));
        }

        @Test
        @DisplayName("a run displaced mid-step ends CANCELLED: no on_failure, nothing written")
        void displacedMidRun() throws Exception {
            Path marker = stateDir.resolve("started.txt");
            String yaml = yaml(marker);
            Cancellation cancel = new Cancellation();
            JobRunner runner = runner(yaml, cancel);
            var job = config(yaml).config().jobs().get("j");

            AtomicReference<Run> result = new AtomicReference<>();
            Thread thread = Thread.ofPlatform().name("run").start(() ->
                    result.set(runner.run(job, new Event("manual", Map.of(), "artifact-1"))));

            Instant deadline = Instant.now().plusSeconds(10);
            while (!Files.exists(marker) && Instant.now().isBefore(deadline)) {
                Thread.sleep(5);
            }
            assertTrue(Files.exists(marker), "the run never reached its first step");

            cancel.cancel("displaced by a newer event");
            assertTrue(thread.join(Duration.ofSeconds(10)), "the cancelled run never ended");

            Run run = result.get();
            assertEquals(Run.Status.CANCELLED, run.status());
            assertEquals("displaced by a newer event", run.message());
            assertTrue(run.steps().stream().noneMatch(s -> s.section().equals("on_failure")),
                    "nothing was wrong, so there is nothing for on_failure: to clean up");
            assertTrue(run.persisted().isEmpty());
            assertTrue(state("j").values().isEmpty(), "a cancelled run writes no state");
            assertNull(state("j").dedupeKey(), "nor claims the event as done");
        }

        @Test
        @DisplayName("a run cancelled before it starts runs no step at all")
        void withdrawnBeforeItStarts() {
            Path marker = stateDir.resolve("started.txt");
            String yaml = yaml(marker);
            Cancellation cancel = new Cancellation();
            cancel.cancel("butler is shutting down");

            Run run = runner(yaml, cancel)
                    .run(config(yaml).config().jobs().get("j"),
                            new Event("manual", Map.of(), null));

            assertEquals(Run.Status.CANCELLED, run.status());
            assertEquals("butler is shutting down", run.message());
            assertTrue(run.steps().isEmpty());
            assertFalse(Files.exists(marker));
        }

        @Test
        @DisplayName("a run cancelled before it starts observes nothing either")
        void withdrawnBeforeItObserves() {
            Path observed = stateDir.resolve("observed.txt");
            String yaml = """
                    jobs:
                      j:
                        on: [{uses: manual}]
                        discover:
                          - name: Ask the host
                            uses: fs.template
                            content: observed
                            to: %s
                            mkdirs: true
                            extract:
                              deployed_version: path
                        steps: [{uses: control.log, message: hi}]
                    """.formatted(observed.toString().replace('\\', '/'));
            Cancellation cancel = new Cancellation();
            cancel.cancel("butler is shutting down");

            Run run = runner(yaml, cancel).run(config(yaml).config().jobs().get("j"),
                    new Event("manual", Map.of(), null));

            assertEquals(Run.Status.CANCELLED, run.status());
            assertTrue(run.discover().isEmpty());
            assertFalse(Files.exists(observed),
                    "discovery runs steps on the host, and a withdrawn run has nobody to tell");
        }

        @Test
        @DisplayName("a cancelled run leaves no audit record either")
        void noRunRecord() {
            String yaml = yaml(stateDir.resolve("started.txt"));
            Cancellation cancel = new Cancellation();
            cancel.cancel("butler is shutting down");
            runner(yaml, cancel).run(config(yaml).config().jobs().get("j"),
                    new Event("manual", Map.of(), null));

            assertFalse(Files.exists(stateDir.resolve("runs")),
                    "the work was withdrawn, so there is nothing to record");
        }
    }

    /**
     * A step that catches its own interrupt and reports how far it got.
     */
    private static final class StallingStep implements StepType<StallingStep.Config> {

        public record Config() {
        }

        @Override
        public String name() {
            return "test.stall";
        }

        @Override
        public Class<Config> configType() {
            return Config.class;
        }

        @Override
        public StepResult execute(Config config, RunContext ctx) {
            int probes = 0;
            while (true) {
                probes++;
                try {
                    Thread.sleep(Duration.ofMillis(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return StepResult.failed("last status 503 after " + probes + " probes")
                            .output("probes", (long) probes);
                }
            }
        }

        @Override
        public String describe(Config config, RunContext ctx) {
            return "would poll until cut off";
        }
    }

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
