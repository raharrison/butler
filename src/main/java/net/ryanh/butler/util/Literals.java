package net.ryanh.butler.util;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Renders a value the way it would be written in a config or an expression.
 *
 * <p>Used wherever a resolved value is shown back to the author - dry-run output, the explained
 * form of a condition, expression error messages - so a string always arrives quoted and a
 * duration always arrives in the one duration syntax.
 */
public final class Literals {

    private Literals() {
    }

    public static String of(Object value) {
        return switch (value) {
            case null -> "null";
            case String s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            default -> text(value);
        };
    }

    /**
     * The same value unquoted: what interpolating {@code ${...}} produces, and what a value with
     * no JSON form of its own is persisted as.
     *
     * <p>One renderer rather than two, so a duration cannot be {@code 30s} in a run report and
     * {@code PT30S} in the state file the next run reads back.
     */
    public static String text(Object value) {
        return switch (value) {
            case null -> null;
            case Duration d -> Durations.format(d);
            case Path p -> path(p);
            default -> String.valueOf(value);
        };
    }

    /**
     * A path as the config wrote it. Butler runs on Linux hosts, so a path shown to the author
     * reads with forward slashes whatever separator the machine rendering it prefers.
     */
    public static String path(Path p) {
        return p.toString().replace('\\', '/');
    }
}
