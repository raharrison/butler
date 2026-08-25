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
 * Sets the owner, the group, or both on a path that already exists.
 *
 * <p>Changing an owner is a privileged operation on Linux, so this step usually wants
 * {@code run_as: root} or a job running as a user that already owns the tree.
 */
public final class ChownStep implements StepType<ChownStep.Config> {

    /**
     * @param recursive apply to everything under {@code path} as well, if it is a directory
     */
    public record Config(Path path, String owner, String group, boolean recursive) {
    }

    @Override
    public String name() {
        return "fs.chown";
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
        return "Set the owner or group of a path that already exists";
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
            Fs.applyOwnership(path, c.owner(), c.group());
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
            return "fs.chown needs a path:";
        }
        if (!Fs.named(c.owner()) && !Fs.named(c.group())) {
            return "fs.chown needs an owner: or a group:, or it would change nothing";
        }
        if (!Files.exists(c.path(), LinkOption.NOFOLLOW_LINKS)) {
            return "no such path: " + Literals.path(c.path());
        }
        return null;
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.path() == null || (!Fs.named(c.owner()) && !Fs.named(c.group()))) {
            return "would fail: fs.chown needs a path: and an owner: or a group:";
        }
        List<String> lines = new ArrayList<>();
        lines.add("would set    " + Fs.ownership(c.owner(), c.group())
                + " on " + Literals.path(c.path()));
        if (c.recursive() && Files.isDirectory(c.path(), LinkOption.NOFOLLOW_LINKS)) {
            lines.add("      and everything under it: " + ChmodStep.entries(c.path()));
        }
        return String.join("\n", lines);
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
        List<String> warnings = new ArrayList<>(Fs.ownershipChecks(c.owner(), c.group()));
        if (!Files.exists(c.path(), LinkOption.NOFOLLOW_LINKS)) {
            warnings.add("no such path: " + Literals.path(c.path()));
        }
        return List.copyOf(warnings);
    }
}
