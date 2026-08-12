package net.ryanh.butler.step.fs;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Reports whether a path is there and what kind of thing it is.
 *
 * <p>Succeeds either way, because "no" is an answer. A job that wants a missing path to end the
 * run asserts on {@code exists}.
 */
public final class ExistsStep implements StepType<ExistsStep.Config> {

    public record Config(Path path) {
    }

    @Override
    public String name() {
        return "fs.exists";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Report whether a path exists, and what it is";
    }

    @Override
    public List<String> locals() {
        return List.of("exists", "type");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) {
        if (c.path() == null) {
            return StepResult.failed("fs.exists needs a path:");
        }
        return StepResult.ok()
                .output("exists", Files.exists(c.path(), LinkOption.NOFOLLOW_LINKS))
                .output("type", type(c.path()));
    }

    /**
     * Looking changes nothing, so a dry run reports what a later step would see.
     */
    @Override
    public StepResult simulate(Config c, RunContext ctx) {
        return execute(c, ctx);
    }

    private static String type(Path path) {
        if (Files.isSymbolicLink(path)) return "symlink";
        if (Files.isDirectory(path)) return "directory";
        if (Files.isRegularFile(path)) return "file";
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? "other" : "missing";
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.path() == null) {
            return "would fail: fs.exists needs a path:";
        }
        return "would check  " + Literals.path(c.path()) + "\n      currently " + type(c.path());
    }
}
