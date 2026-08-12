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
                settle, orderBy, onStartup);
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
                    OnStartup.LATEST));
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
        @DisplayName("on_startup: none waits for a change rather than reporting what is there")
        void onStartupNoneWaits() throws Exception {
            Path file = dir.resolve("config.yaml");
            Files.writeString(file, "a: 1");

            Watcher watcher = watch(new ChangedTrigger.Config(file, Duration.ofMillis(50),
                    OnStartup.NONE));
            try {
                never(() -> !fired.isEmpty());
                Files.writeString(file, "a: 2");
                eventually("the change to fire", () -> !fired.isEmpty());
            } finally {
                watcher.stop();
            }
        }
    }
}
