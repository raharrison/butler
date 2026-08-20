package net.ryanh.butler.config.model;

import java.nio.file.Path;
import java.time.Duration;
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

    public record Settings(
            Path stateDir,
            Enums.LogFormat logFormat,
            int maxConcurrentRuns,
            Duration pollInterval,
            Duration shutdownGrace,
            RunRetention runRetention,
            Path pluginsDir) {

        public static Settings defaults() {
            return new Settings(
                    Path.of("/var/lib/butler"),
                    Enums.LogFormat.JSON,
                    4,
                    Duration.ofSeconds(5),
                    Duration.ofMinutes(2),
                    new RunRetention(200, Duration.ofDays(30)),
                    null);
        }
    }

    public record RunRetention(int count, Duration age) {
    }

    /**
     * @param files read in order and merged; a name may be defined in only one of them
     */
    public record SecretsConfig(boolean fromEnv, List<Path> files) {
        public static SecretsConfig defaults() {
            return new SecretsConfig(true, List.of());
        }
    }
}
