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
 * Moves a file or directory, creating the directories above the destination if asked.
 *
 * <p>Atomic where both sides are on one filesystem, a copy-and-delete otherwise, as {@code mv} is.
 */
public final class MoveStep implements StepType<MoveStep.Config> {

    public record Config(Path from, Path to, String mode, String owner, String group,
                         boolean mkdirs, Boolean overwrite) {
        public Config {
            overwrite = overwrite == null || overwrite;
        }
    }

    @Override
    public String name() {
        return "fs.move";
    }

    @Override
    public List<String> required() {
        return List.of("from", "to");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Move a file or directory";
    }

    @Override
    public List<String> locals() {
        return List.of("path");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.from() == null || c.to() == null) {
            return StepResult.failed("fs.move needs both from: and to:");
        }
        if (c.mkdirs() && c.to().getParent() != null) {
            Files.createDirectories(c.to().getParent());
        }
        try {
            move(c);
        } catch (FileAlreadyExistsException e) {
            return StepResult.failed("already exists and overwrite is false: "
                    + Literals.path(c.to()));
        }
        Fs.applyMode(c.to(), c.mode());
        Fs.applyOwnership(c.to(), c.owner(), c.group());
        return StepResult.ok().output("path", Literals.path(c.to()));
    }

    private static void move(Config c) throws IOException {
        List<StandardCopyOption> options = new ArrayList<>();
        if (c.overwrite()) {
            options.add(StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            List<StandardCopyOption> atomic = new ArrayList<>(options);
            atomic.add(StandardCopyOption.ATOMIC_MOVE);
            Files.move(c.from(), c.to(), atomic.toArray(StandardCopyOption[]::new));
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(c.from(), c.to(), options.toArray(StandardCopyOption[]::new));
        }
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.from() == null || c.to() == null) {
            return "would fail: fs.move needs both from: and to:";
        }
        List<String> lines = new ArrayList<>();
        lines.add("would move   " + Literals.path(c.from()));
        lines.add("      to     " + Literals.path(c.to()));
        int missing = Fs.missingParents(c.to());
        if (c.mkdirs() && missing > 0) {
            lines.add("      creating " + Fs.parents(missing));
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (c.from() == null || c.to() == null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>(Fs.transferChecks(c.from(), c.to(), c.mkdirs()));
        warnings.addAll(Fs.ownershipChecks(c.owner(), c.group()));
        return List.copyOf(warnings);
    }
}
