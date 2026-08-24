package net.ryanh.butler.config;

import java.util.*;

/**
 * Collects every problem in a config rather than stopping at the first.
 *
 * <p>A validator that throws on the first error makes fixing a config an iterative game of
 * whack-a-mole, so nothing here throws.
 *
 * <p>Each problem records the file it is in, since a config may span several.
 */
public final class Diagnostics {

    private final List<Diagnostic> items = new ArrayList<>();
    private final Map<String, SourceMap> sources = new LinkedHashMap<>();
    private String currentFile;
    private SourceMap currentMap = SourceMap.empty();
    private boolean merged;

    /**
     * Begins reading a file; null when the config came from a string.
     */
    public void source(String file, SourceMap map) {
        sources.put(file, map);
        currentFile = file;
        currentMap = map;
    }

    /**
     * No more files: paths are looked up across all of them from here.
     */
    public void merged() {
        merged = true;
    }

    public void error(String path, String message) {
        add(Diagnostic.Severity.ERROR, path, message);
    }

    /**
     * An error whose location is known directly rather than through a path. The parser is the only
     * caller: a document that will not parse has no paths to look up.
     */
    public void errorAt(Diagnostic.Loc loc, String message) {
        items.add(new Diagnostic(Diagnostic.Severity.ERROR, currentFile, "", loc, message));
    }

    public void warn(String path, String message) {
        add(Diagnostic.Severity.WARNING, path, message);
    }

    private void add(Diagnostic.Severity severity, String path, String message) {
        if (!merged) {
            items.add(new Diagnostic(severity, currentFile, path, currentMap.locate(path), message));
            return;
        }
        // Longest known prefix wins. The root, which every file has, falls to the first.
        String p = path == null ? "" : path;
        while (true) {
            for (Map.Entry<String, SourceMap> source : sources.entrySet()) {
                Diagnostic.Loc loc = source.getValue().at(p);
                if (loc != null) {
                    items.add(new Diagnostic(severity, source.getKey(), path, loc, message));
                    return;
                }
            }
            if (p.isEmpty()) {
                break;
            }
            int slash = p.lastIndexOf('/');
            p = slash <= 0 ? "" : p.substring(0, slash);
        }
        String first = sources.isEmpty() ? null : sources.keySet().iterator().next();
        items.add(new Diagnostic(severity, first, path, null, message));
    }

    public List<Diagnostic> all() {
        List<Diagnostic> sorted = new ArrayList<>(items);
        sorted.sort(Comparator
                .comparingInt((Diagnostic d) -> fileOrder(d.file()))
                .thenComparingInt(d -> d.loc() == null ? Integer.MAX_VALUE : d.loc().line())
                .thenComparingInt(d -> d.loc() == null ? 0 : d.loc().col()));
        return List.copyOf(sorted);
    }

    private int fileOrder(String file) {
        int i = 0;
        for (String known : sources.keySet()) {
            if (known == null ? file == null : known.equals(file)) {
                return i;
            }
            i++;
        }
        return Integer.MAX_VALUE;
    }

    public List<Diagnostic> errors() {
        return all().stream().filter(Diagnostic::isError).toList();
    }

    public List<Diagnostic> warnings() {
        return all().stream().filter(d -> !d.isError()).toList();
    }

    public boolean hasErrors() {
        return items.stream().anyMatch(Diagnostic::isError);
    }

    /**
     * Whether an error was already recorded at or below a path. Lets a caller avoid piling a
     * second, less useful message onto one mistake.
     */
    public boolean hasErrorAt(String path) {
        return items.stream()
                .filter(Diagnostic::isError)
                .anyMatch(d -> d.path() != null
                        && (d.path().equals(path) || d.path().startsWith(path + "/")));
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public String render(String fallback) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic d : all()) {
            sb.append(d.render(fallback)).append('\n');
        }
        long errors = errors().size();
        long warnings = warnings().size();
        if (errors > 0 || warnings > 0) {
            sb.append('\n')
                    .append(errors).append(errors == 1 ? " error" : " errors")
                    .append(", ")
                    .append(warnings).append(warnings == 1 ? " warning" : " warnings")
                    .append('\n');
        }
        return sb.toString();
    }
}
