package net.ryanh.butler.config;

/**
 * One problem found in a config file.
 *
 * @param path JSON-pointer-ish location in the document, e.g. {@code /jobs/api/steps/2/uses}
 * @param loc  line and column in the source file, or null if it could not be determined
 */
public record Diagnostic(Severity severity, String path, Loc loc, String message) {

    public enum Severity {
        ERROR, WARNING
    }

    public record Loc(int line, int col) {
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /**
     * Renders as {@code file:line:col: error: message} - the form editors and CI understand.
     */
    public String render(String file) {
        StringBuilder sb = new StringBuilder();
        sb.append(file);
        if (loc != null) {
            sb.append(':').append(loc.line()).append(':').append(loc.col());
        }
        sb.append(": ").append(severity == Severity.ERROR ? "error" : "warning").append(": ");
        sb.append(message);
        if (path != null && !path.isEmpty()) {
            sb.append("\n    at ").append(path);
        }
        return sb.toString();
    }
}
