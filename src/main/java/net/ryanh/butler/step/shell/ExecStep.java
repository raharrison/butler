package net.ryanh.butler.step.shell;

import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one program with an explicit argument list and no shell.
 *
 * <p>Preferred over {@code shell.run} wherever an argument comes from an event: a path holding a
 * space is passed through untouched rather than re-split by a shell.
 */
public final class ExecStep implements StepType<ExecStep.Config> {

    public record Config(List<String> argv) {
        public Config {
            argv = argv == null ? List.of() : List.copyOf(argv);
        }
    }

    @Override
    public String name() {
        return "shell.exec";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Run a program with explicit arguments, no shell";
    }

    @Override
    public List<String> locals() {
        return List.of("stdout", "stderr", "exit_code");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws Exception {
        if (c.argv().isEmpty()) {
            return StepResult.failed("shell.exec needs an argv");
        }
        ProcessRunner.Command command = ctx.command().argv(c.argv());
        return StepResult.of(ctx.processes().run(command), c.argv().getFirst());
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.argv().isEmpty()) {
            return "would fail: shell.exec needs an argv";
        }
        ProcessRunner.Command command = ctx.command();
        List<String> lines = new ArrayList<>();
        lines.add("would run " + command.argv(c.argv()).display());
        if (command.workingDir() != null) {
            lines.add("      in " + Literals.path(command.workingDir()));
        }
        if (command.runAs() != null) {
            lines.add("      as " + command.runAs());
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        return c.argv().isEmpty() ? List.of()
                : Programs.preflight(ctx.command().workingDir(), c.argv().getFirst());
    }
}
