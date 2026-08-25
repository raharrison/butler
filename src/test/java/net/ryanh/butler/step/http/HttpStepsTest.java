package net.ryanh.butler.step.http;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.runtime.*;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.testing.Fixture;
import net.ryanh.butler.testing.StubServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code http.request} and {@code http.wait} against a real server on a temporary port.
 */
class HttpStepsTest {

    @TempDir
    Path stateDir;

    /**
     * Where a downloaded file lands.
     */
    @TempDir
    Path dir;

    /**
     * A temp path spelled with forward slashes, the way a config writes one.
     */
    private String at(String name) {
        return dir.resolve(name).toString().replace('\\', '/');
    }

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
    @DisplayName("preflight warns about a poll with no timeout, which takes the run down with it")
    void preflightWarnsAboutAnEndlessPoll() {
        assertEquals(List.of("no timeout: this would poll until the job's runs out, which fails "
                        + "the whole run rather than this step. Give the step a timeout:"),
                plan("""
                              - uses: http.wait
                                url: http://localhost:8080/health
                                until: status == 200
                        """).steps().getFirst().warnings());
    }

    @Test
    @DisplayName("a response over max_bytes fails the step rather than reaching the run record")
    void refusesAnOversizedBody() {
        try (StubServer server = StubServer.serving(200, "x".repeat(100))) {
            Run run = run("""
                          - uses: http.request
                            url: %s
                            max_bytes: 10
                    """.formatted(server.url("/big")));
            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(run.message().contains("over the max_bytes of 10"), run.message());
        }
    }

    @Test
    @DisplayName("a body exactly at max_bytes still reads, so the cap is a limit and not a margin")
    void readsABodyExactlyAtTheCap() {
        try (StubServer server = StubServer.serving(200, "0123456789")) {
            Run run = run("""
                          - uses: http.request
                            url: %s
                            max_bytes: 10
                            register: r
                          - uses: control.assert
                            that: steps.r.body == "0123456789"
                    """.formatted(server.url("/exact")));
            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
        }
    }

    @Test
    @DisplayName("a poll gives up on an oversized body, since polling cannot make it smaller")
    void pollRefusesAnOversizedBody() {
        try (StubServer server = StubServer.serving(200, "x".repeat(100))) {
            Run run = run("""
                          - uses: http.wait
                            url: %s
                            until: status == 200
                            max_bytes: 10
                            interval: 10ms
                            timeout: 5s
                    """.formatted(server.url("/big")));
            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(run.message().contains("over the max_bytes of 10"), run.message());
        }
    }

    /**
     * {@code http.download} against the same real server. The digests are the known sha256 of the
     * bodies served, computed outside this JVM, so a hashing mistake here cannot agree with itself.
     */
    @Nested
    @DisplayName("download")
    class Download {

        private static final String ARTIFACT = "artifact contents";
        private static final String ARTIFACT_SHA =
                "4334ca16f2ec5681429141d70f1738c1651c215830ec3ea5fa5ba83ef0288552";
        private static final String RELEASE = "a new release";
        private static final String RELEASE_SHA =
                "d26eb597b59ae273a0fec9e32ee9f5222976089144c3cd1886142b03a8f6fddb";

        @Test
        @DisplayName("fetches a file and reports what arrived")
        void downloads() throws IOException {
            try (StubServer server = StubServer.serving(200, ARTIFACT)) {
                Run run = run("""
                              - uses: http.download
                                url: %s
                                to: %s
                                register: got
                              - uses: control.assert
                                that: steps.got.bytes == 17
                              - uses: control.assert
                                that: steps.got.sha256 == "%s"
                              - uses: control.assert
                                that: steps.got.status == 200
                        """.formatted(server.url("/api.jar"), at("api.jar"), ARTIFACT_SHA));

                assertEquals(Run.Status.SUCCESS, run.status(), run.message());
                assertEquals(ARTIFACT, Files.readString(dir.resolve("api.jar")));
            }
        }

        @Test
        @DisplayName("a checksum that matches passes, and nothing is left beside the file")
        void checksumMatches() throws IOException {
            try (StubServer server = StubServer.serving(200, ARTIFACT)) {
                Run run = run("""
                              - uses: http.download
                                url: %s
                                to: %s
                                checksum: "sha256:%s"
                        """.formatted(server.url("/api.jar"), at("api.jar"), ARTIFACT_SHA));

                assertEquals(Run.Status.SUCCESS, run.status(), run.message());
                assertEquals(List.of("api.jar"), names(dir));
            }
        }

        @Test
        @DisplayName("a bare hex checksum is read as a sha256")
        void bareChecksumIsSha256() {
            try (StubServer server = StubServer.serving(200, ARTIFACT)) {
                assertEquals(Run.Status.SUCCESS, run("""
                              - uses: http.download
                                url: %s
                                to: %s
                                checksum: %s
                        """.formatted(server.url("/api.jar"), at("api.jar"), ARTIFACT_SHA)).status());
            }
        }

