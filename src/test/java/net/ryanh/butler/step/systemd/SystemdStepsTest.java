package net.ryanh.butler.step.systemd;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.runtime.*;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.testing.FakeProcessRunner;
import net.ryanh.butler.testing.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code systemd.*} vocabulary. Nothing forks: what these steps decide is the command they
 * build, which {@code FakeProcessRunner} records.
 */
class SystemdStepsTest {

    @TempDir
    Path stateDir;

    private static final String JOB = """
            jobs:
              j:
                on: [{uses: manual}]
                steps:
            %s
            """;

    private Run run(String steps, FakeProcessRunner processes) {
        ConfigLoader.Result result =
                Fixture.config(JOB.formatted(steps), StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return new JobRunner(Fixture.environment(result, StepRegistry.discover(), stateDir,
                processes)).run(result.config().jobs().get("j"),
                new Event("manual", Map.of(), null));
    }

    private Plan plan(String steps, FakeProcessRunner processes) {
        ConfigLoader.Result result =
                Fixture.config(JOB.formatted(steps), StepRegistry.discover());
        return PlanBuilder.build(Fixture.environment(result, StepRegistry.discover(), stateDir,
                        processes), result.config().jobs().get("j"),
                new Event("manual", Map.of(), null), result.diagnostics());
    }

    private static List<List<String>> argvs(FakeProcessRunner processes) {
        return processes.commands().stream().map(ProcessRunner.Command::argv).toList();
    }

    @Test
    @DisplayName("restart goes through sudo, because the daemon runs unprivileged")
    void restartUsesSudo() {
        FakeProcessRunner processes = new FakeProcessRunner();
        assertEquals(Run.Status.SUCCESS, run("""
                      - uses: systemd.restart
                        unit: api.service
                """, processes).status());

        assertEquals(List.of(List.of("sudo", "systemctl", "restart", "api.service")),
                argvs(processes));
    }

    @Test
    @DisplayName("sudo can be turned off for a user unit")
    void sudoIsOptional() {
        FakeProcessRunner processes = new FakeProcessRunner();
        run("""
                      - uses: systemd.restart
                        unit: api.service
                        sudo: false
                """, processes);

        assertEquals(List.of(List.of("systemctl", "restart", "api.service")), argvs(processes));
    }

    @Test
    @DisplayName("wait_active polls is-active rather than trusting restart to mean it is up")
    void waitsForTheUnitToBecomeActive() {
        // Two "activating" answers, then "active": the window in which a health check run
        // straight after the restart would test the old process.
        AtomicInteger probes = new AtomicInteger();
        FakeProcessRunner processes = new FakeProcessRunner().replying(command -> {
            if (!command.argv().contains("is-active")) {
                return new ProcessRunner.Completed(0, "", "", Duration.ZERO, false);
            }
            String state = probes.incrementAndGet() < 3 ? "activating" : "active";
            return new ProcessRunner.Completed(0, state + "\n", "", Duration.ZERO, false);
        });

        Run run = run("""
                      - uses: systemd.restart
                        unit: api.service
                        wait_active: 10s
                        register: restart
                      - uses: control.assert
                        that: steps.restart.active_state == "active"
                """, processes);

        assertEquals(Run.Status.SUCCESS, run.status(), run.message());
        assertEquals(3, probes.get());
    }

    @Test
    @DisplayName("a unit that never becomes active fails the step, saying what it was instead")
    void givesUpOnAUnitThatNeverComesUp() {
        FakeProcessRunner processes = new FakeProcessRunner().replying(command ->
                new ProcessRunner.Completed(0,
                        command.argv().contains("is-active") ? "failed\n" : "", "",
                        Duration.ZERO, false));

        Run run = run("""
                      - uses: systemd.restart
                        unit: api.service
                        wait_active: 300ms
                """, processes);

        assertEquals(Run.Status.FAILED, run.status());
        assertTrue(run.message().contains("api.service is failed"), run.message());
    }

    @Test
    @DisplayName("a systemctl that exits non-zero fails the step with what it printed")
    void reportsWhatSystemctlSaid() {
        FakeProcessRunner processes =
                new FakeProcessRunner().replying(1, "", "Failed to restart api.service: Unit not found.");
        Run run = run("""
                      - uses: systemd.restart
                        unit: api.service
                """, processes);

        assertEquals(Run.Status.FAILED, run.status());
        assertTrue(run.message().contains("Unit not found"), run.message());
    }

    @Test
    void stopWaitsForInactive() {
        FakeProcessRunner processes = new FakeProcessRunner().replying(command ->
                new ProcessRunner.Completed(0,
                        command.argv().contains("is-active") ? "inactive\n" : "", "",
                        Duration.ZERO, false));

        assertEquals(Run.Status.SUCCESS, run("""
                      - uses: systemd.stop
                        unit: api.service
                        wait_inactive: 5s
                """, processes).status());
        assertTrue(argvs(processes).contains(List.of("sudo", "systemctl", "stop", "api.service")),
                argvs(processes).toString());
    }

    @Test
    @DisplayName("start and reload put the same verb in front of the same unit")
    void startAndReloadUseTheirOwnVerb() {
        FakeProcessRunner processes = new FakeProcessRunner();
        assertEquals(Run.Status.SUCCESS, run("""
                      - uses: systemd.start
                        unit: api.service
                      - uses: systemd.reload
                        unit: api.service
                        sudo: false
                """, processes).status());

        assertEquals(List.of(List.of("sudo", "systemctl", "start", "api.service"),
                        List.of("systemctl", "reload", "api.service")),
                argvs(processes));
    }

    @Test
    @DisplayName("wait_active on its own polls without touching the unit")
    void waitActiveChangesNothingItself() {
        AtomicInteger polls = new AtomicInteger();
        FakeProcessRunner processes = new FakeProcessRunner().replying(command ->
                new ProcessRunner.Completed(0,
                        polls.incrementAndGet() < 2 ? "activating\n" : "active\n", "",
                        Duration.ZERO, false));

        assertEquals(Run.Status.SUCCESS, run("""
                      - uses: systemd.wait_active
                        unit: api.service
                        wait_for: 5s
                """, processes).status());

        assertTrue(argvs(processes).stream()
                        .allMatch(argv -> argv.contains("is-active") && argv.contains("api.service")),
                argvs(processes).toString());
        assertTrue(polls.get() >= 2, "it should have polled until the unit was active");
    }

    @Test
    @DisplayName("status asks systemctl show for the properties it needs and parses the answer")
    void statusReportsTheUnitsState() {
        FakeProcessRunner processes = new FakeProcessRunner().replying(0, """
                LoadState=loaded
                ActiveState=active
                SubState=running
                MainPID=4242
                """, "");

        assertEquals(Run.Status.SUCCESS, run("""
                      - uses: systemd.status
                        unit: api.service
                        register: unit
                      - uses: control.assert
                        that: steps.unit.active_state == "active" and steps.unit.pid == 4242
                      - uses: control.assert
                        that: steps.unit.sub_state == "running"
                """, processes).status());

        // A fake answers whatever it is asked, so only this assertion catches a missing verb:
        // `systemctl --property=X unit` is not a command.
        assertEquals(List.of("systemctl", "show", "--property=LoadState",
                        "--property=ActiveState", "--property=SubState", "--property=MainPID",
                        "api.service"),
                processes.commands().getFirst().argv());
    }

    @Test
    @DisplayName("a unit systemd has never heard of fails status rather than reporting nothing")
    void statusFailsForAnUnknownUnit() {
        FakeProcessRunner processes = new FakeProcessRunner().replying(0, "LoadState=not-found\n", "");
        Run run = run("""
                      - uses: systemd.status
                        unit: nope.service
                """, processes);

        assertEquals(Run.Status.FAILED, run.status());
        assertTrue(run.message().contains("nope.service"), run.message());
    }

    @Test
    @DisplayName("the plan says what it would run and how long it would wait")
    void describesTheRestart() {
        assertEquals(List.of("would run    sudo systemctl restart api.service",
                        "      then wait up to 30s for the unit to become active"),
                plan("""
                              - uses: systemd.restart
                                unit: api.service
                                wait_active: 30s
                        """, new FakeProcessRunner()).steps().getFirst().body());
    }

    @Test
    @DisplayName("preflight warns about a unit systemd has never heard of, and a missing "
            + "sudoers rule")
    void preflightWarnsBeforeThreeInTheMorning() {
        FakeProcessRunner processes = new FakeProcessRunner().replying(command ->
                command.argv().contains("-l")
                        ? new ProcessRunner.Completed(1, "", "not allowed", Duration.ZERO, false)
                        : new ProcessRunner.Completed(0, "LoadState=not-found\n", "",
                        Duration.ZERO, false));

        assertEquals(List.of("no unit named api.service is known to systemd",
                        "no NOPASSWD sudoers rule matches `systemctl restart api.service`"),
                plan("""
                              - uses: systemd.restart
                                unit: api.service
                        """, processes).steps().getFirst().warnings());
    }
}
