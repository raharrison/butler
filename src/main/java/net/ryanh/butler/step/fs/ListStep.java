package net.ryanh.butler.step.fs;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lists a directory, ordered and filtered.
 *
 * <p>Entries come back least first, so {@code last} is the greatest under {@code order_by:}.
 */
public final class ListStep implements StepType<ListStep.Config> {

    /**
     * @param match   keep only entries whose name matches this regex
     * @param orderBy how to rank them; by name otherwise
     * @param limit   keep only this many, counting back from the greatest
     */
    public record Config(Path dir, String match, Order orderBy, Integer limit) {
        public Config {
            orderBy = orderBy == null ? Order.NAME : orderBy;
        }
    }

    @Override
    public String name() {
        return "fs.list";
    }

    @Override
    public List<String> required() {
        return List.of("dir");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "List a directory, ordered and filtered";
    }

    @Override
    public List<String> locals() {
        return List.of("entries", "count", "first", "last");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.dir() == null) {
            return StepResult.failed("fs.list needs a dir:");
        }
        if (!Files.isDirectory(c.dir())) {
            return StepResult.failed("not a directory: " + Literals.path(c.dir()));
        }
        List<String> entries = entries(c);
        return StepResult.ok()
                .output("entries", entries)
                .output("count", (long) entries.size())
                .output("first", entries.isEmpty() ? null : entries.getFirst())
                .output("last", entries.isEmpty() ? null : entries.getLast());
    }

    static List<String> entries(Config c) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> listed = Files.list(c.dir())) {
            listed.filter(p -> c.match() == null
                            || p.getFileName().toString().matches(c.match()))
                    .forEach(paths::add);
        }
        paths.sort(c.orderBy().comparator());
        List<String> names = paths.stream().map(p -> p.getFileName().toString()).toList();
        return c.limit() != null && names.size() > c.limit()
                ? List.copyOf(names.subList(names.size() - c.limit(), names.size()))
                : names;
    }

    /**
     * Listing changes nothing, so a dry run reports what a later step would see.
     */
    @Override
    public StepResult simulate(Config c, RunContext ctx) {
        try {
            return execute(c, ctx);
        } catch (IOException e) {
            return StepResult.failed(e.toString());
        }
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.dir() == null) {
            return "would fail: fs.list needs a dir:";
        }
        List<String> lines = new ArrayList<>();
        lines.add("would list   " + Literals.path(c.dir()));
        if (c.match() != null) {
            lines.add("      matching " + c.match());
        }
        if (!Files.isDirectory(c.dir())) {
            lines.add("      currently nothing: there is no such directory");
            return String.join("\n", lines);
        }
        try {
            List<String> entries = entries(c);
            lines.add("      currently " + (entries.isEmpty() ? "empty"
                    : String.join(", ", entries)));
        } catch (IOException e) {
            // The name only: the message carries a path in the platform's spelling.
            lines.add("      cannot be listed yet: " + e.getClass().getSimpleName());
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        return c.dir() != null && !Files.isDirectory(c.dir())
                ? List.of("not a directory: " + Literals.path(c.dir()))
                : List.of();
    }
}
