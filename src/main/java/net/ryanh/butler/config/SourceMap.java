package net.ryanh.butler.config;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLParser;

import java.util.*;

/**
 * Maps a document path such as {@code /jobs/api/steps/2/uses} to its position in the source file.
 *
 * <p>Databind does not retain source locations on the objects it binds, so this is a second,
 * streaming pass over the same text using Jackson's own YAML parser. Using one YAML
 * implementation for both passes means the two can never disagree about the document.
 */
public final class SourceMap {

    private final Map<String, Diagnostic.Loc> locations;
    private final List<String> aliases;

    private SourceMap(Map<String, Diagnostic.Loc> locations, List<String> aliases) {
        this.locations = locations;
        this.aliases = aliases;
    }

    public static SourceMap empty() {
        return new SourceMap(Map.of(), List.of());
    }

    public static SourceMap of(String yaml) {
        Map<String, Diagnostic.Loc> out = new HashMap<>();
        List<String> aliases = new ArrayList<>();
        YAMLMapper mapper = YAMLMapper.builder().build();
        try (JsonParser p = mapper.createParser(yaml)) {
            walk(p, out, aliases);
        } catch (RuntimeException e) {
            // A malformed document is reported by the loading pass with a better message; this
            // pass just yields whatever positions it managed to collect.
            return new SourceMap(Map.copyOf(out), List.copyOf(aliases));
        }
        return new SourceMap(Map.copyOf(out), List.copyOf(aliases));
    }

    /**
     * Paths where the document referred to a YAML anchor, in source order.
     *
     * <p>The parser reports an alias as a scalar whose text is the anchor's name, so
     * {@code copy: *base} would bind the string "base" and nobody would ever know. Only this pass
     * can tell the difference, which is why it collects them for the loader to reject.
     */
    public List<String> aliases() {
        return aliases;
    }

    /**
     * @return the position of a path, walking up to the nearest known ancestor, or null
     */
    public Diagnostic.Loc locate(String path) {
        if (path == null) {
            return null;
        }
        String p = path;
        while (true) {
            Diagnostic.Loc loc = locations.get(p);
            if (loc != null) {
                return loc;
            }
            int slash = p.lastIndexOf('/');
            if (slash <= 0) {
                return locations.get("");
            }
            p = p.substring(0, slash);
        }
    }

    private static void walk(JsonParser p, Map<String, Diagnostic.Loc> out, List<String> aliases) {
        Deque<Frame> stack = new ArrayDeque<>();
        String pendingName = null;
        YAMLParser yaml = (YAMLParser) p;

        JsonToken token;
        while ((token = p.nextToken()) != null) {
            if (token == JsonToken.PROPERTY_NAME) {
                pendingName = p.currentName();
                // Record the key's own position: for most diagnostics, pointing at the key is
                // what the reader wants to see.
                record(out, pathOf(stack, pendingName), p);
                continue;
            }

            String path = pathOf(stack, pendingName);
            if (yaml.isCurrentAlias()) {
                aliases.add(path);
            }

            switch (token) {
                case START_OBJECT, START_ARRAY -> {
                    if (!out.containsKey(path)) {
                        record(out, path, p);
                    }
                    stack.push(new Frame(path, token == JsonToken.START_ARRAY));
                    pendingName = null;
                }
                case END_OBJECT, END_ARRAY -> {
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                    pendingName = null;
                }
                default -> {
                    if (!out.containsKey(path)) {
                        record(out, path, p);
                    }
                    bumpIndex(stack);
                    pendingName = null;
                }
            }

            if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                bumpIndex(stack);
            }
        }
    }

    private static void bumpIndex(Deque<Frame> stack) {
        Frame top = stack.peek();
        if (top != null && top.array) {
            top.index++;
        }
    }

    private static String pathOf(Deque<Frame> stack, String pendingName) {
        Frame top = stack.peek();
        if (top == null) {
            return "";
        }
        if (top.array) {
            return top.path + "/" + top.index;
        }
        return pendingName == null ? top.path : top.path + "/" + pendingName;
    }

    private static void record(Map<String, Diagnostic.Loc> out, String path, JsonParser p) {
        var loc = p.currentTokenLocation();
        out.put(path, new Diagnostic.Loc(loc.getLineNr(), loc.getColumnNr()));
    }

    private static final class Frame {
        final String path;
        final boolean array;
        int index;

        Frame(String path, boolean array) {
            this.path = path;
            this.array = array;
        }
    }
}
