package net.ryanh.butler.runtime;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.config.Diagnostics;
import net.ryanh.butler.config.Secrets;
import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.StepResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The namespaces of DESIGN.md §2.2, and what a run is allowed to add to them.
 */
class ContextTest {

    private static Context context(String yaml, Map<String, Object> facts) {
        ConfigLoader.Result r = ConfigLoader.parse(yaml);
        ButlerConfig config = r.config();
        return Context.forPlan(config, config.jobs().get("j"),
                new Event("manual", facts, null), Secrets.none());
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
                ctx.explain("semver(trigger.version) > semver(\"1.0.0\")"));
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
        assertEquals("j", ctx.resolve("${run.job}"));
        assertTrue(ctx.dryRun());
    }

    @Test
    void secretsComeFromTheFileAndThenTheEnvironment(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("secrets.yaml");
        Files.writeString(file, "SLACK_WEBHOOK: https://hooks.example/abc\n");

        Secrets secrets = Secrets.load(
                new ButlerConfig.SecretsConfig(true, file), new Diagnostics());
        assertEquals("https://hooks.example/abc", secrets.get("SLACK_WEBHOOK"));
        assertEquals(System.getenv("PATH"), secrets.get("PATH"));
        assertNull(secrets.get("NOTHING_IS_NAMED_THIS"));
    }

    @Test
    @DisplayName("a secrets file that is named but absent is not an error")
    void missingSecretsFileIsTolerated(@TempDir Path dir) {
        Diagnostics diags = new Diagnostics();
        Secrets secrets = Secrets.load(
                new ButlerConfig.SecretsConfig(false, dir.resolve("nope.yaml")), diags);
        assertTrue(diags.isEmpty(), diags.render("x"));
        assertNull(secrets.get("ANYTHING"));
    }
}
