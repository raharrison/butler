package net.ryanh.butler.notify;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.runtime.*;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.testing.Fixture;
import net.ryanh.butler.testing.StubServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Notifications against a real server: the job-level policy, the {@code notify.send} step, and
 * what a channel that refuses a message does to the run.
 */
class NotifierTest {

    @TempDir
    Path stateDir;

    private Run run(String yaml) {
        ConfigLoader.Result result = Fixture.config(yaml, StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return new JobRunner(Fixture.environment(result, StepRegistry.discover(), stateDir))
                .run(result.config().jobs().get("j"),
                        new Event("manual", Map.of("version", "1.2.4"), null));
    }

    @Test
    @DisplayName("a job's notify policy is delivered, with the message rendered")
    void theJobPolicySends() {
        try (StubServer server = StubServer.serving(200, "ok")) {
            Run run = run("""
                    notifiers:
                      ops:
                        uses: notify.webhook
                        url: %s
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log, message: go}]
                        notify:
                          to: ops
                          on: [success]
                          success: "api ${trigger.version} deployed"
                    """.formatted(server.url("/hook")));

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
            assertEquals("api 1.2.4 deployed", run.notification().message());
            assertEquals("{\"text\": \"api 1.2.4 deployed\"}",
                    server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("a channel that refuses the message does not fail a run that succeeded")
    void aFailedNotificationIsNotAFailedRun() {
        try (StubServer server = StubServer.serving(500, "no")) {
            Run run = run("""
                    notifiers:
                      ops:
                        uses: notify.webhook
                        url: %s
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log, message: go}]
                        notify:
                          to: ops
                          on: [success]
                          success: deployed
                    """.formatted(server.url("/hook")));

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
            assertEquals(1, server.received().size(), "it was tried, and it did fail");
        }
    }

    @Test
    @DisplayName("notify.send posts from the middle of a pipeline")
    void theStepSends() {
        try (StubServer server = StubServer.serving(200, "ok")) {
            Run run = run("""
                    notifiers:
                      ops:
                        uses: notify.slack
                        webhook: %s
                        channel: "#deploys"
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: notify.send
                            to: ops
                            message: staging ${trigger.version}
                    """.formatted(server.url("/webhook")));

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
            assertEquals("{\"text\": \"staging 1.2.4\", \"channel\": \"#deploys\"}",
                    server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("notify.send names the channel that does not exist rather than failing quietly")
    void anUnknownChannelFailsTheStep() {
        Run run = run("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: notify.send
                        to: nobody
                        message: hello
                """);
        assertEquals(Run.Status.FAILED, run.status());
        assertTrue(run.message().contains("no notifier named \"nobody\""), run.message());
    }

    @Test
    @DisplayName("a dry run renders the message and sends nothing, for the policy and the step "
            + "alike")
    void aDryRunSendsNothing() {
        try (StubServer server = StubServer.serving(200, "ok")) {
            String yaml = """
                    notifiers:
                      ops:
                        uses: notify.webhook
                        url: %s
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: notify.send
                            to: ops
                            message: staging ${trigger.version}
                        notify:
                          to: ops
                          on: [success]
                          success: "api ${trigger.version} deployed"
                    """.formatted(server.url("/hook"));
            ConfigLoader.Result result = Fixture.config(yaml, StepRegistry.discover());
            Plan plan = PlanBuilder.build(
                    Fixture.environment(result, StepRegistry.discover(), stateDir),
                    result.config().jobs().get("j"),
                    new Event("manual", Map.of("version", "1.2.4"), null), result.diagnostics());

            assertEquals(List.of("would send   ops <- \"staging 1.2.4\""),
                    plan.steps().getFirst().body());
            assertEquals("api 1.2.4 deployed", plan.notification().message());
            assertEquals(List.of(), server.received(), "a dry run notifies nobody");
        }
    }

    @Test
    @DisplayName("ntfy puts the message in the body and everything else in headers")
    void ntfyPostsPlainText() {
        try (StubServer server = StubServer.serving(200, "{}")) {
            Run run = run("""
                    notifiers:
                      phone:
                        uses: notify.ntfy
                        server: %s
                        topic: deploys
                        title: Butler
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: notify.send
                            to: phone
                            message: api 1.2.4 is live
                    """.formatted(server.url("")));

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
            assertEquals("/deploys", server.received().getFirst().path());
            assertEquals("api 1.2.4 is live", server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("a message holding a quote or a newline still makes valid JSON")
    void messagesAreEscaped() {
        try (StubServer server = StubServer.serving(200, "ok")) {
            run("""
                    notifiers:
                      ops: {uses: notify.discord, webhook: %s}
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: notify.send
                            to: ops
                            message: "he said \\"boom\\"\\nand left"
                    """.formatted(server.url("/hook")));

            assertEquals("{\"content\": \"he said \\\"boom\\\"\\nand left\"}",
                    server.received().getFirst().body());
        }
    }
}
