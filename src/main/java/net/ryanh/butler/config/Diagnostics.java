package net.ryanh.butler.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Collects every problem in a config rather than stopping at the first.
 *
 * <p>A validator that throws on the first error makes fixing a config an iterative game of
 * whack-a-mole, so nothing here throws.
 */
public final class Diagnostics {

    private final List<Diagnostic> items = new ArrayList<>();
    private SourceMap sourceMap = SourceMap.empty();

    public void sourceMap(SourceMap map) {
        this.sourceMap = map;
    }

    public void error(String path, String message) {
        items.add(new Diagnostic(Diagnostic.Severity.ERROR, path, sourceMap.locate(path), message));
    }

    public void warn(String path, String message) {
        items.add(new Diagnostic(Diagnostic.Severity.WARNING, path, sourceMap.locate(path), message));
    }

    public List<Diagnostic> all() {
        List<Diagnostic> sorted = new ArrayList<>(items);
        sorted.sort(Comparator
                .comparingInt((Diagnostic d) -> d.loc() == null ? Integer.MAX_VALUE : d.loc().line())
                .thenComparingInt(d -> d.loc() == null ? 0 : d.loc().col()));
        return List.copyOf(sorted);
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

    public String render(String file) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic d : all()) {
            sb.append(d.render(file)).append('\n');
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
