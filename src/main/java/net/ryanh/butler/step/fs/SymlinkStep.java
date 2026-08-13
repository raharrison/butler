package net.ryanh.butler.step.fs;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Repoints a symlink, atomically by default, and reports the target it replaced as
 * {@code previous_target}.
 */
public final class SymlinkStep implements StepType<SymlinkStep.Config> {

    public record Config(Path link, Path target, Boolean atomic) {
        public Config {
            atomic = atomic == null || atomic;
        }
    }

    @Override
    public String name() {
        return "fs.symlink";
    }

    @Override
    public List<String> required() {
        return List.of("link", "target");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Point a symlink at a target, atomically";
    }

    @Override
    public List<String> locals() {
        return List.of("previous_target", "link", "target");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.link() == null || c.target() == null) {
            return StepResult.failed("fs.symlink needs both link: and target:");
        }
        Path previous = Fs.linkTarget(c.link());
        if (c.link().getParent() != null) {
            Files.createDirectories(c.link().getParent());
        }

        if (c.atomic()) {
            repointAtomically(c);
        } else {
            Files.deleteIfExists(c.link());
            Files.createSymbolicLink(c.link(), c.target());
        }
        return result(c, previous);
    }

    /**
     * A symlink cannot be repointed in place, so the new one is created under a temporary name in
     * the same directory and moved over the old. Only the move is visible to a reader.
     */
    private static void repointAtomically(Config c) throws IOException {
        Path directory = c.link().getParent() == null ? Path.of(".") : c.link().getParent();
        Path temp = directory.resolve(c.link().getFileName() + ".butler-" + System.nanoTime());
        Files.createSymbolicLink(temp, c.target());
        try {
            try {
                Files.move(temp, c.link(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Still narrower a window than deleting the link and creating it again.
                Files.move(temp, c.link(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static StepResult result(Config c, Path previous) {
        return StepResult.ok()
                .output("previous_target", previous == null ? null : Literals.path(previous))
                .output("link", Literals.path(c.link()))
                .output("target", Literals.path(c.target()));
    }

    /**
     * Reading the link changes nothing, so a dry run reports the real previous target and a
     * rollback step reading {@code previous_target} describes correctly.
     */
    @Override
    public StepResult simulate(Config c, RunContext ctx) {
        return c.link() == null || c.target() == null
                ? StepResult.ok()
                : result(c, Fs.linkTarget(c.link()));
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.link() == null || c.target() == null) {
            return "would fail: fs.symlink needs both link: and target:";
        }
        Path previous = Fs.linkTarget(c.link());
        List<String> lines = new ArrayList<>();
        lines.add("would repoint" + (c.atomic() ? " (atomic) " : " ") + Literals.path(c.link()));
        lines.add("      from   " + (previous == null ? "nothing" : Literals.path(previous)));
        lines.add("      to     " + Literals.path(c.target()));
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (c.link() == null || c.target() == null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        if (!Files.exists(c.target())) {
            warnings.add("target does not exist: " + Literals.path(c.target()));
        }
        if (Files.exists(c.link(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(c.link())) {
            warnings.add("link exists and is not a symlink, so repointing it would replace a real "
                    + "file: " + Literals.path(c.link()));
        }
        return List.copyOf(warnings);
    }
}
