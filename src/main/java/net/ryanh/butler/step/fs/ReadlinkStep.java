package net.ryanh.butler.step.fs;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.nio.file.Path;
import java.util.List;

/**
 * Reports what a symlink points at, as {@code value}.
 */
public final class ReadlinkStep implements StepType<ReadlinkStep.Config> {

    public record Config(Path path) {
    }

    @Override
    public String name() {
        return "fs.readlink";
    }

    @Override
    public List<String> required() {
        return List.of("path");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Report what a symlink points at";
    }

    @Override
    public List<String> locals() {
        return List.of("value");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) {
        if (c.path() == null) {
            return StepResult.failed("fs.readlink needs a path:");
        }
        Path target = Fs.linkTarget(c.path());
        // Failing rather than reporting a null value: inside a discover: block that leaves the
        // persisted value standing, which is what "could not be read" should mean.
        return target == null
                ? StepResult.failed("not a symlink: " + Literals.path(c.path()))
                : StepResult.ok().output("value", Literals.path(target));
    }

    /**
     * Reading a link changes nothing, so a dry run reports the real target.
     */
    @Override
    public StepResult simulate(Config c, RunContext ctx) {
        return execute(c, ctx);
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.path() == null) {
            return "would fail: fs.readlink needs a path:";
        }
        Path target = Fs.linkTarget(c.path());
        return "would read   " + Literals.path(c.path()) + "\n      which points at "
                + (target == null ? "nothing: it is not a symlink" : Literals.path(target));
    }
}
