package net.ryanh.butler.util;

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
            case Duration d -> Durations.format(d);
            default -> String.valueOf(value);
        };
    }
}
