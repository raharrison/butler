package net.ryanh.butler.runtime;

import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The audit trail: one JSON document per run at {@code <state_dir>/runs/<date>/<job>-<id>.json},
 * plus an append-only {@code runs/index.jsonl} so history can be answered without walking the
 * tree (DESIGN.md §6.4).
 */
public final class RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(RunRecorder.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /**
     * Not indented: one line per run is what makes the index greppable.
     */
    private static final ObjectMapper LINES = JsonMapper.builder().build();

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final Pattern DAY_DIR = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * What follows the job in a record's name. Checked, so that pruning {@code api} cannot reach
     * {@code api-v2}'s records.
     */
    private static final Pattern RECORD = Pattern.compile("\\d{8}T\\d{6}-[0-9a-f]{4}\\.json");

    /**
     * The timestamp half of a run id, which dates a record without opening it.
     */
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);

    private final Path runsDir;

    private RunRecorder(Path runsDir) {
        this.runsDir = runsDir;
    }

    public static RunRecorder at(Path stateDir) {
        return new RunRecorder(stateDir.resolve("runs"));
    }

    public Path fileFor(Run run) {
        return runsDir.resolve(DAY.format(run.startedAt()))
                .resolve(run.job() + "-" + run.id() + ".json");
    }

    public Path index() {
        return runsDir.resolve("index.jsonl");
    }

    /**
     * Neither failure ever fails the run that produced it. Pruning is on the caller's thread: a
     * thread nobody waits for never runs under {@code butler trigger}, where the process exits
     * first.
     */
    public void record(Run run, ButlerConfig.RunRetention retention) {
        try {
            write(run);
        } catch (IOException | RuntimeException e) {
            log.error("could not record run {}: {}", run.id(), e.toString());
            return;
        }
        try {
            prune(run.job(), retention);
        } catch (RuntimeException e) {
            log.error("could not apply run retention: {}", e.toString());
        }
    }

    /**
     * Newest {@code count} of this job's records kept, anything older than {@code age} dropped;
     * other jobs untouched. Synchronized with {@link #record}: both touch the index.
     */
    public synchronized void prune(String job, ButlerConfig.RunRetention retention) {
        List<Path> records = records(job);
        Instant cutoff = retention.age() == null ? null : Instant.now().minus(retention.age());

        Set<String> kept = new LinkedHashSet<>();
        for (int i = 0; i < records.size(); i++) {
            Path file = records.get(i);
            String id = idOf(file, job);
            Instant started = startedAt(id);
            boolean tooMany = retention.count() != null && i >= retention.count();
            // A name this build did not write cannot be dated; keep it rather than guess.
            boolean tooOld = cutoff != null && started != null && started.isBefore(cutoff);
            if (tooMany || tooOld) {
                delete(file);
            } else {
                kept.add(id);
            }
        }
        pruneEmptyDays();
        reindex(job, kept);
    }

    // --------------------------------------------------------------------------- writing

    private synchronized void write(Run run) throws IOException {
        Atomically.write(fileFor(run), MAPPER.writeValueAsString(document(run)) + "\n");
        Files.writeString(index(), LINES.writeValueAsString(summary(run)) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static Map<String, Object> document(Run run) {
        Map<String, Object> doc = new LinkedHashMap<>(summary(run));
        doc.put("facts", run.facts());
        doc.put("discover", run.discover().stream().map(RunRecorder::entry).toList());
        doc.put("when", run.decision() == null ? null : decision(run.decision()));
        doc.put("steps", run.steps().stream().map(RunRecorder::step).toList());
        doc.put("persisted", run.persisted());
        doc.put("notified", run.notification() == null ? null
                : Map.of("to", run.notification().to(), "message", run.notification().message()));
        return doc;
    }

    /**
     * The index line, and the head of the full record, so the two cannot disagree.
     */
    private static Map<String, Object> summary(Run run) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", run.id());
        line.put("job", run.job());
        line.put("trigger", run.trigger());
        line.put("status", run.status().toString());
        line.put("started_at", run.startedAt().toString());
        line.put("duration", Durations.format(run.duration()));
        line.put("duration_ms", run.duration().toMillis());
        line.put("failed_step", run.failedStep());
        line.put("message", run.message());
        return line;
    }

    private static Map<String, Object> entry(Plan.Entry e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", e.label());
        out.put("uses", e.uses());
        out.put("observed", e.body());
        out.put("skipped", e.skipped());
        out.put("error", e.error());
        return out;
    }

    private static Map<String, Object> decision(Plan.Decision d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", d.source());
        out.put("explained", d.explained());
        out.put("result", d.result());
        out.put("error", d.error());
        return out;
    }

    private static Map<String, Object> step(Run.Step s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("section", s.section());
        out.put("label", s.label());
        out.put("uses", s.uses());
        out.put("status", s.status().toString());
        out.put("duration", Durations.format(s.duration() == null ? Duration.ZERO : s.duration()));
        out.put("attempts", s.attempts());
        out.put("message", s.message());
        return out;
    }

    // --------------------------------------------------------------------------- retention

    /**
     * One job's records, newest first: a run id sorts as its timestamp does.
     */
    private List<Path> records(String job) {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        // Matched rather than globbed: a job name is a config key, not a glob.
        String prefix = job + "-";
        try (DirectoryStream<Path> days = Files.newDirectoryStream(runsDir, RunRecorder::isDay)) {
            for (Path day : days) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(day, "*.json")) {
                    files.forEach(file -> {
                        if (isRecordOf(file, prefix)) {
                            found.add(file);
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.error("could not list run records under {}: {}", runsDir, e.toString());
            return List.of();
        }
        found.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return found;
    }

    private static boolean isRecordOf(Path file, String prefix) {
        String name = file.getFileName().toString();
        return name.startsWith(prefix)
                && RECORD.matcher(name.substring(prefix.length())).matches();
    }

    private static boolean isDay(Path path) {
        return Files.isDirectory(path)
                && DAY_DIR.matcher(path.getFileName().toString()).matches();
    }

    private void pruneEmptyDays() {
        try (DirectoryStream<Path> days = Files.newDirectoryStream(runsDir, RunRecorder::isDay)) {
            for (Path day : days) {
                try (DirectoryStream<Path> contents = Files.newDirectoryStream(day)) {
                    if (!contents.iterator().hasNext()) {
                        Files.delete(day);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("could not tidy empty run directories under {}: {}", runsDir, e.toString());
        }
    }

    /**
     * Drops this job's lines for records no longer on disk. Only this job's: the index is shared.
     */
    private void reindex(String job, Set<String> kept) {
        Path index = index();
        if (!Files.isReadable(index)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
            List<String> survivors = lines.stream()
                    .filter(line -> !isGone(line, job, kept))
                    .toList();
            if (survivors.size() == lines.size()) {
                return;
            }
            Atomically.write(index, survivors.isEmpty() ? ""
                    : String.join("\n", survivors) + "\n");
        } catch (IOException | RuntimeException e) {
            log.error("could not prune {}: {}", index, e.toString());
        }
    }

    private void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("could not delete run record {}: {}", file, e.toString());
        }
    }

    /**
     * Split by the known job name, since a job name may itself contain a hyphen.
     */
    private static String idOf(Path record, String job) {
        String name = record.getFileName().toString();
        return name.substring(job.length() + 1, name.length() - ".json".length());
    }

    /**
     * A line that will not parse names no job, so it survives.
     */
    private static boolean isGone(String line, String job, Set<String> onDisk) {
        try {
            return LINES.readValue(line, Map.class) instanceof Map<?, ?> m
                    && job.equals(m.get("job")) && !onDisk.contains(m.get("id"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * @return the timestamp half of the id, or null if the id is not one this build wrote
     */
    private static Instant startedAt(String id) {
        int dash = id.indexOf('-');
        try {
            return LocalDateTime.parse(dash < 0 ? id : id.substring(0, dash), RUN_ID)
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
