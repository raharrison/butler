package net.ryanh.butler.config;

import net.ryanh.butler.util.Durations;
import net.ryanh.butler.util.Suggestions;

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
            diags.error(path.isEmpty() ? "" : path, "missing required key \"" + key + "\"");
            return null;
        }
        return string(key, null);
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

    public boolean bool(String key, boolean fallback) {
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
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E c : type.getEnumConstants()) {
            if (c.name().equals(s)) {
                return c;
            }
        }
        List<String> allowed = new ArrayList<>();
        for (E c : type.getEnumConstants()) {
            allowed.add(c.name().toLowerCase(Locale.ROOT));
        }
        diags.error(child(key), "expected one of " + String.join(", ", allowed)
                + ", found \"" + v + "\"" + Suggestions.from(String.valueOf(v), allowed));
        return fallback;
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
     * Most config bugs are typos, so this is the highest-value message in the product.
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
