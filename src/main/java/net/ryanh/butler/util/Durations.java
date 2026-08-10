package net.ryanh.butler.util;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one duration syntax in Butler: {@code \d+(ms|s|m|h|d)}.
 *
 * <p>Used by both the config scalar type and the expression language's duration literal, so there
 * is exactly one form to learn and one place it can drift.
 */
public final class Durations {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h|d)$");

    private static final Map<String, Duration> UNITS = new LinkedHashMap<>();

    static {
        UNITS.put("ms", Duration.ofMillis(1));
        UNITS.put("s", Duration.ofSeconds(1));
        UNITS.put("m", Duration.ofMinutes(1));
        UNITS.put("h", Duration.ofHours(1));
        UNITS.put("d", Duration.ofDays(1));
    }

    private Durations() {
    }

    public static boolean isUnit(String unit) {
        return UNITS.containsKey(unit);
    }

    /**
     * @throws IllegalArgumentException for an unknown unit, or an amount that overflows
     *                                  {@link Duration}. Overflow is reported this way rather than as the
     *                                  {@code ArithmeticException} {@code multipliedBy} raises, so that callers
     *                                  collecting diagnostics catch it with everything else.
     */
    public static Duration of(long amount, String unit) {
        Duration base = UNITS.get(unit);
        if (base == null) {
            throw new IllegalArgumentException("unknown duration unit: " + unit);
        }
        try {
            return base.multipliedBy(amount);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "duration " + amount + unit + " is too large");
        }
    }

    /**
     * Parses a duration.
     *
     * @throws IllegalArgumentException with a message naming the expected form. A bare number is
     *                                  called out specifically: {@code timeout: 30} is a plausible mistake and must never
     *                                  silently mean 30ms or 30s.
     */
    public static Duration parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("expected a duration like 30s, got nothing");
        }
        String trimmed = text.trim();
        Matcher m = PATTERN.matcher(trimmed);
        if (m.matches()) {
            try {
                return of(Long.parseLong(m.group(1)), m.group(2));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("duration \"" + trimmed + "\" is too large");
            }
        }
        if (trimmed.matches("\\d+")) {
            throw new IllegalArgumentException(
                    "duration \"" + trimmed + "\" needs a unit: use " + trimmed + "s, "
                            + trimmed + "m, " + trimmed + "h, " + trimmed + "d or "
                            + trimmed + "ms");
        }
        throw new IllegalArgumentException(
                "not a duration: \"" + trimmed + "\" (expected a number followed by ms, s, m, h or d, e.g. 30s)");
    }

    /**
     * Renders a duration back to the config syntax, choosing the largest exact unit.
     */
    public static String format(Duration d) {
        long ms = d.toMillis();
        if (ms == 0) return "0s";
        if (ms % 86_400_000L == 0) return (ms / 86_400_000L) + "d";
        if (ms % 3_600_000L == 0) return (ms / 3_600_000L) + "h";
        if (ms % 60_000L == 0) return (ms / 60_000L) + "m";
        if (ms % 1_000L == 0) return (ms / 1_000L) + "s";
        return ms + "ms";
    }
}
