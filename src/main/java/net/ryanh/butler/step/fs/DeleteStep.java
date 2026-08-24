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
 * Deletes one named path.
 *
 * <p>A directory with anything in it needs {@code recursive: true}, so a path that turns out to be
 * more than the author expected stops the run rather than taking a tree with it. A symlink is
 * removed as the link it is, leaving its target alone.
 *
 * <p>Deleting what is not there succeeds and reports {@code deleted: false}: cleanup runs after
 * work that may not have got far enough to leave anything behind.
 */
public final class DeleteStep implements StepType<DeleteStep.Config> {

    /**
     * @param recursive permission to delete a directory that is not empty, and everything under it
     */
    public record Config(Path path, boolean recursive) {
    }

    @Override
    public String name() {
        return "fs.delete";
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
        return "Delete a file, a symlink, or a directory";
    }

    @Override
    public List<String> locals() {
        return List.of("path", "deleted");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        String problem = problem(c);
        if (problem != null) {
            return StepResult.failed(problem);
        }
        Path path = c.path();
        String shown = Literals.path(path);

        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return StepResult.ok()
                    .output("path", shown)
                    .output("deleted", false)
                    .message("nothing to delete: " + shown);
        }
        String refusal = refusal(c);
        if (refusal != null) {
            return StepResult.failed(refusal);
        }
        Fs.delete(path);
        return StepResult.ok().output("path", shown).output("deleted", true);
    }

    /**
     * @return why this step cannot run whatever is on disk, or null
     */
    private static String problem(Config c) {
        if (c.path() == null || c.path().toString().isBlank()) {
            return "fs.delete needs a path:";
        }
        // An unset var can resolve to nothing, and "/" or "C:\" is where that lands.
        Path absolute = c.path().toAbsolutePath().normalize();
        if (absolute.getParent() == null) {
            return "refusing to delete the root of a filesystem: " + Literals.path(absolute);
        }
        return null;
    }

    /**
     * @return why the path on disk may not be deleted as configured, or null
     */
    private static String refusal(Config c) throws IOException {
        Path path = c.path();
        if (c.recursive() || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || Fs.isEmptyDirectory(path)) {
            return null;
        }
        return Literals.path(path) + " is a directory with things in it; "
                + "set recursive: true to delete it and everything under it";
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        String problem = problem(c);
        if (problem != null) {
            return "would fail: " + problem;
        }
        Path path = c.path();
        String shown = Literals.path(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return "would delete nothing: there is no " + shown;
        }
        try {
            String refusal = refusal(c);
            if (refusal != null) {
                return "would fail: " + refusal;
            }
            if (Files.isSymbolicLink(path)) {
                return "would delete " + shown + "\n      the link itself, not what it points at";
            }
            if (!Files.isDirectory(path)) {
                return "would delete " + shown;
            }
            // What is about to go is the thing worth reading twice before a run for real.
            long entries = Fs.entryCount(path) - 1;
            return "would delete " + shown + "\n      a directory holding " + entries
                    + (entries == 1 ? " entry" : " entries");
        } catch (IOException e) {
            // The name only: the message carries a path in the platform's spelling.
            return "would delete " + shown
                    + "\n      cannot be read yet: " + e.getClass().getSimpleName();
        }
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (problem(c) != null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        try {
            String refusal = refusal(c);
            if (refusal != null) {
                warnings.add(refusal);
            }
        } catch (IOException e) {
            warnings.add("cannot be read: " + Literals.path(c.path()));
        }
        Path parent = c.path().toAbsolutePath().getParent();
        if (Files.exists(c.path(), LinkOption.NOFOLLOW_LINKS)
                && parent != null && !Files.isWritable(parent)) {
            warnings.add("the directory it is in is not writable: " + Literals.path(parent));
        }
        return List.copyOf(warnings);
    }
}
