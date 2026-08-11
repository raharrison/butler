package net.ryanh.butler.config;

import net.ryanh.butler.config.model.Enums;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.StepDef;
import net.ryanh.butler.runtime.StepRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = ConfigLoaderTest.class.getResourceAsStream("/configs/" + name)) {
            assertNotNull(in, "missing fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static ConfigLoader.Result loadAndValidate(String yaml) {
        ConfigLoader.Result r = ConfigLoader.parse(yaml);
        ConfigValidator.validate(r.config(), r.diagnostics(),
                StepRegistry.discover().conditionParams());
        return r;
    }

    @Nested
    @DisplayName("the canonical config")
    class Canonical {

        private ConfigLoader.Result result;

        private ConfigLoader.Result load() throws IOException {
            if (result == null) {
                result = loadAndValidate(fixture("canonical.yaml"));
            }
            return result;
        }

        @Test
        void validatesWithNoErrorsAndNoWarnings() throws IOException {
            var r = load();
            assertEquals("", r.diagnostics().render("canonical.yaml"),
                    "the acceptance config must stay clean");
            assertTrue(r.ok());
        }

        @Test
        void settingsAreRead() throws IOException {
            var s = load().config().settings();
            assertEquals("/var/lib/butler", s.stateDir().toString().replace('\\', '/'));
            assertEquals(Enums.LogFormat.JSON, s.logFormat());
            assertEquals(4, s.maxConcurrentRuns());
            assertEquals(Duration.ofSeconds(5), s.pollInterval());
            assertEquals(200, s.runRetention().count());
            assertEquals(Duration.ofDays(30), s.runRetention().age());
        }

        @Test
        void jobStructure() throws IOException {
            JobDef job = load().config().jobs().get("api");
            assertNotNull(job);
            assertEquals(1, job.on().size());
            assertEquals("file.appeared", job.on().getFirst().uses());
            assertEquals(2, job.discover().size());
            assertEquals(5, job.steps().size());
            assertEquals(2, job.onFailure().size());
            assertEquals(Duration.ofMinutes(10), job.timeout());
            assertEquals(2, job.persist().size());
        }

        @Test
        void reservedKeysAreLiftedAndParamsAreLeftRaw() throws IOException {
            JobDef job = load().config().jobs().get("api");
            StepDef symlink = job.steps().get(1);

            assertEquals("Point current at the new release", symlink.name());
            assertEquals("fs.symlink", symlink.uses());
            assertEquals("symlink", symlink.register());

            // Reserved keys must not leak into the params the step type binds.
            assertEquals(Set.of("link", "target", "atomic"), symlink.params().keySet());
        }

        @Test
        void continueOnErrorAndRetryDefaults() throws IOException {
            JobDef job = load().config().jobs().get("api");
            assertTrue(job.steps().get(4).continueOnError());
            assertFalse(job.steps().getFirst().continueOnError());
            assertNull(job.steps().getFirst().retry());
        }

        @Test
        void keyOrderIsPreserved() throws IOException {
            // Keys keep source order, which the plan renderer's snapshot tests depend on.
            // Ordered map implementations only: Map.copyOf leaves iteration order unspecified.
            var trigger = load().config().jobs().get("api").on().getFirst();
            assertEquals(List.of("dir", "match", "settle", "order_by", "on_startup"),
                    List.copyOf(trigger.params().keySet()));

            StepDef copy = load().config().jobs().get("api").steps().getFirst();
            assertEquals(List.of("from", "to", "mode", "mkdirs"), List.copyOf(copy.params().keySet()));

            assertEquals(List.of("deployed_version", "current_release"),
                    List.copyOf(load().config().jobs().get("api").persist().keySet()));
        }

        @Test
        void triggerParamsIncludeTheRegex() throws IOException {
            var trigger = load().config().jobs().get("api").on().getFirst();
            assertEquals("api-(?<version>\\d+\\.\\d+\\.\\d+)\\.jar", trigger.params().get("match"));
            assertEquals("latest", trigger.params().get("on_startup"));
        }

        @Test
        void notifyPolicy() throws IOException {
            var n = load().config().jobs().get("api").notifyPolicy();
            assertEquals("ops", n.to());
            assertEquals(List.of(Enums.Outcome.SUCCESS, Enums.Outcome.FAILURE), n.on());
            assertTrue(n.messages().get("success").contains("deployed"));
        }
    }

    @Nested
    @DisplayName("minimum viable config")
    class Minimal {

        @Test
        void onlyOnAndStepsAreRequired() {
            var r = loadAndValidate("""
                    jobs:
                      hello:
                        on:
                          - uses: manual
                        steps:
                          - uses: control.log
                            message: hi
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
            JobDef job = r.config().jobs().get("hello");
            assertEquals("hello", job.concurrency().group(), "group defaults to the job name");
            assertEquals(Enums.ConcurrencyMode.QUEUE, job.concurrency().mode());
            assertTrue(job.concurrency().queueNewestOnly());
        }

        @Test
        void defaultsAreFilledIn() {
            var r = loadAndValidate("""
                    jobs:
                      hello:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                    """);
            var s = r.config().settings();
            assertEquals(Enums.LogFormat.JSON, s.logFormat());
            assertEquals(4, s.maxConcurrentRuns());
            assertTrue(r.config().secrets().fromEnv());
        }
    }

    @Nested
    @DisplayName("durations")
    class Durations {

        @Test
        void everyUnitParses() {
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        timeout: 90s
                        steps:
                          - uses: control.sleep
                            timeout: 500ms
                          - uses: control.sleep
                            timeout: 2h
                          - uses: control.sleep
                            timeout: 3d
                          - uses: control.sleep
                            timeout: 15m
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
            var steps = r.config().jobs().get("j").steps();
            assertEquals(Duration.ofMillis(500), steps.get(0).timeout());
            assertEquals(Duration.ofHours(2), steps.get(1).timeout());
            assertEquals(Duration.ofDays(3), steps.get(2).timeout());
            assertEquals(Duration.ofMinutes(15), steps.get(3).timeout());
        }

        @Test
        void bareNumberIsRejectedWithAUsefulMessage() {
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        timeout: 30
                        steps: [{uses: control.log}]
                    """);
            assertTrue(r.diagnostics().hasErrors());
            String out = r.diagnostics().render("x");
            assertTrue(out.contains("needs a unit"), out);
            assertTrue(out.contains("30s"), out);
        }
    }
}
