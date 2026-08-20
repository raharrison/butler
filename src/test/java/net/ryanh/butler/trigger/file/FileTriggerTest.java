package net.ryanh.butler.trigger.file;

import net.ryanh.butler.expr.ExprException;
import net.ryanh.butler.runtime.Triggering;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.TriggerContext;
import net.ryanh.butler.spi.Watcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code file.appeared} and {@code file.changed}, including the cases that are tedious to
 * reproduce by hand: a file written in chunks, a lower version appearing later, an artifact
 * already present at startup, and a file deleted mid-settle.
 */
class FileTriggerTest {

    @TempDir
    Path dir;

    /**
     * A fast poll, or every test here would sleep through the default five seconds.
     */
    private static final TriggerContext CTX =
            new Triggering("test", Duration.ofMillis(20), false);

    private final List<Event> fired = new CopyOnWriteArrayList<>();

    private Watcher watch(AppearedTrigger.Config config) {
        return new AppearedTrigger().start(config, fired::add, CTX);
    }

    private static AppearedTrigger.Config watching(Path dir, Duration settle, String orderBy,
                                                   OnStartup onStartup) {
        return new AppearedTrigger.Config(dir,
                Pattern.compile("api-(?<version>\\d+\\.\\d+\\.\\d+)\\.jar"),
                settle, orderBy, onStartup, null, Kind.FILE);
    }

    private AppearedTrigger.Config watchingDirs(Duration settle, String orderBy,
                                                OnStartup onStartup) {
        return new AppearedTrigger.Config(dir,
                Pattern.compile("api-(?<version>\\d+\\.\\d+\\.\\d+)"),
                settle, orderBy, onStartup, null, Kind.DIR);
    }

    private void artifact(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    /**
     * Polls for something the watcher should reach, so a slow machine does not make a test flap
     * and a fast one does not make it slow.
     */
    private static void eventually(String what, BooleanSupplier done)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("timed out waiting for " + what);
    }

    private static void never(BooleanSupplier happened)
            throws InterruptedException {
        Thread.sleep(300);
        assertFalse(happened.getAsBoolean());
    }

    @Nested
    @DisplayName("file.appeared")
    class Appeared {

