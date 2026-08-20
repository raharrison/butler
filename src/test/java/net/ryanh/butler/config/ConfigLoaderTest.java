package net.ryanh.butler.config;

import net.ryanh.butler.config.model.Enums;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.StepDef;
import net.ryanh.butler.runtime.StepRegistry;
import net.ryanh.butler.runtime.TriggerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConfigLoaderTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = ConfigLoaderTest.class.getResourceAsStream("/configs/" + name)) {
            assertNotNull(in, "missing fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static ConfigLoader.Result loadAndValidate(String yaml) {
        return validate(ConfigLoader.parse(yaml));
    }

    static ConfigLoader.Result loadAndValidate(ConfigLoader.Source... files) {
        return validate(ConfigLoader.parse(List.of(files)));
    }

    static ConfigLoader.Source file(String name, String yaml) {
        return new ConfigLoader.Source(name, yaml);
    }

    private static ConfigLoader.Result validate(ConfigLoader.Result r) {
        ConfigValidator.validate(r.config(), r.diagnostics(), Vocabulary.of(
                StepRegistry.discover().vocabulary(), TriggerRegistry.discover().vocabulary()));
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
        @DisplayName("is the DESIGN.md example, so the design document cannot describe a config "
                + "this build would refuse")
        void isTheExampleFromTheDesignDocument() throws IOException {
            Path design = Path.of("docs", "DESIGN.md");
            assumeTrue(Files.isReadable(design),
                    "run from the project root to check the design document");

            assertEquals(withoutComments(firstYamlBlock(Files.readString(design))),
                    withoutComments(fixture("canonical.yaml")),
                    "DESIGN.md §3.2 and the acceptance config have drifted; they are the same "
                            + "config and only one of them is validated");
        }

        /**
         * The §3.2 canonical example, which is the first fenced YAML block in the document.
         */
        private static String firstYamlBlock(String markdown) {
            int start = markdown.indexOf("```yaml\n");
            assertTrue(start >= 0, "DESIGN.md has no YAML example in it");
            int from = start + "```yaml\n".length();
            int end = markdown.indexOf("\n```", from);
            assertTrue(end > from, "DESIGN.md's first YAML example is not closed");
            return markdown.substring(from, end);
        }

        /**
         * The document annotates its example and the fixture does not, so the comparison is of
         * what each one says the config <em>is</em>.
         */
        private static String withoutComments(String yaml) {
            return yaml.lines()
                    .map(line -> line.contains("#") && !line.contains("\"#")
                            ? line.substring(0, line.indexOf('#')).stripTrailing() : line)
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.joining("\n"));
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
            assertEquals(List.of(), r.config().secrets().files());
        }

        @Test
        @DisplayName("secrets: file: takes one file or a list of them")
        void secretsFileIsOneOrMany() {
            var one = loadAndValidate("""
                    secrets:
                      file: /etc/butler/secrets.yaml
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                    """);
            assertFalse(one.diagnostics().hasErrors(), one.diagnostics().render("x"));
            assertEquals(List.of(Path.of("/etc/butler/secrets.yaml")),
                    one.config().secrets().files());

            var many = loadAndValidate("""
                    secrets:
                      file:
                        - /etc/butler/secrets.yaml
                        - /etc/butler/secrets.d/api.yaml
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                    """);
            assertFalse(many.diagnostics().hasErrors(), many.diagnostics().render("x"));
            assertEquals(List.of(Path.of("/etc/butler/secrets.yaml"),
                            Path.of("/etc/butler/secrets.d/api.yaml")),
                    many.config().secrets().files(),
                    "read in the order they were listed");
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

    @Nested
    @DisplayName("a config spread over several files")
    class SeveralFiles {

        private static final String BASE = """
                version: 1
                
                settings:
                  state_dir: /srv/butler
                
                vars:
                  app: demo
                
                notifiers:
                  ops:
                    uses: notify.slack
                    channel: "#deploys"
                """;

        @Test
        @DisplayName("is one config: the files accumulate in the order they were given")
        void filesAccumulate() {
            var r = loadAndValidate(
                    file("base.yaml", BASE),
                    file("deploy.yaml", """
                            jobs:
                              deploy:
                                on: [{uses: manual}]
                                steps: [{uses: control.log, message: "deploying ${vars.app}"}]
                                notify: {to: ops}
                            """),
                    file("backup.yaml", """
                            jobs:
                              backup:
                                on: [{uses: manual}]
                                steps: [{uses: control.log, message: backing up}]
                            """));

            assertEquals("", r.diagnostics().render("x"), "a split config should be as clean");
            assertEquals(List.of("deploy", "backup"), List.copyOf(r.config().jobs().keySet()));
            assertEquals(Path.of("/srv/butler"), r.config().settings().stateDir());
            assertEquals(Set.of("ops"), r.config().notifiers().keySet());
            assertEquals("demo", r.config().vars().get("app"));
        }

        @Test
        @DisplayName("a job may use vars and notifiers another file defines")
        void referencesCrossFiles() {
            var r = loadAndValidate(
                    file("base.yaml", BASE),
                    file("jobs.yaml", """
                            jobs:
                              deploy:
                                on: [{uses: manual}]
                                steps: [{uses: control.log, message: "${vars.app}"}]
                                notify: {to: ops}
                            """));
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
        }

        @Test
        @DisplayName("a problem names the file it is in, not the first file")
        void diagnosticsNameTheirOwnFile() {
            var r = loadAndValidate(
                    file("base.yaml", BASE),
                    file("jobs.yaml", """
                            jobs:
                              deploy:
                                on: [{uses: manual}]
                                tiemout: 30s
                                steps: [{uses: control.log}]
                            """));
            var d = DiagnosticsTest.only(r.diagnostics());
            assertEquals("jobs.yaml", d.file());
            assertEquals(4, d.loc().line());
            assertTrue(d.message().contains("tiemout"), d.message());
        }

        @Test
        @DisplayName("a duplicate job is reported against the file that repeated it")
        void duplicateJob() {
            var r = loadAndValidate(
                    file("first.yaml", """
                            jobs:
                              deploy:
                                on: [{uses: manual}]
                                steps: [{uses: control.log, message: a}]
                            """),
                    file("second.yaml", """
                            jobs:
                              deploy:
                                on: [{uses: manual}]
                                steps: [{uses: control.log, message: b}]
                            """));
            var d = DiagnosticsTest.only(r.diagnostics());
            assertEquals("second.yaml", d.file());
            assertEquals("/jobs/deploy", d.path());
            assertEquals(2, d.loc().line());
            assertTrue(d.message().contains("already defined in first.yaml"), d.message());
            assertEquals("a",
                    r.config().jobs().get("deploy").steps().getFirst().params().get("message"),
                    "the first definition is the one kept");
        }

        @Test
        void duplicateVarAndNotifierAreReportedTheSameWay() {
            var r = loadAndValidate(file("base.yaml", BASE), file("more.yaml", """
                    vars:
                      app: other
                    
                    notifiers:
                      ops:
                        uses: notify.slack
                        channel: "#other"
                    
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log, message: hi}]
                    """));
            var messages = r.diagnostics().errors().stream().map(Diagnostic::message).toList();
            assertEquals(2, messages.size(), r.diagnostics().render("x"));
            assertTrue(messages.getFirst().contains("var \"app\" is already defined in base.yaml"),
                    messages.toString());
            assertTrue(messages.get(1).contains("notifier \"ops\" is already defined in base.yaml"),
                    messages.toString());
        }

        @Test
        @DisplayName("settings: configures the daemon, so it belongs to one file")
        void settingsMayOnlyBeSetOnce() {
            var r = loadAndValidate(file("base.yaml", BASE), file("more.yaml", """
                    settings:
                      state_dir: /var/tmp/other
                    
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log, message: hi}]
                    """));
            var d = DiagnosticsTest.only(r.diagnostics());
            assertEquals("more.yaml", d.file());
            assertEquals("/settings", d.path());
            assertTrue(d.message().contains("already set in base.yaml"), d.message());
        }

        @Test
        @DisplayName("settings: may live in any one of the files")
        void settingsNeedNotBeInTheFirstFile() {
            var r = loadAndValidate(
                    file("jobs.yaml", """
                            jobs:
                              j:
                                on: [{uses: manual}]
                                steps: [{uses: control.log, message: hi}]
                            """),
                    file("settings.yaml", """
                            settings:
                              state_dir: /srv/butler
                              max_concurrent_runs: 9
                            """));
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
            assertEquals(Path.of("/srv/butler"), r.config().settings().stateDir());
            assertEquals(9, r.config().settings().maxConcurrentRuns());
        }

        @Test
        @DisplayName("a file that will not parse does not stop the others being read")
        void oneBadFileStillReportsTheRest() {
            var r = loadAndValidate(
                    file("broken.yaml", """
                            jobs:
                              j:
                                 on: [{uses: manual}]
                                  steps: [{uses: control.log}]
                            """),
                    file("good.yaml", """
                            jobs:
                              other:
                                on: [{uses: manual}]
                                tiemout: 30s
                                steps: [{uses: control.log, message: hi}]
                            """));
            var files = r.diagnostics().errors().stream().map(Diagnostic::file).toList();
            assertEquals(List.of("broken.yaml", "good.yaml"), files, r.diagnostics().render("x"));
            assertNotNull(r.config().jobs().get("other"), "the readable file still loaded");
        }

        @Test
        void noJobsInAnyFileIsReportedOnce() {
            var r = loadAndValidate(file("base.yaml", BASE), file("more.yaml", """
                    vars:
                      other: 1
                    """));
            assertTrue(DiagnosticsTest.only(r.diagnostics()).message().contains("no jobs defined"),
                    r.diagnostics().render("x"));
        }
    }
}
