package net.ryanh.butler.runtime;

import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.spi.StepResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The audit trail of DESIGN.md §6.4: a record per run, an index to find it by, and retention that
 * keeps the newest.
 */
class RunRecorderTest {

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    /**
     * Wide enough that writing never prunes.
     */
    private static final ButlerConfig.RunRetention KEEP_EVERYTHING =
            new ButlerConfig.RunRetention(10_000, Duration.ofDays(3650));

    @TempDir
    Path stateDir;

    private RunRecorder recorder() {
        return RunRecorder.at(stateDir);
    }

    private static ButlerConfig.RunRetention keep(int count) {
        return new ButlerConfig.RunRetention(count, Duration.ofDays(3650));
    }

    private static Run run(Instant started, Run.Status status) {
        return run("api", started, status);
    }

    private static Run run(String job, Instant started, Run.Status status) {
        return new Run(RUN_ID.format(started) + "-a1b2", job, "file.appeared",
                Map.of("version", "1.2.4"), status, started, Duration.ofSeconds(12),
                List.of(new Plan.Entry("discover", 1, "Ask the service", "http.request",
                        List.of("state.deployed_version = \"1.2.3\""), List.of(), null, null)),
                new Plan.Decision("semver(trigger.version) > semver(state.deployed_version)",
                        "semver(\"1.2.4\") > semver(\"1.2.3\")", true, null),
                List.of(new Run.Step("step", "Stage the release", "fs.copy",
                        StepResult.Status.OK, Duration.ofMillis(80), 1, null)),
                Map.of("deployed_version", "1.2.4"),
                new Plan.Notification("ops", "api 1.2.4 deployed"), null, null);
    }

    private List<Path> records() throws IOException {
        try (Stream<Path> found = Files.walk(stateDir.resolve("runs"))) {
            return found.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private List<String> index() throws IOException {
        Path index = stateDir.resolve("runs/index.jsonl");
        return Files.exists(index) ? Files.readAllLines(index) : List.of();
    }

    /**
     * Each {@code record} prunes before it returns, so what is on disk afterwards is settled.
     */
    private static void write(RunRecorder recorder, ButlerConfig.RunRetention retention,
                              Instant... at) {
        write(recorder, "api", retention, at);
    }

    private static void write(RunRecorder recorder, String job,
                              ButlerConfig.RunRetention retention, Instant... at) {
        for (Instant when : at) {
            recorder.record(run(job, when, Run.Status.SUCCESS), retention);
        }
    }

    @Test
    @DisplayName("a record holds enough to answer what happened without the logs")
    void theRecordIsTheWholeRun() throws IOException {
        Instant started = Instant.parse("2026-08-09T03:14:07Z");
        Run run = run(started, Run.Status.SUCCESS);
        recorder().record(run, KEEP_EVERYTHING);

        Path file = stateDir.resolve("runs/2026-08-09/api-20260809T031407-a1b2.json");
        assertTrue(Files.exists(file),
                "a record lands under its own date, named for its job: " + records());

        String json = Files.readString(file);
        assertTrue(json.contains("\"status\" : \"success\""), json);
        assertTrue(json.contains("\"job\" : \"api\""), json);
        assertTrue(json.contains("state.deployed_version = \\\"1.2.3\\\""),
                "what discovery observed:\n" + json);
        assertTrue(json.contains("semver(\\\"1.2.4\\\") > semver(\\\"1.2.3\\\")"),
                "the decision, with both sides shown:\n" + json);
        assertTrue(json.contains("\"Stage the release\""), json);
        assertTrue(json.contains("\"duration\" : \"12s\""), json);
        assertTrue(json.contains("\"deployed_version\" : \"1.2.4\""), json);
        assertTrue(json.contains("\"to\" : \"ops\""), json);
    }

    @Test
    @DisplayName("the index carries one line per run, for history without walking the tree")
    void indexIsOneLinePerRun() throws IOException {
        write(recorder(), KEEP_EVERYTHING,
                Instant.parse("2026-08-09T03:14:07Z"), Instant.parse("2026-08-10T09:00:00Z"));

        List<String> lines = index();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.getFirst().startsWith("{"), lines.getFirst());
        assertFalse(lines.getFirst().contains("\n"));
        assertTrue(lines.get(1).contains("\"id\":\"20260810T090000-a1b2\""), lines.get(1));
        assertTrue(lines.get(1).contains("\"duration_ms\":12000"), lines.get(1));
    }

    @Test
    @DisplayName("retention by count keeps the newest and drops the rest")
    void prunesByCount() throws IOException {
        Instant base = Instant.parse("2026-08-09T00:00:00Z");
        RunRecorder recorder = recorder();
        write(recorder, keep(2), base, base.plusSeconds(60), base.plusSeconds(120),
                base.plusSeconds(180), base.plusSeconds(240));

        List<String> kept = records().stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("api-20260809T000300-a1b2.json", "api-20260809T000400-a1b2.json"),
                kept);
        assertEquals(2, index().size(), "the index is pruned to match: " + index());
    }

