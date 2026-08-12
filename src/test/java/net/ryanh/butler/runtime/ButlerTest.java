package net.ryanh.butler.runtime;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.testing.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The daemon loop: starting watchers, bounding runs and stopping cleanly.
 */
class ButlerTest {

    @TempDir
    Path root;

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    private static void eventually(String what, BooleanSupplier done) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for " + what);
    }

    private Butler butler(String yaml, boolean dryRun) {
        ConfigLoader.Result result = Fixture.config(yaml, StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return new Butler(
                Fixture.environment(result, StepRegistry.discover(), root.resolve("state")),
                TriggerRegistry.discover(), dryRun,
                new PrintStream(printed, true, StandardCharsets.UTF_8));
    }

    private String config(Path watched, String steps) {
        return """
                settings:
                  state_dir: %s
                  poll_interval: 30ms
                jobs:
                  j:
                    on:
                      - uses: file.appeared
                        dir: %s
                        settle: 50ms
                        on_startup: all
                    steps:
                %s
                """.formatted(root.resolve("state").toString().replace('\\', '/'),
                watched.toString().replace('\\', '/'), steps);
    }

    @Test
    @DisplayName("a watcher's event reaches the runner, and the run writes state")
    void drainsEventsIntoRuns() throws Exception {
        Path watched = Files.createDirectories(root.resolve("in"));
        Butler butler = butler(config(watched, """
                      - uses: fs.template
                        content: saw ${trigger.name}
                        to: %s
                        mkdirs: true
                """.formatted(root.resolve("out.txt").toString().replace('\\', '/'))), false);

        butler.start();
        try {
            Files.writeString(watched.resolve("thing.txt"), "x");
            eventually("the run to finish", () -> Files.exists(root.resolve("out.txt")));
        } finally {
            butler.stop();
        }
        assertEquals("saw thing.txt", Files.readString(root.resolve("out.txt")));
        assertTrue(Files.exists(root.resolve("state/jobs/j.json")));
    }

    @Test
    @DisplayName("a dry-run daemon watches and reports, and changes nothing")
    void dryRunTouchesNothing() throws Exception {
        Path watched = Files.createDirectories(root.resolve("in"));
        Butler butler = butler(config(watched, """
                      - uses: fs.template
                        content: this must not be written
                        to: %s
                """.formatted(root.resolve("out.txt").toString().replace('\\', '/'))), true);

        butler.start();
        try {
            Files.writeString(watched.resolve("thing.txt"), "x");
            eventually("the plan to be printed",
                    () -> printed.toString(StandardCharsets.UTF_8).contains("DRY RUN"));
        } finally {
            butler.stop();
        }

        String out = printed.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("would write"), out);
        assertFalse(Files.exists(root.resolve("out.txt")),
                "the safest way to introduce Butler to a running server writes nothing");
        assertFalse(Files.exists(root.resolve("state/jobs/j.json")),
                "and records nothing either");
    }

    @Test
    @DisplayName("max_concurrent_runs bounds how many runs are in flight at once")
    void concurrencyIsBounded() throws Exception {
        Path watched = Files.createDirectories(root.resolve("in"));
        Path marks = Files.createDirectories(root.resolve("marks"));
        // Each run makes a file, sleeps, then prunes it, so the count in marks/ is how many runs
        // are in flight.
        Butler butler = butler("""
                settings:
                  state_dir: %s
                  poll_interval: 20ms
                  max_concurrent_runs: 2
                jobs:
                  j:
                    on:
                      - uses: file.appeared
                        dir: %s
                        settle: 20ms
                        on_startup: all
                    steps:
                      - uses: fs.template
                        content: running
                        to: %s/${trigger.name}
                      - uses: control.sleep
                        duration: 200ms
                      - uses: fs.prune
                        dir: %s
                        keep: 0
                """.formatted(root.resolve("state").toString().replace('\\', '/'),
                watched.toString().replace('\\', '/'),
                marks.toString().replace('\\', '/'),
                marks.toString().replace('\\', '/')), false);

        AtomicInteger peak = new AtomicInteger();
        butler.start();
        try {
            for (int i = 0; i < 6; i++) {
                Files.writeString(watched.resolve("artifact-" + i + ".txt"), "x");
            }
            Instant until = Instant.now().plusSeconds(3);
            while (Instant.now().isBefore(until)) {
                try (var listed = Files.list(marks)) {
                    peak.accumulateAndGet((int) listed.count(), Math::max);
                }
                Thread.sleep(5);
            }
        } finally {
            butler.stop();
        }

        assertTrue(peak.get() > 0, "no run was ever observed in flight");
        assertTrue(peak.get() <= 2, "saw " + peak.get() + " runs in flight, the bound is 2");
    }

    /**
     * A job that says when it started and when it finished, with a stall between the two, so a
     * shutdown can be timed to land in the middle.
     */
    private String stalling(Path watched, String grace, String stall) {
        return """
                settings:
                  state_dir: %s
                  poll_interval: 20ms
                  shutdown_grace: %s
                jobs:
                  j:
                    on:
                      - uses: file.appeared
                        dir: %s
                        settle: 20ms
                        on_startup: all
                    steps:
                      - uses: fs.template
                        content: started
                        to: %s
                        mkdirs: true
                      - uses: control.sleep
                        duration: %s
                      - uses: fs.template
                        content: finished
                        to: %s
                    persist:
                      last: ${trigger.name}
                """.formatted(root.resolve("state").toString().replace('\\', '/'), grace,
                watched.toString().replace('\\', '/'),
                root.resolve("started.txt").toString().replace('\\', '/'), stall,
                root.resolve("finished.txt").toString().replace('\\', '/'));
    }

    @Test
    @DisplayName("shutdown lets a run already in flight finish")
    void shutdownDrainsInFlightRuns() throws Exception {
        Path watched = Files.createDirectories(root.resolve("in"));
        Butler butler = butler(stalling(watched, "1m", "300ms"), false);

        butler.start();
        Files.writeString(watched.resolve("thing.txt"), "x");
        eventually("the run to start", () -> Files.exists(root.resolve("started.txt")));
        butler.stop();

        assertTrue(Files.exists(root.resolve("finished.txt")),
                "a deploy killed halfway is the worst outcome available, so the drain waits");
        assertTrue(Files.readString(root.resolve("state/jobs/j.json")).contains("thing.txt"));
    }

    @Test
    @DisplayName("a run that outlasts the grace period is cancelled, and writes nothing")
    void shutdownCancelsWhatOutlastsTheGrace() throws Exception {
        Path watched = Files.createDirectories(root.resolve("in"));
        Butler butler = butler(stalling(watched, "100ms", "60s"), false);

        butler.start();
        Files.writeString(watched.resolve("thing.txt"), "x");
        eventually("the run to start", () -> Files.exists(root.resolve("started.txt")));

        Instant asked = Instant.now();
        butler.stop();

        assertTrue(Duration.between(asked, Instant.now()).compareTo(Duration.ofSeconds(30)) < 0,
                "shutdown should not wait out a 60s step it has already cancelled");
        assertFalse(Files.exists(root.resolve("finished.txt")), "the run was cut short");
        assertFalse(Files.exists(root.resolve("state/jobs/j.json")),
                "a cancelled run records nothing: the work was withdrawn, not done");
    }

    @Test
    @DisplayName("an event the job has already done is dropped before it takes a place in the "
            + "group, so it cannot queue behind a deploy or displace one")
    void anAlreadyProcessedEventNeverReachesTheGate() throws Exception {
        Path watched = Files.createDirectories(root.resolve("in"));
        Path artifact = watched.resolve("thing.txt");
        Files.writeString(artifact, "x");

        // Record the artifact's key as already done, the way `butler adopt` does at install time.
        Butler first = butler(config(watched, """
                      - uses: fs.template
                        content: ran
                        to: %s
                        mkdirs: true
                """.formatted(root.resolve("first.txt").toString().replace('\\', '/'))), false);
        first.start();
        try {
            eventually("the first run to finish", () -> Files.exists(root.resolve("first.txt")));
        } finally {
            first.stop();
        }

        // A second daemon over the same state directory sees the same artifact at startup.
        Butler second = butler(config(watched, """
                      - uses: fs.template
                        content: must not run again
                        to: %s
                        mkdirs: true
                """.formatted(root.resolve("second.txt").toString().replace('\\', '/'))), false);
        second.start();
        try {
            Thread.sleep(300);
        } finally {
            second.stop();
        }
        assertFalse(Files.exists(root.resolve("second.txt")),
                "the dedupe key has not changed, so there was nothing to do");
    }

    @Test
    @DisplayName("an event arriving as shutdown begins is refused rather than started behind the "
            + "drain's back")
    void anEventArrivingDuringShutdownIsRefused() throws Exception {
        Path watched = Files.createDirectories(root.resolve("in"));
        Butler butler = butler(config(watched, """
                      - uses: fs.template
                        content: ran
                        to: %s
                        mkdirs: true
                """.formatted(root.resolve("out.txt").toString().replace('\\', '/'))), false);

        butler.start();
        butler.stop();

        // The watchers are stopped, but a poll already under way could still emit.
        Files.writeString(watched.resolve("late.txt"), "x");
        Thread.sleep(300);
        assertFalse(Files.exists(root.resolve("out.txt")),
                "a run started after the drain would be killed halfway by the exit that follows");
    }

    @Test
    @DisplayName("stopping twice is harmless, and a job with no events is still stopped cleanly")
    void stopsCleanly() throws IOException {
        Path watched = Files.createDirectories(root.resolve("in"));
        Butler butler = butler(config(watched, "      - {uses: control.log, message: hi}"), false);
        butler.start();
        butler.stop();
        butler.stop();
    }

    @Test
    @DisplayName("a job whose trigger will not start is reported, and the rest still run")
    void oneBadTriggerDoesNotStopTheDaemon() throws Exception {
        Path watched = Files.createDirectories(root.resolve("in"));
        Butler butler = butler("""
                settings:
                  state_dir: %s
                  poll_interval: 30ms
                jobs:
                  broken:
                    on: [{uses: schedule.cron, expression: not a cron}]
                    steps: [{uses: control.log, message: never}]
                  fine:
                    on:
                      - uses: file.appeared
                        dir: %s
                        settle: 50ms
                        on_startup: all
                    steps:
                      - uses: fs.template
                        content: ok
                        to: %s
                """.formatted(root.resolve("state").toString().replace('\\', '/'),
                watched.toString().replace('\\', '/'),
                root.resolve("out.txt").toString().replace('\\', '/')), false);

        butler.start();
        try {
            Files.writeString(watched.resolve("thing.txt"), "x");
            eventually("the working job to run", () -> Files.exists(root.resolve("out.txt")));
        } finally {
            butler.stop();
        }
    }
}
