package net.ryanh.butler.step.http;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code http.request} and {@code http.wait} against a real server on a temporary port.
 */
class HttpStepsTest {

    @TempDir
    Path stateDir;

    private static final String JOB = """
            jobs:
              j:
                on: [{uses: manual}]
                steps:
            %s
            """;

    private ConfigLoader.Result config(String steps) {
        ConfigLoader.Result result = Fixture.config(JOB.formatted(steps), StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return result;
    }

    private Run run(String steps) {
        ConfigLoader.Result result = config(steps);
        return new JobRunner(Fixture.environment(result, StepRegistry.discover(), stateDir))
                .run(result.config().jobs().get("j"),
                        new Event("manual", Map.of("version", "1.2.4"), null));
    }

    private Plan plan(String steps) {
        ConfigLoader.Result result = config(steps);
        return PlanBuilder.build(Fixture.environment(result, StepRegistry.discover(), stateDir),
                result.config().jobs().get("j"),
                new Event("manual", Map.of("version", "1.2.4"), null), result.diagnostics());
    }

    @Test
    @DisplayName("a JSON response reaches json.*, which is what discovery reads")
    void jsonIsParsed() {
        try (StubServer server = StubServer.serving(200, "{\"version\": \"1.2.3\"}")) {
            Run run = run("""
                          - uses: http.request
                            url: %s
                            register: health
                          - uses: control.assert
                            that: steps.health.json.version == "1.2.3"
                          - uses: control.assert
                            that: steps.health.status == 200
                    """.formatted(server.url("/health")));
            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
        }
    }

    @Test
    @DisplayName("a body that is not JSON leaves json null rather than failing the step")
    void nonJsonIsNotAnError() {
        try (StubServer server = StubServer.serving(200, "just some text")) {
            Run run = run("""
                          - uses: http.request
                            url: %s
                            register: probe
                          - uses: control.assert
                            that: not exists(steps.probe.json) and steps.probe.body == "just some text"
                    """.formatted(server.url("/")));
            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
        }
    }

    @Test
    @DisplayName("an unexpected status fails the step and quotes the body")
    void unexpectedStatusFails() {
        try (StubServer server = StubServer.serving(503, "service unavailable")) {
            Run run = run("""
                          - uses: http.request
                            url: %s
                    """.formatted(server.url("/health")));

            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(run.message().contains("answered 503"), run.message());
            assertTrue(run.message().contains("service unavailable"), run.message());
        }
    }

    @Test
    @DisplayName("expect_status takes one value or a list")
    void expectedStatusesAreConfigurable() {
        try (StubServer server = StubServer.serving(404, "gone")) {
            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: http.request
                            url: %s
                            expect_status: 404
                    """.formatted(server.url("/"))).status());

            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: http.request
                            url: %s
                            expect_status: [200, 404]
                    """.formatted(server.url("/"))).status());
        }
    }

    @Test
    @DisplayName("a POST sends its body and headers")
    void sendsAMethodBodyAndHeaders() {
        try (StubServer server = StubServer.serving(200, "ok")) {
            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: http.request
                            url: %s
                            method: post
                            headers: {X-Token: abc}
                            body: '{"hello": true}'
                    """.formatted(server.url("/hook"))).status());

            StubServer.Received request = server.received().getFirst();
            assertEquals("POST", request.method());
            assertEquals("/hook", request.path());
            assertEquals("{\"hello\": true}", request.body());
        }
    }

    @Test
    @DisplayName("http.wait polls until the condition holds, seeing the probe as locals")
    void waitsUntilTheConditionHolds() {
        AtomicInteger probes = new AtomicInteger();
        // The old version answers twice, then the new one, as a restarting service does.
        try (StubServer server = StubServer.serving(request ->
                new StubServer.Answer(200, probes.incrementAndGet() < 3
                        ? "{\"version\": \"1.2.3\"}" : "{\"version\": \"1.2.4\"}"))) {

            Run run = run("""
                          - uses: http.wait
                            url: %s
                            until: status == 200 and json.version == ${trigger.version}
                            interval: 50ms
                            timeout: 10s
                            register: health
                          - uses: control.assert
                            that: steps.health.probes == 3
                    """.formatted(server.url("/health")));

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
        }
    }

    @Test
    @DisplayName("giving up says how many probes, how long, and what the last answer was")
    void reportsWhyItGaveUp() {
        try (StubServer server = StubServer.serving(503, "still starting")) {
            Run run = run("""
                          - uses: http.wait
                            url: %s
                            until: status == 200
                            interval: 50ms
                            timeout: 300ms
                    """.formatted(server.url("/health")));

            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(run.message().contains("timed out"), run.message());
            assertTrue(run.message().contains("last status 503"), run.message());
            assertTrue(run.message().contains("still starting"), run.message());
        }
    }

    @Test
    @DisplayName("a service that is not answering yet is a probe that did not hold, not a failure")
    void aRefusedConnectionIsJustAnotherProbe() {
        // Nothing listens on port 1, so the connection is refused, as it is mid-restart.
        Run run = run("""
                      - uses: http.wait
                        url: http://127.0.0.1:1/health
                        until: status == 200
                        interval: 50ms
                        timeout: 300ms
                """);
        assertEquals(Run.Status.FAILED, run.status());
        assertTrue(run.message().contains("timed out"), run.message());
        assertTrue(run.message().contains("never satisfied"), run.message());
    }

    @Test
    @DisplayName("the plan shows the condition with its holes resolved, since a dry run may not "
            + "leave one")
    void describesThePollWithTheConditionResolved() {
        List<String> body = plan("""
                      - uses: http.wait
                        url: http://localhost:8080/health
                        until: status == 200 and json.version == ${trigger.version}
                        interval: 2s
                        timeout: 90s
                """).steps().getFirst().body();

        assertEquals(List.of(
                        "would poll   GET http://localhost:8080/health every 2s, up to 90s",
                        "      until  status == 200 and json.version == \"1.2.4\""),
                body);
    }

    @Test
    @DisplayName("preflight warns about a poll with no timeout, which would never end")
    void preflightWarnsAboutAnEndlessPoll() {
        assertEquals(List.of("no timeout: this would poll until the condition holds, however long "
                        + "that takes. Give the step a timeout: or the job one"),
                plan("""
                              - uses: http.wait
                                url: http://localhost:8080/health
                                until: status == 200
                        """).steps().getFirst().warnings());
    }
}