    @Test
    @DisplayName("retention by age drops the old without touching the newest")
    void prunesByAge() throws IOException {
        Instant now = Instant.now();
        write(recorder(), new ButlerConfig.RunRetention(10_000, Duration.ofDays(30)),
                now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(10)), now);

        List<Path> kept = records();
        assertEquals(2, kept.size(), kept.toString());
        assertTrue(kept.stream().anyMatch(p -> p.getFileName().toString()
                .startsWith("api-" + RUN_ID.format(now))), "the newest survives: " + kept);
        assertEquals(2, index().size());
    }

    @Test
    @DisplayName("an emptied date directory is tidied away")
    void emptyDaysGo() throws IOException {
        Instant base = Instant.parse("2026-08-09T00:00:00Z");
        write(recorder(), keep(1), base, base.plus(Duration.ofDays(1)));

        assertFalse(Files.exists(stateDir.resolve("runs/2026-08-09")));
        assertTrue(Files.exists(stateDir.resolve("runs/2026-08-10")));
    }

    @Test
    @DisplayName("pruning an empty state directory is a no-op rather than a failure")
    void nothingToPrune() {
        assertDoesNotThrow(() -> recorder().prune("api", KEEP_EVERYTHING));
    }

    @Test
    @DisplayName("a record that cannot be written never fails the run that produced it")
    void aFailedWriteIsLoggedAndNoMore() throws IOException {
        // A file where the runs directory should be: nothing can be created underneath it.
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("runs"), "not a directory");

        assertDoesNotThrow(() -> recorder()
                .record(run(Instant.now(), Run.Status.SUCCESS), KEEP_EVERYTHING));
    }

    @Test
    @DisplayName("a record deleted by hand loses its index line at the next prune")
    void theIndexHealsItself() throws IOException {
        Instant base = Instant.parse("2026-08-09T00:00:00Z");
        RunRecorder recorder = recorder();
        write(recorder, KEEP_EVERYTHING, base, base.plusSeconds(60));

        Files.delete(stateDir.resolve("runs/2026-08-09/api-20260809T000000-a1b2.json"));
        recorder.prune("api", KEEP_EVERYTHING);

        assertEquals(1, index().size(), index().toString());
        assertTrue(index().getFirst().contains("20260809T000100-a1b2"), index().toString());
    }

    @Test
    @DisplayName("an index line that will not parse is left alone")
    void unparseableIndexLinesSurvive() throws IOException {
        Instant base = Instant.parse("2026-08-09T00:00:00Z");
        RunRecorder recorder = recorder();
        write(recorder, keep(1), base, base.plusSeconds(60));

        Path index = stateDir.resolve("runs/index.jsonl");
        Files.writeString(index, "not json at all\n" + Files.readString(index));
        recorder.prune("api", keep(1));

        assertEquals("not json at all", index().getFirst(), index().toString());
    }

    @Test
    @DisplayName("one job's name being another's prefix does not mix their records")
    void jobNamesThatSharePrefixes() throws IOException {
        Instant base = Instant.parse("2026-08-09T00:00:00Z");
        RunRecorder recorder = recorder();

        write(recorder, "api-v2", keep(10), base, base.plusSeconds(60));
        write(recorder, "api", keep(1), base.plusSeconds(10), base.plusSeconds(20));

        List<String> kept = records().stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of(
                        "api-20260809T000020-a1b2.json",
                        "api-v2-20260809T000000-a1b2.json",
                        "api-v2-20260809T000100-a1b2.json"), kept,
                "pruning api to one record must not touch api-v2");
        assertEquals(3, index().size(), index().toString());
    }

    @Test
    @DisplayName("a noisy job cannot push a quiet one's history out")
    void retentionIsPerJob() throws IOException {
        Instant base = Instant.parse("2026-08-09T00:00:00Z");
        RunRecorder recorder = recorder();

        write(recorder, "deploy", keep(10), base, base.plusSeconds(60));
        write(recorder, "heartbeat", keep(1),
                base.plusSeconds(10), base.plusSeconds(20), base.plusSeconds(30));

        List<String> kept = records().stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of(
                "deploy-20260809T000000-a1b2.json",
                "deploy-20260809T000100-a1b2.json",
                "heartbeat-20260809T000030-a1b2.json"), kept);
        assertEquals(3, index().size(), "and the index keeps their lines too: " + index());
    }
}
