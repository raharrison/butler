package net.ryanh.butler.runtime;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.config.Diagnostics;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.testing.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanTest {

    /**
     * What the plan is built from, plus everything building it complained about.
     */
    private record Built(Plan plan, Diagnostics diagnostics) {
        String rendered() {
            return PlanRenderer.render(plan);
        }
    }

    @TempDir
    Path stateDir;

    private Built build(String yaml, String job, StepRegistry registry,
                        Map<String, Object> facts) {
        ConfigLoader.Result r = Fixture.config(yaml, registry);
        Plan plan = PlanBuilder.build(Fixture.environment(r, registry, stateDir),
                r.config().jobs().get(job), new Event("manual", facts, null), r.diagnostics());
        return new Built(plan, r.diagnostics());
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = PlanTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing fixture: " + path);
            // Golden files are compared as they were written, whatever the checkout did to them.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    @Nested
    @DisplayName("the rendered plan")
    class Rendered {

        @Test
        @DisplayName("matches the golden file, which pins every describe() at once")
        void matchesTheGoldenFile() throws IOException {
            Built built = build(resource("/configs/plan.yaml"), "deploy",
                    StepRegistry.discover(), Map.of("version", "1.2.4"));

            assertEquals("", built.diagnostics().render("plan.yaml"));
            assertEquals(resource("/plans/deploy.txt"), built.rendered());
        }

        @Test
        @DisplayName("leaves no ${ anywhere: an unexpanded hole is what a dry run exists to catch")
        void everyValueIsResolved() throws IOException {
            Built built = build(resource("/configs/plan.yaml"), "deploy",
                    StepRegistry.discover(), Map.of("version", "1.2.4"));
            assertFalse(built.rendered().contains("${"), built.rendered());
        }

        @Test
        @DisplayName("shows both sides of the job's decision")
        void showsTheDecision() {
            Built built = build("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        when: semver(trigger.version) > semver(default(state.v, "0.0.0"))
                        discover: [{uses: control.log, message: look}]
                        steps: [{uses: control.log, message: go}]
                    """, "j", StepRegistry.discover(), Map.of("version", "2.0.0"));

            assertTrue(built.rendered().contains(
                            "when   semver(\"2.0.0\") > semver(\"0.0.0\")   ->   true"),
                    built.rendered());
        }

        @Test
        @DisplayName("says nothing would run when the job's when is false")
        void whenFalseStopsThePipeline() {
            Built built = build("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        when: trigger.version == "9.9.9"
                        discover: [{uses: control.log, message: look}]
                        steps: [{uses: control.log, message: go}]
                        persist:
                          v: ${trigger.version}
                    """, "j", StepRegistry.discover(), Map.of("version", "1.0.0"));

            String out = built.rendered();
            assertTrue(out.contains("->   false"), out);
            assertTrue(out.contains("not run: the job's when is false"), out);
            assertFalse(out.contains("would log [info] go"), out);
            assertFalse(out.contains("persist"), "a skipped run writes no state:\n" + out);
        }

        @Test
        @DisplayName("a when nobody could evaluate is not a when that came out false")
        void whenUnevaluableSaysSo() {
            Built built = build("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        when: trigger.a < state.missing
                        steps: [{uses: control.log, message: go}]
                    """, "j", StepRegistry.discover(), Map.of("a", "1"));

            String out = built.rendered();
            assertTrue(out.contains("could not be evaluated"), out);
            assertTrue(out.contains("not shown: the job's when could not be evaluated"), out);
            assertFalse(out.contains("when is false"), out);
        }

        @Test
        @DisplayName("keeps what discover observed even when the job's when is false, since it is "
                + "still true of this host")
        void discoverFindingsSurviveASkippedRun() {
            Built built = build("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        discover:
                          - name: Look
                            uses: control.set
                            vars: {observed: 1.2.3}
                            extract:
                              deployed_version: vars.observed
                        when: trigger.version == "9.9.9"
                        steps: [{uses: control.log, message: go}]
                    """, "j", StepRegistry.discover(), Map.of("version", "1.0.0"));

            String out = built.rendered();
            assertTrue(out.contains("not run: the job's when is false"), out);
            assertTrue(out.contains("state.deployed_version = \"1.2.3\""), out);
        }

        @Test
        @DisplayName("promises no notification the run would not send")
        void notifyRespectsTheOutcomesItFiresOn() {
            Built built = build("""
                    notifiers:
                      ops: {uses: notify.slack}
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log, message: go}]
                        notify:
                          to: ops
                          on: [failure]
                          success: ":rocket: this must never be sent"
                    """, "j", StepRegistry.discover(), Map.of());

            assertNull(built.plan().notification(),
                    "the policy fires on failure only, so a successful run notifies nobody");
            assertFalse(built.rendered().contains("notify"), built.rendered());
        }

        @Test
        @DisplayName("collects preflight findings from every section into one list")
        void warningsAreCollected() {
            StepRegistry registry = StepRegistry.of(
                    new FakeStep("test.warn").warning("the unit is not known to systemd"));
            Built built = build("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: First
                            uses: test.warn
                          - name: Second
                            uses: test.warn
                    """, "j", registry, Map.of());

            String out = built.rendered();
            assertTrue(out.contains("  2 warnings"), out);
            assertTrue(out.contains("    step 1      the unit is not known to systemd"), out);
            assertTrue(out.contains("    step 2      the unit is not known to systemd"), out);
        }
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("is lazy per step, so a step can read what an earlier one produced")
        void laterStepsSeeEarlierResults() {
            StepRegistry registry = StepRegistry.of(
                    new FakeStep("test.produce").output("thing", "a-real-value"),
                    new FakeStep("test.echo"));
            Built built = build("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: test.produce
                            register: earlier
                          - uses: test.echo
                            value: ${steps.earlier.thing}
                    """, "j", registry, Map.of());

            assertEquals("", built.diagnostics().render("x"));
            assertEquals(List.of("value = a-real-value"),
                    built.plan().steps().get(1).body());
        }

        @Test
        @DisplayName("carries variables a step set into the steps that follow")
        void varsSetByAStepAreVisible() {
            Built built = build("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.set
                            vars: {greeting: hello}
                          - uses: control.log
                            message: ${vars.greeting} there
                    """, "j", StepRegistry.discover(), Map.of());

            assertEquals(List.of("would log [info] hello there"),
                    built.plan().steps().get(1).body());
        }

        @Test
        @DisplayName("keeps a lone hole's type, so a number stays a number")
        void aLoneHoleKeepsItsType() {
            StepRegistry registry = StepRegistry.of(new FakeStep("test.count"));
            Built built = build("""
                    vars: {keep: 5}
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: test.count
                            count: ${vars.keep}
                    """, "j", registry, Map.of());

            assertEquals("", built.diagnostics().render("x"));
            assertEquals(List.of("count = 5"), built.plan().steps().getFirst().body());
        }
    }

    @Nested
    @DisplayName("problems")
    class Problems {

        @Test
        @DisplayName("a step that leaves a ${ in its own description is reported as a defect")
        void describeMustNotLeakAHole() {
            StepRegistry registry = StepRegistry.of(
                    new FakeStep("test.leaky").description("would use ${vars.whatever}"));
            Built built = build("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: test.leaky}]
                    """, "j", registry, Map.of());

            String reported = built.diagnostics().render("x");
            assertTrue(reported.contains("left an unresolved ${...}"), reported);
            assertTrue(built.rendered().contains("error: test.leaky left an unresolved"),
                    built.rendered());
        }

        @Test
        @DisplayName("a ${ the author asked for is not a leak: $${ escapes one, and a fact can "
                + "carry one")
        void anEscapedHoleIsNotADefect() {
            Built built = build("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            message: "shell wants $${HOME}, and the event brought ${trigger.brace}"
                    """, "j", StepRegistry.discover(), Map.of("brace", "${oops}"));

            assertEquals("", built.diagnostics().render("x"));
            assertEquals(List.of("would log [info] shell wants ${HOME}, and the event brought "
                    + "${oops}"), built.plan().steps().getFirst().body());
        }

        @Test
        @DisplayName("a parameter whose type is wrong once resolved is reported at the step")
        void bindingFailureIsReported() {
            Built built = build("""
                    vars: {shouting: shout}
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            message: hi
                            level: ${vars.shouting}
                    """, "j", StepRegistry.discover(), Map.of());

            String reported = built.diagnostics().render("x");
            assertTrue(reported.contains("shout"), reported);
            assertTrue(reported.contains("/jobs/j/steps/0"), reported);
        }

        @Test
        @DisplayName("nothing is executed, ever")
        void nothingExecutes() {
            // FakeStep.execute throws, so a plan that touched it would fail here rather than
            // quietly having done something.
            StepRegistry registry = StepRegistry.of(new FakeStep("test.explode"));
            Built built = build("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: test.explode}]
                    """, "j", registry, Map.of());

            assertEquals("", built.diagnostics().render("x"));
            assertEquals(1, built.plan().steps().size());
        }
    }

    /**
     * A step that reports whatever the test told it to, and refuses to run.
     */
    private static final class FakeStep implements StepType<FakeStep.Config> {

        public record Config(String value, Integer count) {
        }

        private final String name;
        private String description;
        private final List<String> warnings = new ArrayList<>();
        private final Map<String, Object> outputs = new LinkedHashMap<>();

        FakeStep(String name) {
            this.name = name;
        }

        FakeStep description(String text) {
            this.description = text;
            return this;
        }

        FakeStep warning(String text) {
            warnings.add(text);
            return this;
        }

        FakeStep output(String key, Object value) {
            outputs.put(key, value);
            return this;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Class<Config> configType() {
            return Config.class;
        }

        @Override
        public StepResult execute(Config config, RunContext ctx) {
            throw new AssertionError("a plan must never execute a step");
        }

        @Override
        public String describe(Config c, RunContext ctx) {
            if (description != null) {
                return description;
            }
            if (c.count() != null) {
                return "count = " + c.count();
            }
            return "value = " + c.value();
        }

        @Override
        public List<String> preflight(Config config, RunContext ctx) {
            return warnings;
        }

        @Override
        public StepResult simulate(Config config, RunContext ctx) {
            return StepResult.ok().outputs(outputs);
        }
    }
}
