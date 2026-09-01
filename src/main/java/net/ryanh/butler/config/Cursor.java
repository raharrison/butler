package net.ryanh.butler.config;

import net.ryanh.butler.util.Durations;
import net.ryanh.butler.util.Suggestions;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * A typed reader over one YAML mapping that records diagnostics instead of throwing, and
 * remembers which keys it was asked for.
 *
 * <p>That last part is what makes unknown-key detection free: after reading everything a schema
 * knows about, whatever is left over is by definition a key the schema does not know, and the
 * keys that <em>were</em> asked for are exactly the candidate set for a "did you mean" hint.
 */
public final class Cursor {

    private final Map<String, Object> map;
    private final String path;
    private final Diagnostics diags;
    private final Set<String> asked = new LinkedHashSet<>();

    public Cursor(Map<String, Object> map, String path, Diagnostics diags) {
        this.map = map == null ? Map.of() : map;
        this.path = path;
        this.diags = diags;
    }

    public String path() {
        return path;
    }

    public Diagnostics diagnostics() {
        return diags;
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    /**
     * Marks a key as read by something other than this walk, so it is neither reported as unknown
     * nor missing from the did-you-mean candidates. {@code include:} is the one such key: it is
     * resolved before the document it appears in is walked.
     */
    public void skip(String key) {
        asked.add(key);
    }

    private Object raw(String key) {
        asked.add(key);
        return map.get(key);
    }

    private String child(String key) {
        return path + "/" + key;
    }

    // ---------------------------------------------------------------- scalars

    public String string(String key, String fallback) {
        Object v = raw(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Map || v instanceof List) {
            diags.error(child(key), "expected text, found " + kindOf(v));
            return fallback;
        }
        return String.valueOf(v);
    }

    public String requiredString(String key) {
        Object v = raw(key);
        if (v == null) {
            diags.error(path, "missing required key \"" + key + "\"");
            return null;
        }
        return string(key, null);
    }

    /**
     * One name or a list of them, at least one.
     */
    public List<String> requiredStrings(String key) {
        if (raw(key) == null) {
            diags.error(path, "missing required key \"" + key + "\"");
            return List.of();
        }
        List<String> values = strings(key);
        if (values.isEmpty() && !diags.hasErrorAt(child(key))) {
            diags.error(child(key), "must name at least one");
        }
        return values;
    }

    public Integer integer(String key, Integer fallback) {
        Object v = raw(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            diags.error(child(key), "expected a whole number, found \"" + v + "\"");
            return fallback;
        }
    }

    public Boolean bool(String key, Boolean fallback) {
        Object v = raw(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("false")) {
            return Boolean.parseBoolean(s);
        }
        diags.error(child(key), "expected true or false, found \"" + v + "\"");
        return fallback;
    }

    /**
     * A filesystem path. Parsed here rather than by the caller so that text no filesystem can
     * name is a diagnostic like any other, instead of an exception escaping a pass that is
     * supposed to collect every problem at once.
     */
    public Path path(String key, Path fallback) {
        String text = string(key, null);
        if (text == null) {
            return fallback;
        }
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            diags.error(child(key), "not a usable path: \"" + text + "\" (" + e.getReason() + ")");
            return fallback;
        }
    }

