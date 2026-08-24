package net.ryanh.butler.step.fs;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies a file, creating the directories above it if asked.
 *
 * <p>Takes the mode the copy should end up with rather than leaving it to whatever umask the
 * daemon runs under.
 */
public final class CopyStep implements StepType<CopyStep.Config> {

    /**
     * @param mode      octal permissions for the copy, as the config writes them: {@code "0640"}
     * @param owner     user the copy should belong to, by name
     * @param group     group the copy should belong to, by name
     * @param mkdirs    create the directories above {@code to} if they are missing
     * @param overwrite replace an existing destination; true unless the config says otherwise
     */
    public record Config(Path from, Path to, String mode, String owner, String group,
                         boolean mkdirs, Boolean overwrite) {
        public Config {
            overwrite = overwrite == null || overwrite;
        }
    }

    @Override
    public String name() {
        return "fs.copy";
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
        return "Copy a file, creating parent directories and setting its mode and owner";
    }

    @Override
    public List<String> locals() {
        return List.of("path", "bytes");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.from() == null || c.to() == null) {
            return StepResult.failed("fs.copy needs both from: and to:");
        }
        if (c.mkdirs() && c.to().getParent() != null) {
            Files.createDirectories(c.to().getParent());
        }
        try {
            if (c.overwrite()) {
                Files.copy(c.from(), c.to(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(c.from(), c.to());
            }
        } catch (FileAlreadyExistsException e) {
            return StepResult.failed("already exists and overwrite is false: "
                    + Literals.path(c.to()));
        }
        Fs.applyMode(c.to(), c.mode());
        Fs.applyOwnership(c.to(), c.owner(), c.group());
        return StepResult.ok()
                .output("path", Literals.path(c.to()))
                .output("bytes", Files.size(c.to()));
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.from() == null || c.to() == null) {
            return "would fail: fs.copy needs both from: and to:";
        }
        List<String> lines = new ArrayList<>();
        lines.add("would copy   " + Literals.path(c.from()));
        lines.add("      to     " + Literals.path(c.to()));

        List<String> notes = new ArrayList<>();
        if (Fs.named(c.mode())) {
            notes.add("mode " + c.mode());
        }
        String ownership = Fs.ownership(c.owner(), c.group());
        if (ownership != null) {
            notes.add(ownership);
        }
        int missing = Fs.missingParents(c.to());
        if (c.mkdirs() && missing > 0) {
            notes.add("creating " + Fs.parents(missing));
        }
        if (!c.overwrite()) {
            notes.add("refusing to overwrite");
        }
        if (!notes.isEmpty()) {
            lines.add("      " + String.join(", ", notes));
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (c.from() == null || c.to() == null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>(Fs.transferChecks(c.from(), c.to(), c.mkdirs()));
        if (Fs.named(c.mode())) {
            try {
                Fs.mode(c.mode());
            } catch (IllegalArgumentException e) {
                warnings.add(e.getMessage());
            }
        }
        warnings.addAll(Fs.ownershipChecks(c.owner(), c.group()));
        return List.copyOf(warnings);
    }
}
