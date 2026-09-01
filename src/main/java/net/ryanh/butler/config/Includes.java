package net.ryanh.butler.config;

import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Expands {@code include:} into the ordered list of documents one config is made of, so the set of
 * files belongs to the config rather than to the command line (DESIGN.md §3.4).
 *
 * <p>A file is read immediately after the one that named it, so the list reads top-down. Files are
 * deduped by absolute path, which is also what ends a cycle: an include leading back to a file
 * already read finds it there.
 */
final class Includes {

    private Includes() {
    }

    /**
     * Reads the files given on the command line and everything they include, in order.
     *
     * <p>A root file that cannot be read throws, since the caller named it directly and has
     * already checked it. One named by an {@code include:} is a diagnostic like any other config
     * mistake, reported at the entry that named it.
     */
    static List<ConfigLoader.Document> expand(List<Path> roots, Diagnostics diags)
            throws IOException {
        List<ConfigLoader.Document> documents = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Path root : roots) {
            if (seen.add(key(root))) {
                follow(read(root, Files.readString(root), diags), documents, seen, diags);
            }
        }
        return documents;
    }

    /**
     * Adds one document, then everything it includes, before the caller moves on.
     */
    private static void follow(ConfigLoader.Document doc, List<ConfigLoader.Document> documents,
                               Set<Path> seen, Diagnostics diags) {
        documents.add(doc);
        if (doc.root() == null) {
            return;
        }
        // Read through a Cursor so that a mistyped value, an unusable path and the location of
        // each entry are all reported the way every other key's are.
        List<Path> entries = new Cursor(doc.root(), "", diags).paths("include");
        boolean list = doc.root().get("include") instanceof List;
        Path base = doc.path().getParent();

        for (int i = 0; i < entries.size(); i++) {
            // Reading a child moved the current source; this entry's problems are in this file.
            diags.source(doc.file(), doc.map());
            // With one entry there is no index in the source to point at.
            String at = list ? "/include/" + i : "/include";
            // Normalised, because this path is the name the file's own problems are reported
            // against and "jobs/../shared/vars.yaml" is not how the author would write it.
            Path file = base == null
                    ? entries.get(i).normalize()
                    : base.resolve(entries.get(i)).normalize();
            if (!seen.add(key(file))) {
                continue;
            }
            String yaml = readable(file, at, diags);
            if (yaml != null) {
                follow(read(file, yaml, diags), documents, seen, diags);
            }
        }
    }

    /**
     * The file's text, or null having reported why it could not be had. Unlike a secrets file, an
     * absent one is an error: an include is what the config is made of, not host state.
     */
    private static String readable(Path file, String at, Diagnostics diags) {
        if (!Files.exists(file)) {
            diags.error(at, "no such config file: " + Literals.path(file));
            return null;
        }
        if (Files.isDirectory(file)) {
            diags.error(at, "not a config file: " + Literals.path(file) + " (it is a directory)");
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            diags.error(at, "could not read " + Literals.path(file) + ": " + e.getMessage());
            return null;
        }
    }

    private static ConfigLoader.Document read(Path file, String yaml, Diagnostics diags) {
        return ConfigLoader.open(new ConfigLoader.Source(file.toString(), yaml), file, diags);
    }

    /**
     * What makes two names the same file. Absolute rather than real, because a file is deduped
     * before it is known to exist and {@code toRealPath} throws when it does not.
     */
    private static Path key(Path file) {
        return file.toAbsolutePath().normalize();
    }
}
