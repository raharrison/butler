package net.ryanh.butler.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct coverage of the path-to-position index. It is the subtlest logic in the config layer,
 * since array indices advance when a container <em>ends</em> rather than when it starts, and
 * every diagnostic in the product depends on it being right.
 */
class SourceMapTest {

    private static final String YAML = """
            version: 1
            settings:
              log_format: json
            jobs:
              api:
                on:
                  - uses: file.appeared
                    dir: /srv
                  - uses: manual
                steps:
                  - uses: fs.copy
                    from: /a
                  - uses: fs.symlink
                    link: /b
                    nested:
                      deep:
                        - one
                        - two
                tags: [a, b, c]
            """;

    private final SourceMap map = SourceMap.of(YAML);

    private void assertAt(String path, int line) {
        Diagnostic.Loc loc = map.locate(path);
        assertNotNull(loc, "no location for " + path);
        assertEquals(line, loc.line(), "wrong line for " + path);
    }

    @Test
    @DisplayName("top-level keys")
    void topLevel() {
        assertAt("/version", 1);
        assertAt("/settings", 2);
        assertAt("/jobs", 4);
    }

    @Test
    @DisplayName("nested mappings")
    void nested() {
        assertAt("/settings/log_format", 3);
        assertAt("/jobs/api", 5);
        assertAt("/jobs/api/on", 6);
    }

    @Test
    @DisplayName("objects inside arrays advance by index")
    void objectsInArrays() {
        assertAt("/jobs/api/on/0", 7);
        assertAt("/jobs/api/on/0/uses", 7);
        assertAt("/jobs/api/on/0/dir", 8);
        assertAt("/jobs/api/on/1", 9);
        assertAt("/jobs/api/on/1/uses", 9);
    }

    @Test
    @DisplayName("a second array in the same mapping restarts at index 0")
    void secondArray() {
        assertAt("/jobs/api/steps/0/uses", 11);
        assertAt("/jobs/api/steps/0/from", 12);
        assertAt("/jobs/api/steps/1/uses", 13);
        assertAt("/jobs/api/steps/1/link", 14);
    }

    @Test
    @DisplayName("arrays nested inside an array item")
    void arraysInsideArrayItems() {
        assertAt("/jobs/api/steps/1/nested", 15);
        assertAt("/jobs/api/steps/1/nested/deep", 16);
        assertAt("/jobs/api/steps/1/nested/deep/0", 17);
        assertAt("/jobs/api/steps/1/nested/deep/1", 18);
    }

    @Test
    @DisplayName("indices resume correctly after a nested container closes")
    void indexResumesAfterNestedContainer() {
        // tags sits after a step containing a nested array; if the index bump on END_* were
        // wrong, this key would be attributed to the wrong line.
        assertAt("/jobs/api/tags", 19);
        assertAt("/jobs/api/tags/0", 19);
        assertAt("/jobs/api/tags/2", 19);
    }

    @Test
    @DisplayName("an unknown path walks up to its nearest known ancestor")
    void walksUpToAncestor() {
        assertAt("/jobs/api/steps/1/link/does/not/exist", 14);
        assertAt("/jobs/api/nonexistent", 5);
    }

    @Test
    void emptyMapLocatesNothing() {
        assertNull(SourceMap.empty().locate("/anything"));
    }

    @Test
    void malformedYamlYieldsWhateverWasReadRatherThanThrowing() {
        SourceMap partial = assertDoesNotThrow(() -> SourceMap.of("a: 1\nb: [\n"));
        assertNotNull(partial.locate("/a"));
    }
}
