package net.ryanh.butler.cli;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.config.ConfigValidator;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Options shared by every command, so they all read the same config the daemon would.
 */
public final class ConfigMixin {

    @Option(names = {"-c", "--config"},
            paramLabel = "<file>",
            defaultValue = "/etc/butler/butler.yaml",
            description = "Config file to read. Default: ${DEFAULT-VALUE}")
    Path config;

    @Option(names = "--dry-run",
            description = "Report what would happen without changing anything.")
    boolean dryRun;

    public Path config() {
        return config;
    }

    public boolean dryRun() {
        return dryRun;
    }

    /**
     * Loads and fully validates, without reporting. Returns whatever could be built.
     */
    public ConfigLoader.Result loadAndValidate() {
        if (!Files.exists(config)) {
            throw new IllegalStateException("no such config file: " + config);
        }
        if (Files.isDirectory(config)) {
            throw new IllegalStateException("config path is a directory: " + config);
        }
        ConfigLoader.Result result;
        try {
            result = ConfigLoader.load(config);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + config, e);
        }
        ConfigValidator.validate(result.config(), result.diagnostics());
        return result;
    }
}
