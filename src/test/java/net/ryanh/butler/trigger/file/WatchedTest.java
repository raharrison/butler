package net.ryanh.butler.trigger.file;

import net.ryanh.butler.runtime.Triggering;
import net.ryanh.butler.spi.TriggerContext;
import net.ryanh.butler.util.Literals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code poll_interval:} resolution, and the aggregate a directory candidate is judged by.
 */
class WatchedTest {

    private static final TriggerContext CTX = new Triggering("test", Duration.ofSeconds(5), false);

    @Test
    @DisplayName("no override falls back to the daemon default")
    void noOverride() {
        assertEquals(Duration.ofSeconds(5), Watched.pollInterval(null, CTX));
    }

    @Test
    @DisplayName("a positive override wins over the daemon default")
    void positiveOverrideWins() {
        assertEquals(Duration.ofMillis(50), Watched.pollInterval(Duration.ofMillis(50), CTX));
    }

    @Test
    @DisplayName("a zero override is treated as unset rather than spinning")
    void zeroOverrideFallsBack() {
        assertEquals(Duration.ofSeconds(5), Watched.pollInterval(Duration.ZERO, CTX));
    }

    @Test
    @DisplayName("a negative override is treated as unset rather than failing")
    void negativeOverrideFallsBack() {
        assertEquals(Duration.ofSeconds(5), Watched.pollInterval(Duration.ofSeconds(-1), CTX));
    }

    @Nested
    @DisplayName("a directory's snapshot")
    class Trees {

        @TempDir
        Path dir;

        @Test
        @DisplayName("sums the regular files beneath it and counts every entry")
        void aggregates() throws IOException {
            Files.writeString(dir.resolve("app.jar"), "12345");
            Path lib = Files.createDirectory(dir.resolve("lib"));
            Files.writeString(lib.resolve("dep.jar"), "678");

            Watched.Snapshot snapshot = Watched.Snapshot.ofTree(dir);

            assertEquals(8, snapshot.size(), "the directory's own size says nothing");
            assertEquals(3, snapshot.entries(), "app.jar, lib and lib/dep.jar");
        }

        @Test
        @DisplayName("takes the newest mtime anywhere in the tree, not the directory's own")
        void newestModifiedWins() throws IOException {
            Path lib = Files.createDirectory(dir.resolve("lib"));
            Files.writeString(lib.resolve("dep.jar"), "x");

            Instant recent = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
            Files.setLastModifiedTime(lib.resolve("dep.jar"), FileTime.from(recent));

            assertEquals(recent.toEpochMilli(), Watched.Snapshot.ofTree(dir).modified());
        }

        @Test
        @DisplayName("an empty directory reports no entries, which is what stops it firing")
        void empty() throws IOException {
            assertEquals(0, Watched.Snapshot.ofTree(dir).entries());
        }

        @Test
        @DisplayName("a directory that is not there is an IOException, not an unchecked one "
                + "escaping onto the watch thread")
        void unreadable() {
            assertThrows(IOException.class,
                    () -> Watched.Snapshot.ofTree(dir.resolve("absent")));
        }

        @Test
        @DisplayName("a directory's dedupe key carries its entry count, a file's is unchanged")
        void dedupeKeys() throws IOException {
            Files.writeString(dir.resolve("app.jar"), "12345");
            Watched.Snapshot tree = Watched.Snapshot.ofTree(dir);

            assertEquals(Literals.path(dir.toAbsolutePath()) + ":5:" + tree.modified() + ":1",
                    Watched.dedupeKey(dir, tree, Kind.DIR));

            Path file = dir.resolve("app.jar");
            Watched.Snapshot one = Watched.Snapshot.of(file);
            assertEquals(Literals.path(file.toAbsolutePath()) + ":5:" + one.modified(),
                    Watched.dedupeKey(file, one, Kind.FILE));
        }
    }
}