    /**
     * One path, or a list of them.
     */
    public List<Path> paths(String key) {
        boolean list = map.get(key) instanceof List;
        List<String> values = strings(key);
        List<Path> out = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            String text = values.get(i);
            try {
                out.add(Path.of(text));
            } catch (InvalidPathException e) {
                diags.error(list ? child(key) + "/" + i : child(key),
                        "not a usable path: \"" + text + "\" (" + e.getReason() + ")");
            }
        }
        return List.copyOf(out);
    }

    public Duration duration(String key, Duration fallback) {
        Object v = raw(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Durations.parse(String.valueOf(v));
        } catch (IllegalArgumentException e) {
            diags.error(child(key), e.getMessage());
            return fallback;
        }
    }

    public <E extends Enum<E>> E enumValue(String key, Class<E> type, E fallback) {
        Object v = raw(key);
        if (v == null) {
            return fallback;
        }
        E value = constant(String.valueOf(v), type, child(key));
        return value == null ? fallback : value;
    }

    /**
     * One constant of {@code type} or a list of them. Empty when the key is absent.
     */
    public <E extends Enum<E>> List<E> enumValues(String key, Class<E> type) {
        boolean list = raw(key) instanceof List;
        List<String> texts = strings(key);
        List<E> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            E value = constant(texts.get(i), type, list ? child(key) + "/" + i : child(key));
            if (value != null) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    /**
     * @return null after reporting, when the text names no constant of {@code type}
     */
    private <E extends Enum<E>> E constant(String text, Class<E> type, String at) {
        String name = text.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E c : type.getEnumConstants()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        List<String> allowed = new ArrayList<>();
        for (E c : type.getEnumConstants()) {
            allowed.add(c.name().toLowerCase(Locale.ROOT));
        }
        diags.error(at, "expected one of " + String.join(", ", allowed)
                + ", found \"" + text + "\"" + Suggestions.from(text, allowed));
        return null;
    }

    // ------------------------------------------------------------- containers

    @SuppressWarnings("unchecked")
    public Cursor object(String key) {
        Object v = raw(key);
        if (v == null) {
            return new Cursor(Map.of(), child(key), diags);
        }
        if (!(v instanceof Map)) {
            diags.error(child(key), "expected a mapping, found " + kindOf(v));
            return new Cursor(Map.of(), child(key), diags);
        }
        return new Cursor((Map<String, Object>) v, child(key), diags);
    }

    /**
     * A list of mappings, each as its own cursor with an indexed path.
     */
    @SuppressWarnings("unchecked")
    public List<Cursor> objects(String key) {
        Object v = raw(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            diags.error(child(key), "expected a list, found " + kindOf(v));
            return List.of();
        }
        List<Cursor> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String itemPath = child(key) + "/" + i;
            if (item instanceof Map) {
                out.add(new Cursor((Map<String, Object>) item, itemPath, diags));
            } else {
                diags.error(itemPath, "expected a mapping, found " + kindOf(item));
            }
        }
        return out;
    }

    public List<String> strings(String key) {
        Object v = raw(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof String s) {
            return List.of(s);
        }
        if (!(v instanceof List<?> list)) {
            diags.error(child(key), "expected a list, found " + kindOf(v));
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map || item instanceof List) {
                diags.error(child(key) + "/" + i, "expected text, found " + kindOf(item));
            } else {
                out.add(String.valueOf(item));
            }
        }
        return List.copyOf(out);
    }

    /**
     * A mapping of name to scalar, such as {@code env:} or {@code persist:}.
     */
    public Map<String, String> stringMap(String key) {
        Object v = raw(key);
        if (v == null) {
            return Map.of();
        }
        if (!(v instanceof Map<?, ?> m)) {
            diags.error(child(key), "expected a mapping, found " + kindOf(v));
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, val) -> {
            if (val instanceof Map || val instanceof List) {
                diags.error(child(key) + "/" + k, "expected text, found " + kindOf(val));
            } else {
                out.put(String.valueOf(k), String.valueOf(val));
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * A mapping of name to arbitrary value, such as {@code vars:}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> anyMap(String key) {
        Object v = raw(key);
        if (v == null) {
            return Map.of();
        }
        if (!(v instanceof Map)) {
            diags.error(child(key), "expected a mapping, found " + kindOf(v));
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) v));
    }

    /**
     * A mapping of name to mapping, such as {@code jobs:} or {@code notifiers:}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Cursor> namedObjects(String key) {
        Object v = raw(key);
        if (v == null) {
            return Map.of();
        }
        if (!(v instanceof Map<?, ?> m)) {
            diags.error(child(key), "expected a mapping, found " + kindOf(v));
            return Map.of();
        }
        Map<String, Cursor> out = new LinkedHashMap<>();
        m.forEach((k, val) -> {
            String name = String.valueOf(k);
            String p = child(key) + "/" + name;
            if (val instanceof Map) {
                out.put(name, new Cursor((Map<String, Object>) val, p, diags));
            } else {
                diags.error(p, "expected a mapping, found " + kindOf(val));
            }
        });
        return out;
    }

    // ----------------------------------------------------------- leftover keys

    /**
     * Everything not yet read, for schemas whose remainder is meaningful (step params).
     */
    public Map<String, Object> rest() {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (!asked.contains(k)) {
                out.put(k, v);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * Reports every key the schema did not read, suggesting the closest key it did know about.
     */
    public void rejectUnknownKeys() {
        for (String k : map.keySet()) {
            if (!asked.contains(k)) {
                diags.error(child(k), "unknown key \"" + k + "\"" + Suggestions.from(k, asked));
            }
        }
    }

    static String kindOf(Object v) {
        if (v == null) return "nothing";
        if (v instanceof Map) return "a mapping";
        if (v instanceof List) return "a list";
        if (v instanceof Number) return "a number";
        if (v instanceof Boolean) return "true/false";
        return "text";
    }
}
