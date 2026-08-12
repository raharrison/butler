package net.ryanh.butler.runtime;

import net.ryanh.butler.util.Semver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {

    @TempDir
    Path dir;

    @Test
    void writesAndReadsBackWhatAJobKnows() throws IOException {
        StateStore store = StateStore.at(dir);
        Instant when = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        store.write("api", new StateStore.JobState("artifact-1.2.4", when,
                Map.of("deployed_version", "1.2.4")));

        StateStore.JobState read = store.read("api");
        assertEquals("artifact-1.2.4", read.dedupeKey());
        assertEquals(when, read.lastRun());
        assertEquals("1.2.4", read.values().get("deployed_version"));
    }

    @Test
    @DisplayName("a job that has never run has empty state, not an error")
    void nothingRecordedYet() {
        StateStore.JobState read = StateStore.at(dir).read("never-run");
        assertNull(read.dedupeKey());
        assertTrue(read.values().isEmpty());
    }

    @Test
    @DisplayName("persisted values sit under their own key, so a job may persist one called "
            + "dedupe_key")
    void aPersistedValueCannotOverwriteTheBookkeeping() throws IOException {
        StateStore store = StateStore.at(dir);
        store.write("api", new StateStore.JobState("the-real-key", Instant.now(),
                Map.of("dedupe_key", "just a value")));

        StateStore.JobState read = store.read("api");
        assertEquals("the-real-key", read.dedupeKey());
        assertEquals("just a value", read.values().get("dedupe_key"));
    }

    @Test
    @DisplayName("a value with no JSON form of its own is stored as the text it renders to, so "
            + "the next run reads back what was written")
    void valuesWithoutAJsonFormRoundTrip() throws IOException {
        StateStore store = StateStore.at(dir);
        store.write("api", new StateStore.JobState("k", Instant.now(), Map.of(
                "as_version", Semver.parse("1.2.4"),
                "how_long", Duration.ofSeconds(30),
                "where", Path.of("/srv/apps/api"))));

        Map<String, Object> read = store.read("api").values();
        // Databind would write the version as {} and the duration as PT30S, neither of which is
        // the value the run reported or one an expression can compare against.
        assertEquals("1.2.4", read.get("as_version"));
        assertEquals("30s", read.get("how_long"), "the one duration syntax, not ISO-8601");
        assertEquals("/srv/apps/api", read.get("where"));
    }

    @Test
    @DisplayName("ordinary JSON values are stored as themselves, nested ones included")
    void jsonValuesAreLeftAlone() throws IOException {
        StateStore store = StateStore.at(dir);
        store.write("api", new StateStore.JobState("k", Instant.now(), Map.of(
                "count", 5,
                "healthy", true,
                "probe", Map.of("took", Duration.ofMillis(1500)))));

        Map<String, Object> read = store.read("api").values();
        assertEquals(5, read.get("count"));
        assertEquals(true, read.get("healthy"));
        assertEquals(Map.of("took", "1500ms"), read.get("probe"));
    }

    @Test
    @DisplayName("a corrupt state file degrades to empty rather than refusing to start")
    void corruptFile() throws IOException {
        Files.createDirectories(dir.resolve("jobs"));
        Files.writeString(dir.resolve("jobs/api.json"), "{not json at all");

        StateStore.JobState read = StateStore.at(dir).read("api");
        assertTrue(read.values().isEmpty(), "discovery re-derives the truth on the next event");
        assertNull(read.dedupeKey());
    }

    @Test
    @DisplayName("a failed write leaves the previous state intact and no temp file behind")
    void writesAreAllOrNothing() throws IOException {
        StateStore store = StateStore.at(dir);
        store.write("api", new StateStore.JobState("first", Instant.now(),
                Map.of("deployed_version", "1.2.3")));

        // A value that blows up on the way to disk, which is the closest a test gets to the
        // machine dying half way through. The file already there must not be touched.
        assertThrows(RuntimeException.class, () -> store.write("api",
                new StateStore.JobState("second", Instant.now(),
                        Map.of("deployed_version", new Unwritable()))));

        StateStore.JobState read = store.read("api");
        assertEquals("first", read.dedupeKey());
        assertEquals("1.2.3", read.values().get("deployed_version"));

        try (var files = Files.list(dir.resolve("jobs"))) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("api.json"), names, "no temp file left behind");
        }
    }

    @Test
    @DisplayName("a replace that cannot complete leaves no temp file in the state directory")
    void aFailedReplaceTidiesUp() throws IOException {
        // A non-empty directory where the file goes: the move cannot land on it, on any platform.
        Path target = dir.resolve("jobs/api.json");
        Files.createDirectories(target);
        Files.writeString(target.resolve("in-the-way"), "x");

        assertThrows(IOException.class, () -> Atomically.write(target, "{}"));

        try (var files = Files.list(dir.resolve("jobs"))) {
            assertEquals(List.of("api.json"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    /**
     * Blows up while it is being rendered, the one way a value can still fail a write now that
     * anything without a JSON form is stored as text.
     */
    public static final class Unwritable {
        @Override
        public String toString() {
            throw new IllegalStateException("nope");
        }
    }

    @Test
    @DisplayName("a job name that is not a usable file name still gets a file")
    void awkwardJobNames() throws IOException {
        StateStore store = StateStore.at(dir);
        store.write("deploy/api", new StateStore.JobState("k", Instant.now(), Map.of()));
        assertTrue(Files.exists(dir.resolve("jobs/deploy_api.json")));
        assertEquals("k", store.read("deploy/api").dedupeKey());
    }
}
