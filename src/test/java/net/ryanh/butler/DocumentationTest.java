package net.ryanh.butler;

import net.ryanh.butler.runtime.NotifierRegistry;
import net.ryanh.butler.runtime.StepRegistry;
import net.ryanh.butler.runtime.TriggerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The docs are part of the product, so the references between them and the code are checked like
 * any other reference.
 */
class DocumentationTest {

    private static final Path DOCS = Path.of("docs");

    private static final List<Path> PAGES = List.of(
            Path.of("README.md"), DOCS.resolve("DESIGN.md"),
            DOCS.resolve("CONFIGURATION.md"), DOCS.resolve("OPERATING.md"));

    private static final Pattern CITATION = Pattern.compile("DESIGN\\.md §(\\d+(?:\\.\\d+)?)");
    // "## 1. Design principles" and "### 2.1 Run lifecycle" both.
    private static final Pattern HEADING = Pattern.compile("^#{2,3} (\\d+(?:\\.\\d+)?)\\.? \\w");

    /**
     * Skips rather than fails when run from somewhere other than the project root.
     */
    private static String read(Path path) throws IOException {
        assumeTrue(Files.isReadable(path), "run from the project root to check the docs");
        return Files.readString(path);
    }

    private static Set<String> designSections() throws IOException {
        Set<String> sections = new LinkedHashSet<>();
        for (String line : read(DOCS.resolve("DESIGN.md")).lines().toList()) {
            Matcher m = HEADING.matcher(line);
            if (m.find()) {
                sections.add(m.group(1));
            }
        }
        assertTrue(sections.size() > 25, "DESIGN.md has no numbered sections: " + sections);
        return sections;
    }

    @Test
    @DisplayName("every DESIGN.md section the code cites exists, so restructuring the document "
            + "cannot leave a javadoc pointing at nothing")
    void everyCitedSectionExists() throws IOException {
        Set<String> sections = designSections();
        List<String> broken = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(Path.of("src"))) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = CITATION.matcher(Files.readString(file));
                while (m.find()) {
                    if (!sections.contains(m.group(1))) {
                        broken.add(file + " cites DESIGN.md §" + m.group(1));
                    }
                }
            }
        }
        // The document's own cross-references go stale the same way.
        Matcher own = Pattern.compile("§(\\d+(?:\\.\\d+)?)").matcher(read(DOCS.resolve("DESIGN.md")));
        while (own.find()) {
            if (!sections.contains(own.group(1))) {
                broken.add("DESIGN.md cross-references §" + own.group(1));
            }
        }
        assertEquals(List.of(), broken, "cited sections that DESIGN.md does not have");
    }

    @Test
    @DisplayName("every registered step, trigger and notifier is documented, so a new one cannot "
            + "ship without a reference entry")
    void theVocabularyIsDocumented() throws IOException {
        List<String> names = new ArrayList<>();
        names.addAll(StepRegistry.discover().names());
        names.addAll(TriggerRegistry.discover().names());
        names.addAll(NotifierRegistry.discover().names());
        assertTrue(names.size() > 30, "the registries came up empty: " + names);

        List<String> undocumented = new ArrayList<>();
        for (Path doc : List.of(DOCS.resolve("CONFIGURATION.md"), Path.of("README.md"))) {
            String text = read(doc);
            // Bounded: a plain substring test would let fs.readlink alone document fs.read.
            names.stream()
                    .filter(name -> !Pattern.compile("\\b" + Pattern.quote(name) + "\\b")
                            .matcher(text).find())
                    .forEach(name -> undocumented.add(doc + " never mentions " + name));
        }
        assertEquals(List.of(), undocumented, "vocabulary missing from the docs");
    }

    @Test
    @DisplayName("no doc links to a file that is not there")
    void everyRelativeLinkResolves() throws IOException {
        Pattern link = Pattern.compile("]\\((?!https?://|#)([^)#]+)");
        List<String> broken = new ArrayList<>();

        for (Path doc : PAGES) {
            Matcher m = link.matcher(read(doc));
            while (m.find()) {
                Path target = doc.toAbsolutePath().getParent().resolve(m.group(1)).normalize();
                if (!Files.exists(target)) {
                    broken.add(doc + " -> " + m.group(1));
                }
            }
        }
        assertEquals(List.of(), broken, "links pointing at files that do not exist");
    }

    @Test
    @DisplayName("every cross-document #anchor names a heading that exists, since consolidating "
            + "into one page is only safe if the links into it are checked")
    void everyAnchorResolves() throws IOException {
        Map<Path, Set<String>> headings = new LinkedHashMap<>();
        for (Path doc : PAGES) {
            Set<String> slugs = new LinkedHashSet<>();
            Matcher m = Pattern.compile("(?m)^#{1,6} (.+)$").matcher(read(doc));
            while (m.find()) {
                slugs.add(slug(m.group(1)));
            }
            headings.put(doc.toAbsolutePath().normalize(), slugs);
        }

        Pattern link = Pattern.compile("]\\((?!https?://)([^)#]*)#([^)]+)\\)");
        List<String> broken = new ArrayList<>();
        for (Path doc : PAGES) {
            Matcher m = link.matcher(read(doc));
            while (m.find()) {
                Path target = m.group(1).isEmpty() ? doc
                        : doc.toAbsolutePath().getParent().resolve(m.group(1));
                Set<String> known = headings.get(target.toAbsolutePath().normalize());
                if (known == null || !known.contains(m.group(2))) {
                    broken.add(doc + " -> " + m.group(1) + "#" + m.group(2));
                }
            }
        }
        assertEquals(List.of(), broken, "anchors pointing at headings that do not exist");
    }

    /**
     * GitHub's heading slug: lowercased, punctuation dropped, spaces hyphenated.
     */
    private static String slug(String heading) {
        String text = heading.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("[`*_]", "")
                .replaceAll("[^a-z0-9 -]", "");
        return text.replace(' ', '-');
    }
}
