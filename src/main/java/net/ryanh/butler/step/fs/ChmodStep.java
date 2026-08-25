package net.ryanh.butler.step.fs;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sets the mode of a path that already exists, for the cases the writing step could not: a tree
 * that arrived from {@code fs.unpack}, or a file another job put there.
 */
public final class ChmodStep implements StepType<ChmodStep.Config> {

    /**
     * @param recursive apply to everything under {@code path} as well, if it is a directory
     */
    public record Config(Path path, String mode, boolean recursive) {
    }

    @Override
    public String name() {
        return "fs.chmod";
    }

    @Override
    public List<String> required() {
        return List.of("path", "mode");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Set the mode of a path that already exists";
    }

    @Override
    public List<String> locals() {
        return List.of("path", "changed");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        String problem = problem(c);
        if (problem != null) {
            return StepResult.failed(problem);
        }
        List<Path> paths = Fs.tree(c.path(), c.recursive());
        for (Path path : paths) {
            Fs.applyMode(path, c.mode());
        }
        return StepResult.ok()
                .output("path", Literals.path(c.path()))
                .output("changed", (long) paths.size());
    }

    /**
     * @return why this step cannot run, or null
     */
    private static String problem(Config c) {
        if (c.path() == null) {
            return "fs.chmod needs a path:";
        }
        if (!Fs.named(c.mode())) {
            return "fs.chmod needs a mode:";
        }
        if (!Files.exists(c.path(), LinkOption.NOFOLLOW_LINKS)) {
            return "no such path: " + Literals.path(c.path());
        }
        return null;
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.path() == null || !Fs.named(c.mode())) {
            return "would fail: fs.chmod needs a path: and a mode:";
        }
        List<String> lines = new ArrayList<>();
        lines.add("would set    mode " + c.mode() + " on " + Literals.path(c.path()));
        if (c.recursive() && Files.isDirectory(c.path(), LinkOption.NOFOLLOW_LINKS)) {
            lines.add("      and everything under it: " + entries(c.path()));
        }
        return String.join("\n", lines);
    }

    /**
     * How much a recursive change would reach, for the plan to show. Unreadable trees report the
     * problem rather than a count, since preflight already warns about them.
     */
    static String entries(Path dir) {
        try {
            long count = Fs.entryCount(dir) - 1;
            return count + (count == 1 ? " entry" : " entries");
        } catch (IOException e) {
            return "a tree that cannot be walked yet";
        }
    }

    /**
     * The walk is read-only, so a dry run reports the true reach rather than a hole.
     */
    @Override
    public StepResult simulate(Config c, RunContext ctx) {
        if (c.path() == null) {
            return StepResult.ok();
        }
        StepResult result = StepResult.ok().output("path", Literals.path(c.path()));
        try {
            return result.output("changed", (long) Fs.tree(c.path(), c.recursive()).size());
        } catch (IOException e) {
            return result;
        }
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (c.path() == null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>(Fs.modeChecks(c.mode()));
        if (!Files.exists(c.path(), LinkOption.NOFOLLOW_LINKS)) {
            warnings.add("no such path: " + Literals.path(c.path()));
        }
        return List.copyOf(warnings);
    }
}
