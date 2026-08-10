package net.ryanh.butler.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semantic version that orders correctly, so {@code 1.10.0} never ranks below {@code 1.9.0}.
 * Leading "v" is tolerated because artifact names routinely carry one.
 */
public final class Semver implements Comparable<Semver> {

    private static final Pattern PATTERN = Pattern.compile(
            "^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$");

    private final int major;
    private final int minor;
    private final int patch;
    private final List<String> prerelease;
    private final String raw;

    private Semver(int major, int minor, int patch, List<String> prerelease, String raw) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
        this.raw = raw;
    }

    /**
     * @throws ExprException if the text is not a recognisable version
     */
    public static Semver parse(String text) {
        if (text == null) {
            throw new ExprException("semver() got null");
        }
        Matcher m = PATTERN.matcher(text.trim());
        if (!m.matches()) {
            throw new ExprException("not a version: \"" + text + "\"");
        }
        List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
        return new Semver(
                component(m.group(1), text),
                component(m.group(2), text),
                component(m.group(3), text),
                pre,
                text);
    }

    /**
     * Parses one numeric component, reporting overflow as an expression error rather than NFE.
     */
    private static int component(String digits, String whole) {
        if (digits == null) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new ExprException(
                    "version component \"" + digits + "\" in \"" + whole + "\" is too large");
        }
    }

    /**
     * Returns null rather than throwing, for callers that treat unparseable input as absent.
     */
    public static Semver tryParse(String text) {
        try {
            return parse(text);
        } catch (ExprException e) {
            return null;
        }
    }

    @Override
    public int compareTo(Semver other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) return c;
        c = Integer.compare(minor, other.minor);
        if (c != 0) return c;
        c = Integer.compare(patch, other.patch);
        if (c != 0) return c;

        // A version with a prerelease ranks below the same version without one.
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0;
        if (prerelease.isEmpty()) return 1;
        if (other.prerelease.isEmpty()) return -1;

        int n = Math.min(prerelease.size(), other.prerelease.size());
        for (int i = 0; i < n; i++) {
            c = compareIdentifier(prerelease.get(i), other.prerelease.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }

    private static int compareIdentifier(String a, String b) {
        Long na = asNumber(a);
        Long nb = asNumber(b);
        if (na != null && nb != null) return Long.compare(na, nb);
        if (na != null) return -1;   // numeric identifiers rank below alphanumeric ones
        if (nb != null) return 1;
        return a.compareTo(b);
    }

    /**
     * The numeric value of an identifier, or null if it is alphanumeric or too long to be one.
     */
    private static Long asNumber(String s) {
        if (s.isEmpty() || s.length() > 18) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return null;
        }
        return Long.valueOf(s);
    }

    /**
     * Prerelease identifiers with numeric ones normalised, so equal versions hash equally:
     * {@code 1.0.0-1} and {@code 1.0.0-01} compare equal and must therefore agree here too.
     */
    private List<Object> normalisedPrerelease() {
        List<Object> out = new ArrayList<>(prerelease.size());
        for (String id : prerelease) {
            Long n = asNumber(id);
            out.add(n == null ? id : n);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Semver s && compareTo(s) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, normalisedPrerelease());
    }

    @Override
    public String toString() {
        return raw;
    }
}
