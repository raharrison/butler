package net.ryanh.butler.runtime;

import net.ryanh.butler.util.Literals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Butler remembers between runs: one JSON file per job under {@code <state_dir>/jobs/}
 * (DESIGN.md §6.4).
 *
 * <p>State is a cache of host reality, not the truth, which is why an unreadable file is a log line
 * rather than a refusal to start: discovery re-derives it on the next event (DESIGN.md §6.1).
 */
public final class StateStore {

    private static final Logger log = LoggerFactory.getLogger(StateStore.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final Path stateDir;

    private StateStore(Path stateDir) {
        this.stateDir = stateDir;
    }

    public static StateStore at(Path stateDir) {
        return new StateStore(stateDir);
    }

    /**
     * What a job knows: the last event it processed, when it last ran, and every value its
     * {@code persist:} and {@code discover:} blocks produced.
     *
     * <p>The values are nested rather than beside {@code dedupeKey} so that a job may persist a key
     * called {@code dedupe_key} without overwriting the bookkeeping.
     */
    public record JobState(String dedupeKey, Instant lastRun, Map<String, Object> values) {

        public JobState {
            values = values == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public static JobState empty() {
            return new JobState(null, null, Map.of());
        }
    }

    public Path fileFor(String job) {
        return stateDir.resolve("jobs").resolve(fileName(job));
    }

    /**
     * Job names are YAML keys and could hold anything; a state file has to be one path segment.
     */
    private static String fileName(String job) {
        StringBuilder sb = new StringBuilder(job.length() + 5);
        for (char c : job.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return sb.append(".json").toString();
    }

    /**
     * Reads a job's state, or empty state if there is none to read or it cannot be parsed.
     */
    public JobState read(String job) {
        Path file = fileFor(job);
        if (!Files.isReadable(file)) {
            return JobState.empty();
        }
        Map<String, Object> raw;
        try {
            raw = MAPPER.readValue(Files.readString(file),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (IOException | RuntimeException e) {
            log.error("state file {} could not be read ({}); continuing as if the job had no "
                    + "state, which discovery will re-derive", file, e.toString());
            return JobState.empty();
        }
        if (raw == null) {
            return JobState.empty();
        }
        return new JobState(asString(raw.get("dedupe_key")), instant(raw.get("last_run")),
                values(raw.get("state")));
    }

    /**
     * Writes a job's state, replacing whatever was there.
     */
    public void write(String job, JobState state) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("dedupe_key", state.dedupeKey());
        document.put("last_run", state.lastRun() == null ? null : state.lastRun().toString());
        document.put("state", storable(state.values()));

        Atomically.write(fileFor(job), MAPPER.writeValueAsString(document) + "\n");
    }

    /**
     * A value the next run will read back as the one that was written.
     *
     * <p>An expression yields more than JSON has scalars for: {@code semver(...)} produces a
     * version and {@code ${30s}} a duration, and databind would write the first as {@code {}} and
     * the second in a syntax {@code Durations} refuses. Both are stored as the text they render
     * to, which is what the run report showed and what a condition compares against anyway.
     */
    private static Object storable(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), storable(v)));
                yield out;
            }
            case List<?> l -> l.stream().map(StateStore::storable).toList();
            default -> Literals.text(value);
        };
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Object value) {
        return value instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of();
    }
}
