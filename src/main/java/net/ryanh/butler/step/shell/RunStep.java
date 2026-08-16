package net.ryanh.butler.step.shell;

import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a script through a shell: the escape hatch of DESIGN.md §1.
 *
 * <p>It executes with the daemon's privileges unless {@code run_as:} says otherwise, so a config
 * file is as trusted as the daemon itself (DESIGN.md §10.2). The script is interpolated like any
 * other value, so a shell variable is written {@code $${HOME}}.
 */
public final class RunStep implements StepType<RunStep.Config> {

    public record Config(String script, String shell) {
        public Config {
            if (shell == null || shell.isBlank()) {
                shell = "/bin/sh";
            }
            if (script == null) {
                script = "";
            }
        }
    }

    @Override
    public String name() {
        return "shell.run";
    }

    @Override
    public List<String> required() {
        return List.of("script");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Run a script through a shell";
    }

    @Override
    public List<String> locals() {
        return List.of("stdout", "stderr", "exit_code");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws Exception {
        ProcessRunner.Command command = ctx.command().argv(c.shell(), "-c", c.script());
        return StepResult.of(ctx.processes().run(command), c.shell());
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        ProcessRunner.Command command = ctx.command();
        List<String> lines = new ArrayList<>();
        lines.add("would run " + c.shell() + " -c");
        if (command.workingDir() != null) {
            lines.add("      in " + Literals.path(command.workingDir()));
        }
        if (command.runAs() != null) {
            lines.add("      as " + command.runAs());
        }
        for (String line : c.script().stripTrailing().split("\n")) {
            lines.add("      | " + line);
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        return Programs.preflight(ctx.command().workingDir(), c.shell());
    }
}