        @Test
        @DisplayName("a checksum that does not match fails, and what was there before stands")
        void checksumMismatchLeavesTheOldFile() throws IOException {
            Files.writeString(dir.resolve("api.jar"), "the release that works");
            try (StubServer server = StubServer.serving(200, "something else entirely")) {
                Run run = run("""
                              - uses: http.download
                                url: %s
                                to: %s
                                checksum: "sha256:%s"
                        """.formatted(server.url("/api.jar"), at("api.jar"), ARTIFACT_SHA));

                assertEquals(Run.Status.FAILED, run.status());
                assertTrue(run.steps().getFirst().message().startsWith("checksum mismatch: expected"),
                        run.steps().getFirst().message());
                assertEquals("the release that works", Files.readString(dir.resolve("api.jar")),
                        "a download that did not check out must not replace what is serving");
                assertEquals(List.of("api.jar"), names(dir), "and leaves no part file behind");
            }
        }

        @Test
        @DisplayName("a status that is not 2xx writes no file and quotes what the server said")
        void badStatusWritesNothing() throws IOException {
            try (StubServer server = StubServer.serving(404, "no such artifact")) {
                Run run = run("""
                              - uses: http.download
                                url: %s
                                to: %s
                        """.formatted(server.url("/api.jar"), at("api.jar")));

                assertEquals(Run.Status.FAILED, run.status());
                assertTrue(run.steps().getFirst().message().contains("answered 404"),
                        run.steps().getFirst().message());
                assertTrue(run.steps().getFirst().message().contains("no such artifact"),
                        "the body says which artifact it did not find: "
                                + run.steps().getFirst().message());
                assertEquals(List.of(), names(dir));
            }
        }

        @Test
        @DisplayName("a successful download replaces what is there")
        void replacesTheOldFile() throws IOException {
            Files.writeString(dir.resolve("api.jar"), "the old release");
            try (StubServer server = StubServer.serving(200, RELEASE)) {
                Run run = run("""
                              - uses: http.download
                                url: %s
                                to: %s
                                checksum: "sha256:%s"
                        """.formatted(server.url("/api.jar"), at("api.jar"), RELEASE_SHA));

                assertEquals(Run.Status.SUCCESS, run.status(), run.message());
                assertEquals(RELEASE, Files.readString(dir.resolve("api.jar")));
            }
        }

        @Test
        @DisplayName("headers are sent, which is how a private artifact is fetched")
        void sendsHeaders() {
            try (StubServer server = StubServer.serving(200, ARTIFACT)) {
                assertEquals(Run.Status.SUCCESS, run("""
                              - uses: http.download
                                url: %s
                                to: %s
                                headers:
                                  Authorization: Bearer hunter2
                        """.formatted(server.url("/api.jar"), at("api.jar"))).status());

                StubServer.Received request = server.received().getFirst();
                assertEquals(List.of("Bearer hunter2"), request.headers().get("Authorization"));
                assertEquals("GET", request.method());
            }
        }

        @Test
        @DisplayName("mkdirs makes the release directory on the way")
        void makesTheDirectory() {
            try (StubServer server = StubServer.serving(200, ARTIFACT)) {
                assertEquals(Run.Status.SUCCESS, run("""
                              - uses: http.download
                                url: %s
                                to: %s
                                mkdirs: true
                        """.formatted(server.url("/api.jar"),
                        at("releases/1.2.4/api.jar"))).status());

                assertTrue(Files.isRegularFile(dir.resolve("releases/1.2.4/api.jar")));
            }
        }

        @Test
        @DisplayName("the plan says what it would fetch and what it would check")
        void describesTheDownload() {
            List<String> body = plan("""
                          - uses: http.download
                            url: https://artifacts.example/api-1.2.4.jar
                            to: %s
                            checksum: "sha256:%s"
                            mode: "0640"
                            owner: app
                            mkdirs: true
                    """.formatted(at("releases/1.2.4/api.jar"), ARTIFACT_SHA))
                    .steps().getFirst().body();

            assertEquals("would fetch  https://artifacts.example/api-1.2.4.jar", body.get(0));
            assertEquals("      to     " + at("releases/1.2.4/api.jar"), body.get(1));
            assertEquals("      checking sha256, mode 0640, owner app, creating 2 parent directories",
                    body.get(2));
        }

        @Test
        @DisplayName("preflight names a checksum this step cannot check")
        void preflightWarnsOnAnUncheckableChecksum() {
            assertEquals(List.of("checksum names md5, and sha256 is the one this step can check"),
                    plan("""
                                  - uses: http.download
                                    url: https://artifacts.example/api.jar
                                    to: %s
                                    checksum: "md5:d41d8cd98f00b204e9800998ecf8427e"
                            """.formatted(at("api.jar"))).steps().getFirst().warnings());
        }

        @Test
        @DisplayName("a directory where the file goes fails rather than writing into it")
        void refusesADirectoryAsDestination() throws IOException {
            Files.createDirectories(dir.resolve("releases"));
            Run run = run("""
                          - uses: http.download
                            url: https://artifacts.example/api.jar
                            to: %s
                    """.formatted(at("releases")));

            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(run.steps().getFirst().message().startsWith("to: is a directory"),
                    run.steps().getFirst().message());
        }

        /**
         * What is in a directory, so a test can say that nothing else was left in it.
         */
        private List<String> names(Path directory) throws IOException {
            try (Stream<Path> entries = Files.list(directory)) {
                return entries.map(p -> p.getFileName().toString()).sorted().toList();
            }
        }
    }
}
