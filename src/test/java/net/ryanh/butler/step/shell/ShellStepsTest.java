package net.ryanh.butler.step.shell;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.runtime.*;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.testing.FakeProcessRunner;
import net.ryanh.butler.testing.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the shell steps decide: the argv, the process settings the reserved keys supply, and what
 * a finished process turns into. Starting a process is {@code ProcessRunnerTest}'s job.
 */
class ShellStepsTest {

    @TempDir
    Path stateDir;

    private final FakeProcessRunner processes = new FakeProcessRunner();

    private Run run(String yaml) {
        ConfigLoader.Result result = Fixture.config(yaml, StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return new JobRunner(Fixture.environment(result, StepRegistry.discover(), stateDir,
                processes)).run(result.config().jobs().get("j"),
                new Event("manual", Map.of("version", "1.2.4"), null));
    }

    private Plan plan(String yaml) {
        ConfigLoader.Result result = Fixture.config(yaml, StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return PlanBuilder.build(
                Fixture.environment(result, StepRegistry.discover(), stateDir, processes),
                result.config().jobs().get("j"),
                new Event("manual", Map.of("version", "1.2.4"), null), result.diagnostics());
    }

    @Test
    @DisplayName("shell.run goes through a shell, with the script interpolated")
    void runUsesAShell() {
        run("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: shell.run
                        script: ./bin/migrate --to ${trigger.version}
                """);

        assertEquals(List.of("/bin/sh", "-c", "./bin/migrate --to 1.2.4"),
                processes.last().argv());
    }

    @Test
    @DisplayName("the reserved step keys reach the process: working_dir, env over the job's, "
            + "run_as and the step timeout")
    void processSettings() {
        run("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    env:
                      FROM_JOB: yes
                      SHARED: job
                    steps:
                      - uses: shell.run
                        script: ./deploy
                        working_dir: /srv/apps/${trigger.version}
                        run_as: appuser
                        timeout: 30s
                        env:
                          SHARED: step
                """);

        ProcessRunner.Command command = processes.last();
        assertEquals(Path.of("/srv/apps/1.2.4"), command.workingDir());
        assertEquals("appuser", command.runAs());
        assertEquals(Duration.ofSeconds(30), command.timeout());
        assertEquals("yes", command.env().get("FROM_JOB"));
        assertEquals("step", command.env().get("SHARED"), "the step's env wins over the job's");
    }

    @Test
    @DisplayName("stdout, stderr and exit_code are on the result, whether it worked or not")
    void outputsAreRegistered() {
        processes.replying(0, "all good\n", "a warning\n");
        Run run = run("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: shell.run
                        script: ./check
                        register: check
                      - uses: control.assert
                        that: steps.check.exit_code == 0 and trim(steps.check.stdout) == "all good"
                """);

        assertEquals(Run.Status.SUCCESS, run.status());
    }

    @Test
    void aNonZeroExitFailsTheStepAndSaysWhy() {
        processes.replying(2, "", "cannot open the file\n");
        Run run = run("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - name: Migrate
                        uses: shell.run
                        script: ./bin/migrate
                """);

        assertEquals(Run.Status.FAILED, run.status());
        assertTrue(run.message().contains("exited 2"), run.message());
        assertTrue(run.message().contains("cannot open the file"), run.message());
    }

    @Test
    @DisplayName("shell.exec runs an argv with no shell anywhere near it")
    void execTakesAnArgv() {
        run("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: shell.exec
                        argv:
                          - /usr/bin/systemctl
                          - restart
                          - api-${trigger.version}.service
                """);

        assertEquals(List.of("/usr/bin/systemctl", "restart", "api-1.2.4.service"),
                processes.last().argv());
    }

    @Test
    @DisplayName("a dry run describes the script and never starts it")
    void describesRatherThanRuns() {
        Plan plan = plan("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: shell.run
                        working_dir: /srv/apps/current
                        run_as: appuser
                        script: |
                          set -euo pipefail
                          ./bin/migrate --to ${trigger.version}
                """);

        assertEquals(List.of(
                        "would run /bin/sh -c",
                        "      in /srv/apps/current",
                        "      as appuser",
                        "      | set -euo pipefail",
                        "      | ./bin/migrate --to 1.2.4"),
                plan.steps().getFirst().body());
        assertTrue(processes.commands().isEmpty(), "a dry run starts no process");
    }

    @Test
    @DisplayName("preflight warns about a working directory that is not there")
    void preflightChecksTheWorkingDirectory() {
        Plan plan = plan("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: shell.run
                        working_dir: /nowhere/at/all
                        script: ./deploy
                """);

        assertTrue(plan.steps().getFirst().warnings().stream()
                        .anyMatch(w -> w.contains("working_dir does not exist")),
                plan.steps().getFirst().warnings().toString());
    }

    @Test
    @DisplayName("a step killed for overstaying still reports what the process printed")
    void outputSurvivesATimeout() {
        processes.replying(c -> {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // What ForkingProcessRunner does when the runtime cancels it: kill the tree, keep the
            // output.
            return new ProcessRunner.Completed(-1, "step 1 of 4 done\n", "", Duration.ZERO, true);
        });

        Run run = run("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - name: Migrate
                        uses: shell.run
                        script: ./bin/migrate
                        timeout: 100ms
                        register: migrate
                        continue_on_error: true
                      - name: Read what it managed
                        uses: control.assert
                        that: steps.migrate.stdout contains "step 1 of 4 done"
                """);

        assertEquals(Run.Status.SUCCESS, run.status());
        assertEquals(StepResult.Status.FAILED, run.steps().getFirst().status());
        assertTrue(run.steps().getFirst().message().contains("timed out"),
                run.steps().getFirst().message());
        assertEquals(StepResult.Status.OK, run.steps().getLast().status(),
                "the tail of a killed process is the most useful thing about the failure, so it "
                        + "reaches steps.migrate.* like any other output");
    }

    @Test
    @DisplayName("a shell variable is written $${VAR}, since ${} belongs to the config")
    void shellVariablesAreEscaped() {
        run("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: shell.run
                        script: echo $${HOME}
                """);

        assertEquals("echo ${HOME}", processes.last().argv().get(2));
    }
}
