package net.ryanh.butler.runtime;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.config.Diagnostics;
import net.ryanh.butler.config.Secrets;
import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.testing.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The namespaces of DESIGN.md §2.2, and what a run is allowed to add to them.
 */
class ContextTest {

    @TempDir
    Path stateDir;

    private Context context(String yaml, Map<String, Object> facts) {
        StepRegistry steps = StepRegistry.discover();
        ConfigLoader.Result r = Fixture.config(yaml, steps);
        return Context.forPlan(Fixture.environment(r, steps, stateDir),
                r.config().jobs().get("j"), new Event("manual", facts, null), Map.of());
    }

    private static final String JOB = """
            vars:
              root: /srv/apps
              app: demo
            jobs:
              j:
                on: [{uses: manual}]
                vars:
                  releases: ${vars.root}/${vars.app}/releases
                steps: [{uses: control.log, message: hi}]
            """;

    @Test
    @DisplayName("job vars are resolved after global ones and can refer to them")
    void varsResolveInOrder() {
        Context ctx = context(JOB, Map.of());
        assertEquals("/srv/apps/demo/releases", ctx.resolve("${vars.releases}"));
    }

    @Test
    void triggerFactsAreTheEventsFacts() {
        Context ctx = context(JOB, Map.of("version", "1.2.4"));
        assertEquals("1.2.4", ctx.namespace("trigger").get("version"));
        assertEquals("deploying 1.2.4", ctx.resolve("deploying ${trigger.version}"));
    }

    @Test
    @DisplayName("a lone hole keeps its type; anything else is text")
    void resolveValueKeepsTypes() {
        Context ctx = context(JOB, Map.of("count", 5));
        assertEquals(5, ctx.resolveValue("${trigger.count}"));
        assertEquals("5 of them", ctx.resolveValue("${trigger.count} of them"));
    }

    @Test
    void conditionsAreEvaluatedAgainstTheSameNamespaces() {
        Context ctx = context(JOB, Map.of("version", "1.2.4"));
        assertTrue(ctx.evaluate("semver(trigger.version) > semver(\"1.0.0\")"));
        assertEquals("semver(\"1.2.4\") > semver(\"1.0.0\")",
                ctx.decide("semver(trigger.version) > semver(\"1.0.0\")").explained());
    }

    @Test
    @DisplayName("a registered result is visible as steps.<name>.*")
    void registeredResults() {
        Context ctx = context(JOB, Map.of());
        ctx.register("probe", StepResult.ok().output("status", 200L));
        // A step's own "status" wins over the result's, which is what an http probe wants.
        assertEquals("200", ctx.resolve("${steps.probe.status}"));
        assertTrue(ctx.evaluate("steps.probe.ok"));
        assertTrue(ctx.namespace("steps").containsKey("probe"));
    }

    @Test
    void variablesAStepWroteJoinTheVarsNamespace() {
        Context ctx = context(JOB, Map.of());
        ctx.applyVars(Map.of("release_path", "/srv/apps/demo/releases/1.2.4"));
        assertEquals("/srv/apps/demo/releases/1.2.4", ctx.resolve("${vars.release_path}"));
    }

    @Test
    @DisplayName("values only a real run knows are placeholders, so a plan is deterministic")
    void runValuesArePlaceholders() {
        Context ctx = context(JOB, Map.of());
        assertEquals("<duration>", ctx.resolve("${run.duration}"));
        assertEquals("<duration_ms>", ctx.resolve("${run.duration_ms}"));
        assertEquals("j", ctx.resolve("${run.job}"));
        assertTrue(ctx.dryRun());
    }

    @Test
    @DisplayName("run.duration reads as elapsed time, run.duration_ms is the exact figure")
    void outcomeDuration() {
        Context ctx = context(JOB, Map.of());
        ctx.outcome("ok", Duration.ofMillis(1_247_231), null, null);

        assertEquals("20m 47s", ctx.resolve("${run.duration}"));
        assertEquals("1247231", ctx.resolve("${run.duration_ms}"));
        assertTrue(ctx.evaluate("run.duration_ms > 300000"));
    }

