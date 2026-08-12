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
 * The audit trail: one JSON document per run under {@code <state_dir>/runs/<date>/}, plus an
 * append-only {@code runs/index.jsonl} so history can be answered without walking the tree
 * (DESIGN.md §6.4).
 *
 * <p>Failing to write one never fails a run: the work was done, and losing the note about it is
 * worth a log line and no more.
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
     * The timestamp half of a run id, which dates a record without opening it.
     */
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);

    private final Path runsDir;
    private final ButlerConfig.RunRetention retention;

    private RunRecorder(Path runsDir, ButlerConfig.RunRetention retention) {
        this.runsDir = runsDir;
        this.retention = retention;
    }

    public static RunRecorder at(Path stateDir, ButlerConfig.RunRetention retention) {
        return new RunRecorder(stateDir.resolve("runs"), retention);
    }

    public Path fileFor(Run run) {
        return runsDir.resolve(DAY.format(run.startedAt())).resolve(run.id() + ".json");
    }

    public Path index() {
        return runsDir.resolve("index.jsonl");
    }

    /**
     * Writes the record and its index line, then prunes on a virtual thread so a run never waits on
     * retention.
     */
    public void record(Run run) {
        try {
            write(run);
        } catch (IOException | RuntimeException e) {
            log.error("could not record run {}: {}", run.id(), e.toString());
            return;
        }
        Thread.ofVirtual().name("run-retention").start(this::prune);
    }

    /**
     * Newest {@code count} records kept, anything older than {@code age} dropped whether or not the
     * count allows it. Synchronized with {@link #record} because both touch the index.
     */
    public synchronized void prune() {
        List<Path> records = records();
        Instant cutoff = retention.age() == null ? null : Instant.now().minus(retention.age());

        Set<String> kept = new LinkedHashSet<>();
        for (int i = 0; i < records.size(); i++) {
            Path file = records.get(i);
            String id = idOf(file);
            Instant started = startedAt(id);
            boolean tooMany = i >= retention.count();
            // A null started_at is a name this build did not write; keep what cannot be dated.
            boolean tooOld = cutoff != null && started != null && started.isBefore(cutoff);
            if (tooMany || tooOld) {
                delete(file);
            } else {
                kept.add(id);
            }
        }
        pruneEmptyDays();
        rewriteIndex(kept);
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
     * Every record on disk, newest first. A run id sorts as its timestamp does, so the order falls
     * out of the names without opening a file.
     */
    private List<Path> records() {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> days = Files.newDirectoryStream(runsDir, Files::isDirectory)) {
            for (Path day : days) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(day, "*.json")) {
                    files.forEach(found::add);
                }
            }
        } catch (IOException e) {
            log.error("could not list run records under {}: {}", runsDir, e.toString());
            return List.of();
        }
        found.sort(Comparator.comparing(RunRecorder::idOf).reversed());
        return found;
    }

    private void pruneEmptyDays() {
        try (DirectoryStream<Path> days = Files.newDirectoryStream(runsDir, Files::isDirectory)) {
            for (Path day : days) {
                if (!DAY_DIR.matcher(day.getFileName().toString()).matches()) {
                    continue;
                }
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
     * Rewrites the index to the records that survived, so a reader never sees a line pointing at a
     * record that has been deleted.
     */
    private void rewriteIndex(Set<String> kept) {
        Path index = index();
        if (!Files.isReadable(index)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
            List<String> survivors = lines.stream()
                    .filter(line -> kept.contains(idIn(line)))
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

    private static String idOf(Path record) {
        String name = record.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    /**
     * @return the id in one index line, or null if the line is not a record
     */
    private static String idIn(String line) {
        try {
            Object parsed = LINES.readValue(line, Map.class);
            return parsed instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * @return when the run started, read from the timestamp half of its id, or null if that is not
     * a run id this build wrote
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