        @Test
        @DisplayName("fires once a file has settled, with the regex groups as facts")
        void firesWithNamedGroups() throws Exception {
            Watcher watcher = watch(watching(dir, Duration.ofMillis(50), null, OnStartup.ALL));
            try {
                artifact("api-1.2.4.jar", "jar");
                eventually("the artifact to fire", () -> !fired.isEmpty());

                Event event = fired.getFirst();
                assertEquals("file.appeared", event.trigger());
                assertEquals("1.2.4", event.facts().get("version"));
                assertEquals("api-1.2.4.jar", event.facts().get("name"));
                assertEquals(3L, event.facts().get("size"));
                assertTrue(String.valueOf(event.facts().get("path")).endsWith("api-1.2.4.jar"));
                assertNotNull(event.dedupeKey());
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("a file still being written does not fire until it stops growing")
        void settleHoldsBackAHalfWrittenFile() throws Exception {
            Watcher watcher = watch(watching(dir, Duration.ofMillis(300), null, OnStartup.ALL));
            try {
                Path file = dir.resolve("api-1.2.4.jar");
                Files.writeString(file, "chunk-");
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(60);
                    Files.writeString(file, "chunk-" + i, StandardOpenOption.APPEND);
                    assertTrue(fired.isEmpty(),
                            "deploying a half-uploaded jar is exactly what settle prevents");
                }
                eventually("the finished artifact to fire", () -> !fired.isEmpty());
                assertEquals(1, fired.size());
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("a file deleted mid-settle fires nothing")
        void aFileDeletedMidSettleNeverFires() throws Exception {
            Watcher watcher = watch(watching(dir, Duration.ofMillis(300), null, OnStartup.ALL));
            try {
                artifact("api-1.2.4.jar", "half");
                Thread.sleep(100);
                Files.delete(dir.resolve("api-1.2.4.jar"));
                never(() -> !fired.isEmpty());
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("order_by means an older version dropped in later cannot deploy a downgrade")
        void aLowerVersionAppearingLaterDoesNotFire() throws Exception {
            Watcher watcher = watch(watching(dir, Duration.ofMillis(50), "semver(version)",
                    OnStartup.ALL));
            try {
                artifact("api-1.2.4.jar", "new");
                eventually("1.2.4 to fire", () -> !fired.isEmpty());
                assertEquals("1.2.4", fired.getFirst().facts().get("version"));

                artifact("api-1.2.3.jar", "old");
                never(() -> fired.size() > 1);

                artifact("api-1.3.0.jar", "newer");
                eventually("1.3.0 to fire", () -> fired.size() > 1);
                assertEquals("1.3.0", fired.get(1).facts().get("version"));
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("on_startup: latest fires for the greatest artifact already there")
        void restartWithAnArtifactAlreadyPresent() throws Exception {
            artifact("api-1.2.3.jar", "old");
            artifact("api-1.2.4.jar", "new");

            Watcher watcher = watch(watching(dir, Duration.ofMillis(50), "semver(version)",
                    OnStartup.LATEST));
            try {
                eventually("the newest artifact to fire", () -> !fired.isEmpty());
                Thread.sleep(200);
                assertEquals(1, fired.size(), "only the greatest fires");
                assertEquals("1.2.4", fired.getFirst().facts().get("version"));
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("on_startup: none treats what is already there as already seen")
        void onStartupNoneFiresForNothing() throws Exception {
            artifact("api-1.2.4.jar", "already here");

            Watcher watcher = watch(watching(dir, Duration.ofMillis(50), null, OnStartup.NONE));
            try {
                never(() -> !fired.isEmpty());
                artifact("api-1.2.5.jar", "new");
                eventually("the new artifact to fire", () -> !fired.isEmpty());
                assertEquals("1.2.5", fired.getFirst().facts().get("version"));
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("an order_by that will not parse fails the start rather than killing the "
                + "watch thread in silence")
        void aBadOrderByFailsTheStart() throws Exception {
            AppearedTrigger.Config config =
                    watching(dir, Duration.ofMillis(50), "semver(version", OnStartup.ALL);

            assertThrows(ExprException.class, () -> new AppearedTrigger()
                    .start(config, fired::add, CTX));

            artifact("api-1.2.4.jar", "jar");
            never(() -> !fired.isEmpty());
        }

        @Test
        @DisplayName("the same file fires once, and again once it is rewritten")
        void theDedupeKeyFollowsTheContents() throws Exception {
            Watcher watcher = watch(watching(dir, Duration.ofMillis(50), null, OnStartup.ALL));
            try {
                artifact("api-1.2.4.jar", "one");
                eventually("the first firing", () -> !fired.isEmpty());
                never(() -> fired.size() > 1);

                artifact("api-1.2.4.jar", "two, which is longer");
                eventually("the rewrite to fire", () -> fired.size() > 1);
                assertNotEquals(fired.get(0).dedupeKey(), fired.get(1).dedupeKey());
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("no dir: fails the start rather than leaving the daemon reporting that it "
                + "watches a job whose watch thread died on the first poll")
        void aMissingDirFailsTheStart() throws Exception {
            AppearedTrigger.Config config =
                    new AppearedTrigger.Config(null, null, Duration.ofMillis(50), null,
                            OnStartup.ALL, null, Kind.FILE);

            var e = assertThrows(IllegalArgumentException.class,
                    () -> new AppearedTrigger().start(config, fired::add, CTX));
            assertTrue(e.getMessage().contains("needs a dir"), e.getMessage());

            // And the rehearsal path, which does not go through start(), simply sees nothing.
            assertEquals(List.of(), new AppearedTrigger().current(config, CTX));
        }

        @Test
        @DisplayName("current() is what butler trigger rehearses against: the settled candidates, "
                + "greatest last")
        void currentReportsTheSettledCandidates() throws Exception {
            artifact("api-1.9.0.jar", "a");
            artifact("api-1.10.0.jar", "b");
            artifact("notes.txt", "ignored");
            // Settle is judged from the modification time in a single-shot look, so age them.
            for (String name : List.of("api-1.9.0.jar", "api-1.10.0.jar", "notes.txt")) {
                Files.setLastModifiedTime(dir.resolve(name),
                        FileTime.from(Instant.now().minusSeconds(60)));
            }

            List<Event> events = new AppearedTrigger().current(
                    watching(dir, Duration.ofSeconds(10), "semver(version)", OnStartup.LATEST),
                    CTX);

            assertEquals(List.of("1.9.0", "1.10.0"),
                    events.stream().map(e -> e.facts().get("version")).toList());
        }

        @Test
        @DisplayName("current() leaves out a file that has not settled")
        void currentSkipsWhatIsStillBeingWritten() throws Exception {
            artifact("api-1.2.4.jar", "still being written");
            assertEquals(List.of(), new AppearedTrigger().current(
                    watching(dir, Duration.ofSeconds(30), null, OnStartup.LATEST), CTX));
        }

        @Test
        @DisplayName("poll_interval: on the trigger itself overrides a slow daemon default")
        void pollIntervalOverridesTheDefault() throws Exception {
            TriggerContext slow = new Triggering("test", Duration.ofSeconds(10), false);
            AppearedTrigger.Config config = new AppearedTrigger.Config(dir,
                    Pattern.compile("api-(?<version>\\d+\\.\\d+\\.\\d+)\\.jar"),
                    Duration.ofMillis(20), null, OnStartup.ALL, Duration.ofMillis(30),
                    Kind.FILE);

            Watcher watcher = new AppearedTrigger().start(config, fired::add, slow);
            try {
                // The first scan runs immediately, so the file has to arrive after it for the
                // next firing to actually depend on the poll interval rather than the first look.
                Thread.sleep(100);
                artifact("api-1.2.4.jar", "jar");
                eventually("the artifact to fire despite a 10s daemon default",
                        () -> !fired.isEmpty());
            } finally {
                watcher.stop();
            }
        }
    }

    @Nested
    @DisplayName("file.appeared with kind: dir")
    class AppearedDirectories {

        /**
         * A release that arrives unpacked rather than as one jar.
         */
        private Path release(String name, String content) throws IOException {
            Path root = Files.createDirectories(dir.resolve(name).resolve("lib"));
            Files.writeString(root.resolve("app.jar"), content);
            return dir.resolve(name);
        }

        @Test
        @DisplayName("fires once a tree has settled, with the directory's own name matched")
        void firesForADirectory() throws Exception {
            Watcher watcher = watch(watchingDirs(Duration.ofMillis(50), null, OnStartup.ALL));
            try {
                release("api-1.2.4", "jar");
                eventually("the release to fire", () -> !fired.isEmpty());

                Event event = fired.getFirst();
                assertEquals("1.2.4", event.facts().get("version"));
                assertEquals("api-1.2.4", event.facts().get("name"));
                // The size of everything beneath it, not the directory entry's own.
                assertEquals(3L, event.facts().get("size"));
                assertTrue(String.valueOf(event.facts().get("path")).endsWith("api-1.2.4"));
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("a file still being written inside the tree holds the whole tree back")
        void settleHoldsBackATreeStillBeingFilled() throws Exception {
            Watcher watcher = watch(watchingDirs(Duration.ofMillis(200), null, OnStartup.ALL));
            try {
                // Written into a subdirectory, and for well over the settle window, so the top
                // directory's own size and mtime never move while the copy is in flight.
                Path file = release("api-1.2.4", "chunk-").resolve("lib").resolve("app.jar");
                for (int i = 0; i < 8; i++) {
                    Thread.sleep(80);
                    Files.writeString(file, "chunk-" + i, StandardOpenOption.APPEND);
                    assertTrue(fired.isEmpty(), "an unpacked release is exactly as damaging "
                            + "half-copied as a jar is half-uploaded");
                }
                eventually("the finished release to fire", () -> !fired.isEmpty());
                assertEquals(1, fired.size());
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("an empty directory is not a candidate, so a mkdir alone fires nothing")
        void anEmptyDirectoryNeverFires() throws Exception {
            Watcher watcher = watch(watchingDirs(Duration.ofMillis(50), null, OnStartup.ALL));
            try {
                Files.createDirectory(dir.resolve("api-1.2.4"));
                never(() -> !fired.isEmpty());
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("order_by ranks directories, so an older release unpacked later is ignored")
        void aLowerVersionAppearingLaterDoesNotFire() throws Exception {
            Watcher watcher = watch(watchingDirs(Duration.ofMillis(50), "semver(version)",
                    OnStartup.ALL));
            try {
                release("api-1.2.4", "new");
                eventually("1.2.4 to fire", () -> !fired.isEmpty());
                assertEquals("1.2.4", fired.getFirst().facts().get("version"));

                release("api-1.2.3", "old");
                never(() -> fired.size() > 1);
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("on_startup: latest fires for the greatest release already unpacked")
        void restartWithAReleaseAlreadyPresent() throws Exception {
            release("api-1.2.3", "old");
            release("api-1.2.4", "new");

            Watcher watcher = watch(watchingDirs(Duration.ofMillis(50), "semver(version)",
                    OnStartup.LATEST));
            try {
                eventually("the newest release to fire", () -> !fired.isEmpty());
                Thread.sleep(200);
                assertEquals(1, fired.size(), "only the greatest fires");
                assertEquals("1.2.4", fired.getFirst().facts().get("version"));
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("each kind ignores the other, so no config written today changes meaning")
        void theTwoKindsDoNotSeeEachOther() throws Exception {
            release("api-1.2.4", "unpacked");
            artifact("api-1.2.3.jar", "jar");

            Watcher dirs = watch(watchingDirs(Duration.ofMillis(50), null, OnStartup.ALL));
            try {
                eventually("the release to fire", () -> !fired.isEmpty());
                Thread.sleep(200);
                assertEquals(List.of("1.2.4"),
                        fired.stream().map(e -> e.facts().get("version")).toList());
            } finally {
                dirs.stop();
            }

            fired.clear();
            Watcher files = watch(watching(dir, Duration.ofMillis(50), null, OnStartup.ALL));
            try {
                eventually("the jar to fire", () -> !fired.isEmpty());
                Thread.sleep(200);
                assertEquals(List.of("1.2.3"),
                        fired.stream().map(e -> e.facts().get("version")).toList());
            } finally {
                files.stop();
            }
        }

        @Test
        @DisplayName("current() reports the settled directories, greatest last")
        void currentReportsTheSettledDirectories() throws Exception {
            release("api-1.9.0", "a");
            release("api-1.10.0", "b");
            artifact("api-1.2.3.jar", "ignored, it is a file");
            age(dir.resolve("api-1.9.0"), dir.resolve("api-1.10.0"));

            List<Event> events = new AppearedTrigger().current(
                    watchingDirs(Duration.ofSeconds(10), "semver(version)", OnStartup.LATEST),
                    CTX);

            assertEquals(List.of("1.9.0", "1.10.0"),
                    events.stream().map(e -> e.facts().get("version")).toList());
        }

        /**
         * Settle is judged from the newest mtime in the tree in a single-shot look, so every entry
         * has to be aged, not only the directory.
         */
        private void age(Path... trees) throws IOException {
            FileTime old = FileTime.from(Instant.now().minusSeconds(60));
            for (Path tree : trees) {
                try (Stream<Path> walked = Files.walk(tree)) {
                    for (Path p : walked.toList()) {
                        Files.setLastModifiedTime(p, old);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("file.changed")
    class Changed {

        private Watcher watch(ChangedTrigger.Config config) {
            return new ChangedTrigger().start(config, fired::add, CTX);
        }

        @Test
        @DisplayName("fires on a content change, and not on a rewrite of the same contents")
        void firesOnContentRatherThanTimestamp() throws Exception {
            Path file = dir.resolve("config.yaml");
            Files.writeString(file, "a: 1");

            Watcher watcher = watch(new ChangedTrigger.Config(file, Duration.ofMillis(50),
                    OnStartup.LATEST, null));
            try {
                eventually("the first reading", () -> !fired.isEmpty());
                assertNotNull(fired.getFirst().facts().get("sha256"));

                Files.writeString(file, "a: 1");
                never(() -> fired.size() > 1);

                Files.writeString(file, "a: 2");
                eventually("the change to fire", () -> fired.size() > 1);
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("no path: fails the start, as file.appeared does without a dir")
        void aMissingPathFailsTheStart() {
            ChangedTrigger.Config config =
                    new ChangedTrigger.Config(null, Duration.ofMillis(50), OnStartup.LATEST, null);

            var e = assertThrows(IllegalArgumentException.class,
                    () -> new ChangedTrigger().start(config, fired::add, CTX));
            assertTrue(e.getMessage().contains("needs a path"), e.getMessage());
            assertEquals(List.of(), new ChangedTrigger().current(config, CTX));
        }

        @Test
        @DisplayName("on_startup: none waits for a change rather than reporting what is there")
        void onStartupNoneWaits() throws Exception {
            Path file = dir.resolve("config.yaml");
            Files.writeString(file, "a: 1");

            Watcher watcher = watch(new ChangedTrigger.Config(file, Duration.ofMillis(50),
                    OnStartup.NONE, null));
            try {
                never(() -> !fired.isEmpty());
                Files.writeString(file, "a: 2");
                eventually("the change to fire", () -> !fired.isEmpty());
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("poll_interval: on the trigger itself overrides a slow daemon default")
        void pollIntervalOverridesTheDefault() throws Exception {
            Path file = dir.resolve("config.yaml");
            Files.writeString(file, "a: 1");
            TriggerContext slow = new Triggering("test", Duration.ofSeconds(10), false);
            ChangedTrigger.Config config = new ChangedTrigger.Config(file, Duration.ofMillis(20),
                    OnStartup.LATEST, Duration.ofMillis(30));

            Watcher watcher = new ChangedTrigger().start(config, fired::add, slow);
            try {
                eventually("the first reading despite a 10s daemon default",
                        () -> !fired.isEmpty());
            } finally {
                watcher.stop();
            }
        }
    }
}
