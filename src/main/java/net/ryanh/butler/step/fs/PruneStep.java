package net.ryanh.butler.step.fs;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Deletes all but the newest entries of a directory.
 *
 * <p>An entry something still points at is kept whatever {@code keep:} works out to, and the step
 * reports that it did. After a rollback by hand the running release is an old one, and deleting it
 * takes the application down.
 */
public final class PruneStep implements StepType<PruneStep.Config> {

    /**
     * @param keep    how many of the greatest entries to leave alone. Boxed because it is
     *                required: a missing number must not read as zero and delete everything
     * @param protect entries never to delete, over and above what a symlink points at
     */
    public record Config(Path dir, Integer keep, Order orderBy, List<Path> protect) {
        public Config {
            orderBy = orderBy == null ? Order.MODIFIED : orderBy;
            protect = protect == null ? List.of() : List.copyOf(protect);
        }
    }

    @Override
    public String name() {
        return "fs.prune";
    }

    @Override
    public List<String> required() {
        return List.of("dir", "keep");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Delete all but the newest entries of a directory";
    }

    @Override
    public List<String> locals() {
        return List.of("deleted", "kept", "protected");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.dir() == null) {
            return StepResult.failed("fs.prune needs a dir:");
        }
        if (c.keep() == null) {
            return StepResult.failed("fs.prune needs a keep:");
        }
        if (!Files.isDirectory(c.dir())) {
            return StepResult.failed("not a directory: " + Literals.path(c.dir()));
        }
        Split split = split(c);
        for (String name : split.deleted()) {
            Fs.delete(c.dir().resolve(name));
        }
        StepResult result = StepResult.ok()
                .output("deleted", split.deleted())
                .output("kept", split.kept())
                .output("protected", split.spared());
        return split.spared().isEmpty() ? result
                : result.message("kept " + String.join(", ", split.spared())
                                 + ": still in use, whatever keep says");
    }

    /**
     * The three ways an entry can end up: deleted, left alone by {@code keep:}, or spared because
     * something still points at it.
     */
    private record Split(List<String> deleted, List<String> kept, List<String> spared) {
    }

    private static Split split(Config c) throws IOException {
        List<String> ordered = ListStep.entries(
                new ListStep.Config(c.dir(), null, c.orderBy(), null));
        Set<Path> inUse = inUse(c);

        List<String> deleted = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> spared = new ArrayList<>();
        int keepFrom = Math.max(0, ordered.size() - Math.max(0, c.keep()));
        for (int i = 0; i < ordered.size(); i++) {
            String name = ordered.get(i);
            if (i >= keepFrom) {
                kept.add(name);
            } else if (inUse.contains(absolute(c.dir().resolve(name)))) {
                spared.add(name);
            } else {
                deleted.add(name);
            }
        }
        return new Split(List.copyOf(deleted), List.copyOf(kept), List.copyOf(spared));
    }

    /**
     * Everything {@code protect:} names, plus the target of every symlink beside the directory: a
     * current-release link is the releases directory's sibling in the layouts this is built for.
     *
     * <p>Absolute paths rather than names, or a link into another tree would spare an entry here
     * that happens to share its name.
     */
    private static Set<Path> inUse(Config c) throws IOException {
        Set<Path> targets = new LinkedHashSet<>();
        for (Path p : c.protect()) {
            // A bare name means an entry of this directory; an absolute path means itself.
            targets.add(absolute(c.dir().resolve(p)));
        }
        Path beside = absolute(c.dir()).getParent();
        if (beside == null || !Files.isDirectory(beside)) {
            return targets;
        }
        try (Stream<Path> siblings = Files.list(beside)) {
            for (Path link : siblings.filter(Files::isSymbolicLink).toList()) {
                Path target = Fs.linkTarget(link);
                if (target != null) {
                    // A relative target is relative to the link's own directory.
                    targets.add(absolute(beside.resolve(target)));
                }
            }
        }
        return targets;
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.dir() == null) {
            return "would fail: fs.prune needs a dir:";
        }
        if (c.keep() == null) {
            return "would fail: fs.prune needs a keep:";
        }
        if (!Files.isDirectory(c.dir())) {
            return "would prune  " + Literals.path(c.dir())
                    + "\n      nothing yet: there is no such directory";
        }
        List<String> lines = new ArrayList<>();
        Split split;
        try {
            split = split(c);
        } catch (IOException e) {
            // The name only: the message carries a path in the platform's spelling.
            return "would prune  " + Literals.path(c.dir())
                    + "\n      cannot be listed yet: " + e.getClass().getSimpleName();
        }
        if (split.deleted().isEmpty()) {
            lines.add("would delete nothing from " + Literals.path(c.dir()));
        }
        for (int i = 0; i < split.deleted().size(); i++) {
            lines.add((i == 0 ? "would delete " : "             ")
                    + Literals.path(c.dir().resolve(split.deleted().get(i))));
        }
        int total = split.deleted().size() + split.kept().size() + split.spared().size();
        lines.add("      keeping the newest " + split.kept().size() + " of " + total);
        for (String name : split.spared()) {
            lines.add("      keeping " + name + " as well: still in use");
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (c.dir() == null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        if (!Files.isDirectory(c.dir())) {
            warnings.add("not a directory: " + Literals.path(c.dir()));
        }
        if (c.keep() != null && c.keep() < 1) {
            warnings.add("keep is " + c.keep() + ", so this would delete every release present");
        }
        return List.copyOf(warnings);
    }
}
