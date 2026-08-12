package net.ryanh.butler.expr;

import net.ryanh.butler.util.Durations;
import net.ryanh.butler.util.Semver;
import net.ryanh.butler.util.Suggestions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The built-in function set.
 */
public final class Functions {

    /**
     * name -> [minArity, maxArity]
     */
    private static final Map<String, int[]> ARITY = new LinkedHashMap<>();

    static {
        ARITY.put("semver", new int[]{1, 1});
        ARITY.put("exists", new int[]{1, 1});
        ARITY.put("default", new int[]{2, 2});
        ARITY.put("len", new int[]{1, 1});
        ARITY.put("int", new int[]{1, 1});
        ARITY.put("lower", new int[]{1, 1});
        ARITY.put("upper", new int[]{1, 1});
        ARITY.put("trim", new int[]{1, 1});
        ARITY.put("basename", new int[]{1, 1});
        ARITY.put("dirname", new int[]{1, 1});
        ARITY.put("match", new int[]{2, 3});
        ARITY.put("file_exists", new int[]{1, 1});
        ARITY.put("now", new int[]{0, 0});
    }

    private Functions() {
    }

    public static boolean exists(String name) {
        return ARITY.containsKey(name);
    }

    static void checkArity(String name, int given) {
        int[] a = ARITY.get(name);
        if (given < a[0] || given > a[1]) {
            String expected = a[0] == a[1]
                    ? String.valueOf(a[0])
                    : a[0] + " to " + a[1];
            throw new ExprException(
                    name + "() takes " + expected + " argument" + (a[1] == 1 ? "" : "s")
                            + ", got " + given);
        }
    }

    /**
     * Appends a " (did you mean ...)" hint, or an empty string if nothing is close.
     */
    static String suggest(String name) {
        String best = Suggestions.closest(name, ARITY.keySet());
        return best == null ? "" : " (did you mean " + best + "?)";
    }

    static Object call(String name, List<Object> args) {
        return switch (name) {
            case "semver" -> semver(args.getFirst());
            case "exists" -> args.getFirst() != null;
            case "default" -> args.getFirst() != null ? args.getFirst() : args.get(1);
            case "len" -> len(args.getFirst());
            case "int" -> toLong(args.getFirst());
            case "lower" -> nullable(args.getFirst(), s -> s.toLowerCase(Locale.ROOT));
            case "upper" -> nullable(args.getFirst(), s -> s.toUpperCase(Locale.ROOT));
            case "trim" -> nullable(args.getFirst(), String::trim);
            case "basename" -> nullable(args.getFirst(), Functions::basename);
            case "dirname" -> nullable(args.getFirst(), Functions::dirname);
            case "match" -> match(args);
            case "file_exists" -> args.getFirst() != null && Files.exists(Path.of(str(args.getFirst())));
            case "now" -> Instant.now();
            default -> throw new ExprException("unknown function \"" + name + "\"");
        };
    }

    /**
     * {@link Semver} reports a bad version as an argument problem, since its other callers have no
     * expression to blame. Here there is one.
     */
    private static Semver semver(Object value) {
        try {
            return Semver.parse(str(value));
        } catch (IllegalArgumentException e) {
            throw new ExprException("semver(): " + e.getMessage());
        }
    }

    private static Object nullable(Object value, Function<String, Object> fn) {
        return value == null ? null : fn.apply(str(value));
    }

    private static Object len(Object v) {
        if (v == null) return 0L;
        if (v instanceof Collection<?> c) return (long) c.size();
        if (v instanceof Map<?, ?> m) return (long) m.size();
        return (long) str(v).length();
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof Duration d) return d.toMillis();
        try {
            return Long.parseLong(str(v).trim());
        } catch (NumberFormatException e) {
            throw new ExprException("int() cannot convert \"" + v + "\"");
        }
    }

    private static Object match(List<Object> args) {
        Object subject = args.getFirst();
        Object regex = args.get(1);
        if (subject == null || regex == null) return null;
        Long group = 0L;
        if (args.size() == 3) {
            group = toLong(args.get(2));
        }
        if (group == null) {
            throw new ExprException("match() group must be a number, not null");
        }
        if (group < 0) {
            throw new ExprException("match() group must not be negative, got " + group);
        }
        Matcher m = compile(str(regex)).matcher(str(subject));
        if (!m.find()) return null;
        if (group > m.groupCount()) {
            throw new ExprException("match() asked for group " + group
                    + " but the pattern has " + m.groupCount());
        }
        return m.group(group.intValue());
    }

    static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ExprException("invalid regex \"" + regex + "\": " + e.getDescription());
        }
    }

    private static String basename(String path) {
        String p = stripTrailingSeparator(path);
        int idx = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return idx < 0 ? p : p.substring(idx + 1);
    }

    private static String dirname(String path) {
        String p = stripTrailingSeparator(path);
        int idx = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return idx < 0 ? "." : (idx == 0 ? "/" : p.substring(0, idx));
    }

    private static String stripTrailingSeparator(String p) {
        int end = p.length();
        while (end > 1 && (p.charAt(end - 1) == '/' || p.charAt(end - 1) == '\\')) {
            end--;
        }
        return p.substring(0, end);
    }

    static String str(Object v) {
        if (v == null) return null;
        if (v instanceof Duration d) return Durations.format(d);
        return String.valueOf(v);
    }

}
