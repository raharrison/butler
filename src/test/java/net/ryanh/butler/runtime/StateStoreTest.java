package net.ryanh.butler.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        // A value that cannot be written fails the write partway through, exactly as a crash
        // would. The file that is already there must not be touched.
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

    /**
     * Blows up while it is being serialised, which is the closest a test can get to the machine
     * dying half way through the write.
     */
    public static final class Unwritable {
        public String getBoom() {
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
