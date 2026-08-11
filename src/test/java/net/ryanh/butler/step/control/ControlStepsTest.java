package net.ryanh.butler.step.control;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.runtime.*;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.testing.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code control.*} vocabulary, through a real run and through a plan, since a step has to
 * explain itself as well as do the work.
 */
class ControlStepsTest {

    @TempDir
    Path stateDir;

    private static final String JOB = """
            jobs:
              j:
                on: [{uses: manual}]
                steps:
            %s
            """;

    private Run run(String steps) {
        ConfigLoader.Result result = config(steps);
        return new JobRunner(Fixture.environment(result, StepRegistry.discover(), stateDir))
                .run(result.config().jobs().get("j"), new Event("manual", Map.of(), null));
    }

    private Plan plan(String steps) {
        ConfigLoader.Result result = config(steps);
        return PlanBuilder.build(Fixture.environment(result, StepRegistry.discover(), stateDir),
                result.config().jobs().get("j"), new Event("manual", Map.of(), null),
                result.diagnostics());
    }

    private ConfigLoader.Result config(String steps) {
        ConfigLoader.Result result = Fixture.config(JOB.formatted(steps), StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return result;
    }

    @Test
    @DisplayName("control.assert passes a true condition and fails a false one")
    void assertions() {
        assertEquals(Run.Status.SUCCESS, run("""
                      - uses: control.assert
                        that: 1 == 1
                """).status());

        Run failed = run("""
                      - name: Check
                        uses: control.assert
                        that: 1 == 2
                        message: one is not two
                """);
        assertEquals(Run.Status.FAILED, failed.status());
        assertEquals("one is not two", failed.message());
    }

    @Test
    @DisplayName("control.assert's condition is parsed, not interpolated")
    void assertionsAreConditionsNotTemplates() {
        // Rendering this first would leave the unparseable text `1.2.4 == "1.2.4"`.
        ConfigLoader.Result result = Fixture.config("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: control.assert
                        that: ${trigger.version} == "1.2.4"
                """, StepRegistry.discover());
        Run run = new JobRunner(Fixture.environment(result, StepRegistry.discover(), stateDir))
                .run(result.config().jobs().get("j"),
                        new Event("manual", Map.of("version", "1.2.4"), null));

        assertEquals(Run.Status.SUCCESS, run.status());
    }

    @Test
    void assertionsExplainThemselvesInAPlan() {
        assertEquals(List.of("would assert  1 == 2", "      currently false"),
                plan("""
                              - uses: control.assert
                                that: 1 == 2
                        """).steps().getFirst().body());
    }

    @Test
    @DisplayName("control.sleep waits, and can be cut short by a timeout")
    void sleeping() {
        Instant started = Instant.now();
        assertEquals(Run.Status.SUCCESS, run("""
                      - uses: control.sleep
                        duration: 150ms
                """).status());
        assertTrue(Duration.between(started, Instant.now()).toMillis() >= 150);

        assertEquals(List.of("would sleep 2m"), plan("""
                      - uses: control.sleep
                        duration: 2m
                """).steps().getFirst().body());
    }

    @Test
    void failingOnPurpose() {
        Run run = run("""
                      - name: Give up
                        uses: control.fail
                        message: this host is not supported
                """);
        assertEquals(Run.Status.FAILED, run.status());
        assertEquals("this host is not supported", run.message());

        assertEquals(List.of("would fail the run: this host is not supported"), plan("""
                      - uses: control.fail
                        message: this host is not supported
                """).steps().getFirst().body());
    }

    @Test
    @DisplayName("control.set writes into vars.* for the steps that follow")
    void settingVariables() {
        Run run = run("""
                      - uses: control.set
                        vars:
                          release: /srv/apps/1.2.4
                      - name: Read it back
                        uses: control.assert
                        that: vars.release == "/srv/apps/1.2.4"
                """);
        assertEquals(Run.Status.SUCCESS, run.status());
        assertEquals(StepResult.Status.OK, run.steps().getLast().status());
    }

    @Test
    @DisplayName("control.log writes the message it was given")
    void logging() {
        assertEquals(List.of("would log [warn] careful"), plan("""
                      - uses: control.log
                        level: warn
                        message: careful
                """).steps().getFirst().body());
        assertEquals(Run.Status.SUCCESS, run("""
                      - uses: control.log
                        message: hello
                """).status());
    }
}
