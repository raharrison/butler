package net.ryanh.butler.cli;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.config.ConfigValidator;
import net.ryanh.butler.config.Vocabulary;
import net.ryanh.butler.runtime.*;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Options shared by every command, so they all read the same config the daemon would.
 */
public final class ConfigMixin {

    public static final Path DEFAULT_CONFIG = Path.of("/etc/butler/butler.yaml");

    private StepRegistry steps;
    private TriggerRegistry triggers;
    private NotifierRegistry notifiers;
    private RunEnvironment environment;
    private ClassLoader extensions = ConfigMixin.class.getClassLoader();

    @Option(names = {"-c", "--config"},
            paramLabel = "<file>",
            arity = "1",
            description = "Config file to read. Repeat it to spread one config over several "
                    + "files, merged in the order given. Default: /etc/butler/butler.yaml")
    List<Path> config = new ArrayList<>();

    @Option(names = "--dry-run",
            description = "Report what would happen without changing anything.")
    boolean dryRun;

    /**
     * The config files to read, in order. Repeats are dropped: a file named twice would duplicate
     * every job in it.
     */
    public List<Path> configs() {
        if (config.isEmpty()) {
            return List.of(DEFAULT_CONFIG);
        }
        Set<Path> seen = new LinkedHashSet<>();
        List<Path> unique = new ArrayList<>(config.size());
        for (Path path : config) {
            if (seen.add(path.toAbsolutePath().normalize())) {
                unique.add(path);
            }
        }
        return List.copyOf(unique);
    }

    /**
     * The config files as one string, for a message about all of them.
     */
    public String describe() {
        return String.join(", ", configs().stream().map(Path::toString).toList());
    }

    public boolean dryRun() {
        return dryRun;
    }

    /**
     * The step types this build can run. Loaded once per command.
     */
    public StepRegistry steps() {
        if (steps == null) {
            steps = StepRegistry.discover(extensions);
        }
        return steps;
    }

    /**
     * The trigger types this build can watch with. Loaded once per command.
     */
    public TriggerRegistry triggers() {
        if (triggers == null) {
            triggers = TriggerRegistry.discover(extensions);
        }
        return triggers;
    }

    /**
     * The notification channels this build can send through. Loaded once per command.
     */
    public NotifierRegistry notifiers() {
        if (notifiers == null) {
            notifiers = NotifierRegistry.discover(extensions);
        }
        return notifiers;
    }

    /**
     * What a run needs from outside itself, for the config just loaded. Built during
     * {@link #loadAndValidate}, so a secrets file that cannot be read is one of the errors a
     * command reports rather than something a run discovers by resolving every secret to null.
     */
    public RunEnvironment environment() {
        if (environment == null) {
            throw new IllegalStateException("the config has not been loaded yet");
        }
        return environment;
    }

    /**
     * Loads and fully validates, without reporting. Returns whatever could be built.
     *
     * <p>Validation is both passes: structure and expressions, then everything that needs the
     * registries. A command that skipped the second would accept a config the daemon cannot run.
     */
    public ConfigLoader.Result loadAndValidate() {
        List<Path> files = configs();
        for (Path file : files) {
            if (!Files.exists(file)) {
                throw new IllegalStateException("no such config file: " + file);
            }
            if (Files.isDirectory(file)) {
                throw new IllegalStateException("config path is a directory: " + file);
            }
        }
        ConfigLoader.Result result;
        try {
            result = ConfigLoader.load(files);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + describe(), e);
        }
        // Before the registries are built, or a config naming a third-party step would be judged
        // against a vocabulary that does not have it yet.
        if (result.config() != null) {
            extensions = Plugins.loader(result.config().settings().pluginsDir(),
                    result.diagnostics());
        }
        ConfigValidator.validate(result.config(), result.diagnostics(),
                Vocabulary.of(steps().vocabulary(), triggers().vocabulary()));
        RegistryValidator.validate(result.config(), steps(), triggers(), notifiers(),
                result.diagnostics());
        if (result.config() != null) {
            environment = RunEnvironment.of(result.config(), steps(), notifiers(),
                    result.diagnostics());
        }
        return result;
    }
}
