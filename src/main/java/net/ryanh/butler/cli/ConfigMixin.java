package net.ryanh.butler.cli;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.config.ConfigValidator;
import net.ryanh.butler.runtime.RegistryValidator;
import net.ryanh.butler.runtime.StepRegistry;
import net.ryanh.butler.runtime.TriggerRegistry;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Options shared by every command, so they all read the same config the daemon would.
 */
public final class ConfigMixin {

    private StepRegistry steps;
    private TriggerRegistry triggers;

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
     * The step types this build can run. Loaded once per command.
     */
    public StepRegistry steps() {
        if (steps == null) {
            steps = StepRegistry.discover();
        }
        return steps;
    }

    /**
     * The trigger types this build can watch with. Loaded once per command.
     */
    public TriggerRegistry triggers() {
        if (triggers == null) {
            triggers = TriggerRegistry.discover();
        }
        return triggers;
    }

    /**
     * Loads and fully validates, without reporting. Returns whatever could be built.
     *
     * <p>Validation is both passes: structure and expressions, then everything that needs the
     * registries. A command that skipped the second would accept a config the daemon cannot run.
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
        RegistryValidator.validate(result.config(), steps(), triggers(), result.diagnostics());
        return result;
    }
}
