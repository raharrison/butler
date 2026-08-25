package net.ryanh.butler.config.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The whole config document.
 */
public record ButlerConfig(
        int version,
        Settings settings,
        SecretsConfig secrets,
        Map<String, Object> vars,
        Map<String, NotifierDef> notifiers,
        Map<String, JobDef> jobs) {

    /**
     * How much history to keep for one job: what it set, and the global default for the rest.
     */
    public RunRetention retentionFor(JobDef job) {
        return job.runRetention().or(settings.runRetention());
    }

    /**
     * How long one job may take: its own {@code timeout:}, or the daemon-wide default. Every run
     * is bounded, because an unbounded one holds a concurrency permit for as long as it hangs.
     */
    public Duration timeoutFor(JobDef job) {
        return job.timeout() == null ? settings.defaultJobTimeout() : job.timeout();
    }

    public record Settings(
            Path stateDir,
            Enums.LogFormat logFormat,
            int maxConcurrentRuns,
            Duration pollInterval,
            Duration shutdownGrace,
            Duration defaultJobTimeout,
            RunRetention runRetention,
            Path pluginsDir) {

        public static Settings defaults() {
            return new Settings(
                    Path.of("/var/lib/butler"),
                    Enums.LogFormat.JSON,
                    4,
                    Duration.ofSeconds(5),
                    Duration.ofMinutes(2),
                    Duration.ofHours(1),
                    new RunRetention(200, Duration.ofDays(30)),
                    null);
        }
    }

    /**
     * A null field is not set here; {@link RunRetention#or} fills it from the fallback.
     */
    public record RunRetention(Integer count, Duration age) {

        public RunRetention or(RunRetention fallback) {
            return new RunRetention(count == null ? fallback.count() : count,
                    age == null ? fallback.age() : age);
        }
    }

    /**
     * @param fromEnv null when the file did not say; {@link #or} fills it from the fallback
     * @param files   read in order and merged; a name may be defined in only one of them
     */
    public record SecretsConfig(Boolean fromEnv, List<Path> files) {

        public SecretsConfig {
            // Distinct: the same file named by two config files would collide with itself.
            files = files == null ? List.of() : List.copyOf(new LinkedHashSet<>(files));
        }

        public SecretsConfig or(SecretsConfig fallback) {
            List<Path> all = new ArrayList<>(fallback.files());
            all.addAll(files);
            return new SecretsConfig(fromEnv == null ? fallback.fromEnv() : fromEnv, all);
        }

        public static SecretsConfig defaults() {
            return new SecretsConfig(true, List.of());
        }
    }
}
