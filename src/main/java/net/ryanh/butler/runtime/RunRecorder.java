package net.ryanh.butler.runtime;

import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
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


    // --------------------------------------------------------------------------- reading

    /**
     * One line of {@code runs/index.jsonl}, which is also the head of the full record.
     */
    public record Summary(String id, String job, String trigger, Run.Status status,
                          Instant startedAt, Duration duration, String failedStep, String message) {
    }

    /**
     * Every run the index still carries, in the order it was written, which is the order runs
     * finished.
     *
     * <p>A line this build cannot read is skipped rather than fatal: history is an account of what
     * happened, and one unreadable entry must not hide the rest of it.
     */
    public List<Summary> history() {
        Path index = index();
        if (!Files.isReadable(index)) {
            return List.of();
        }
        List<Summary> found = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                Summary summary = summary(line);
                if (summary != null) {
                    found.add(summary);
                }
            }
        } catch (IOException e) {
            log.error("could not read {}: {}", index, e.toString());
            return List.of();
        }
        return List.copyOf(found);
    }

    /**
     * The full record for one run, or null if none survives under that id. The id carries the day
     * it started, so this opens one directory rather than walking the tree.
     *
     * @throws RuntimeException if the record exists but cannot be read
     */
    public Run read(String id) {
        // Checked before it reaches the directory glob below, where a wildcard would match a
        // record the caller did not ask for.
        if (id == null || !RECORD.matcher(id + ".json").matches()) {
            return null;
        }
        Instant started = startedAt(id);
        if (started == null) {
            return null;
        }
        Path day = runsDir.resolve(DAY.format(started));
        if (!Files.isDirectory(day)) {
            return null;
        }
        String suffix = "-" + id + ".json";
        try (DirectoryStream<Path> files = Files.newDirectoryStream(day, "*" + suffix)) {
            for (Path file : files) {
                return run(LINES.readValue(Files.readString(file, StandardCharsets.UTF_8),
                        Map.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the record for run " + id, e);
        }
        return null;
    }

    /**
     * The reverse of {@link #summary(Run)}. A line missing what identifies a run, or carrying a
     * status this build does not know, is not one.
     */
    private static Summary summary(String line) {
        try {
            if (!(LINES.readValue(line, Map.class) instanceof Map<?, ?> m)) {
                return null;
            }
            Run.Status status = status(m.get("status"), Run.Status.class);
            Instant started = m.get("started_at") == null
                    ? null : Instant.parse(String.valueOf(m.get("started_at")));
            if (m.get("id") == null || m.get("job") == null || status == null || started == null) {
                return null;
            }
            return new Summary(str(m.get("id")), str(m.get("job")), str(m.get("trigger")), status,
                    started, Duration.ofMillis(number(m.get("duration_ms"))),
                    str(m.get("failed_step")), str(m.get("message")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The reverse of {@link #document(Run)}. Durations round-trip exactly, because
     * {@link Durations#format} picks the largest unit that divides them.
     */
    private static Run run(Map<?, ?> doc) {
        List<Plan.Entry> discover = new ArrayList<>();
        int number = 0;
        for (Object o : list(doc.get("discover"))) {
            Map<?, ?> e = (Map<?, ?>) o;
            String skipped = str(e.get("skipped"));
            String error = str(e.get("error"));
            // The renderer numbers what ran and dashes what did not, as Discovery did.
            boolean ran = skipped == null && error == null;
            discover.add(new Plan.Entry("discover", ran ? ++number : 0, str(e.get("label")),
                    str(e.get("uses")), strings(e.get("observed")), List.of(), skipped, error));
        }

        List<Run.Step> steps = new ArrayList<>();
        for (Object o : list(doc.get("steps"))) {
            Map<?, ?> s = (Map<?, ?>) o;
            steps.add(new Run.Step(str(s.get("section")), str(s.get("label")), str(s.get("uses")),
                    status(s.get("status"), StepResult.Status.class),
                    Durations.parse(str(s.get("duration"))), (int) number(s.get("attempts")),
                    str(s.get("message"))));
        }

        Map<?, ?> when = doc.get("when") instanceof Map<?, ?> m ? m : null;
        Map<?, ?> notified = doc.get("notified") instanceof Map<?, ?> m ? m : null;

        return new Run(str(doc.get("id")), str(doc.get("job")), str(doc.get("trigger")),
                values(doc.get("facts")), status(doc.get("status"), Run.Status.class),
                Instant.parse(str(doc.get("started_at"))),
                Duration.ofMillis(number(doc.get("duration_ms"))),
                List.copyOf(discover),
                when == null ? null : new Plan.Decision(str(when.get("source")),
                        str(when.get("explained")), Boolean.TRUE.equals(when.get("result")),
                        str(when.get("error"))),
                List.copyOf(steps), values(doc.get("persisted")),
                notified == null ? null : new Plan.Notification(strings(notified.get("to")),
                        str(notified.get("message"))),
                str(doc.get("failed_step")), str(doc.get("message")));
    }

    private static <E extends Enum<E>> E status(Object value, Class<E> type) {
        try {
            return value == null ? null
                    : Enum.valueOf(type, String.valueOf(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static List<String> strings(Object value) {
        return list(value).stream().map(RunRecorder::str).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Object value) {
        return value instanceof Map<?, ?> m
                ? Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) m))
                : Map.of();
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
