package net.ryanh.butler.config.model;

import java.nio.file.Path;
import java.time.Duration;
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
            RunRetention runRetention,
            Path pluginsDir) {

        public static Settings defaults() {
            return new Settings(
                    Path.of("/var/lib/butler"),
                    Enums.LogFormat.JSON,
                    4,
                    Duration.ofSeconds(5),
                    new RunRetention(200, Duration.ofDays(30)),
                    null);
        }
    }

    public record RunRetention(int count, Duration age) {
    }

    public record SecretsConfig(boolean fromEnv, Path file) {
        public static SecretsConfig defaults() {
            return new SecretsConfig(true, null);
        }
    }
}