    @Test
    @DisplayName("a part-second rounds rather than truncates")
    void outcomeDurationRounds() {
        Context ctx = context(JOB, Map.of());
        ctx.outcome("ok", Duration.ofMillis(1600), null, null);
        assertEquals("2s", ctx.resolve("${run.duration}"));

        ctx.outcome("ok", Duration.ofMillis(400), null, null);
        assertEquals("0s", ctx.resolve("${run.duration}"));
        assertEquals("400", ctx.resolve("${run.duration_ms}"));
    }

    @Test
    @DisplayName("step-injected locals sit over the namespaces, and only for the view that has "
            + "them")
    void locals() {
        Context ctx = context(JOB, Map.of());
        RunContext scoped = ctx.withLocals(Map.of("status", 200L, "json",
                Map.of("version", "1.2.4")));

        assertTrue(scoped.evaluate("status == 200 and json.version == \"1.2.4\""));
        assertEquals("/srv/apps/demo/releases", scoped.resolve("${vars.releases}"),
                "the namespaces are still there underneath");
        assertFalse(ctx.evaluate("exists(status)"), "and the run itself never saw them");
    }

    @Test
    @DisplayName("what discovery observed joins state.*, where a job cannot tell it from what was "
            + "persisted")
    void discoveredState() {
        Context ctx = context(JOB, Map.of());
        ctx.observe("deployed_version", "1.2.3");

        assertTrue(ctx.evaluate("state.deployed_version == \"1.2.3\""));
        assertEquals(Map.of("deployed_version", "1.2.3"), ctx.state());
    }

    @Test
    void secretsComeFromTheFileAndThenTheEnvironment(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("secrets.yaml");
        Files.writeString(file, "SLACK_WEBHOOK: https://hooks.example/abc\n");

        Secrets secrets = Secrets.load(
                new ButlerConfig.SecretsConfig(true, List.of(file)), new Diagnostics());
        assertEquals("https://hooks.example/abc", secrets.get("SLACK_WEBHOOK"));
        assertEquals(System.getenv("PATH"), secrets.get("PATH"));
        assertNull(secrets.get("NOTHING_IS_NAMED_THIS"));
    }

    @Test
    @DisplayName("several secrets files are read in order and merged")
    void secretsFilesMerge(@TempDir Path dir) throws IOException {
        Path shared = dir.resolve("shared.yaml");
        Files.writeString(shared, "SLACK_WEBHOOK: https://hooks.example/abc\n");
        Path api = dir.resolve("api.yaml");
        Files.writeString(api, "API_TOKEN: t-123\n");

        Diagnostics diags = new Diagnostics();
        Secrets secrets = Secrets.load(
                new ButlerConfig.SecretsConfig(false, List.of(shared, api)), diags);
        assertTrue(diags.isEmpty(), diags.render("x"));
        assertEquals("https://hooks.example/abc", secrets.get("SLACK_WEBHOOK"));
        assertEquals("t-123", secrets.get("API_TOKEN"));
    }

    @Test
    @DisplayName("a secret defined in two files is an error, not a silent shadowing")
    void duplicateSecretIsReported(@TempDir Path dir) throws IOException {
        Path first = dir.resolve("first.yaml");
        Files.writeString(first, "API_TOKEN: original\n");
        Path second = dir.resolve("second.yaml");
        Files.writeString(second, "API_TOKEN: replacement\n");

        Diagnostics diags = new Diagnostics();
        Secrets secrets = Secrets.load(
                new ButlerConfig.SecretsConfig(false, List.of(first, second)), diags);
        assertEquals(1, diags.errors().size(), diags.render("x"));
        String message = diags.errors().getFirst().message();
        assertTrue(message.contains("API_TOKEN"), message);
        assertTrue(message.contains("first.yaml"), message);
        assertEquals("original", secrets.get("API_TOKEN"), "the first file is the one kept");
    }

    @Test
    @DisplayName("a secrets file that is named but absent is not an error")
    void missingSecretsFileIsTolerated(@TempDir Path dir) {
        Diagnostics diags = new Diagnostics();
        Secrets secrets = Secrets.load(
                new ButlerConfig.SecretsConfig(false, List.of(dir.resolve("nope.yaml"))), diags);
        assertTrue(diags.isEmpty(), diags.render("x"));
        assertNull(secrets.get("ANYTHING"));
    }
}
