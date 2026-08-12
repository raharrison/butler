package net.ryanh.butler.cli;

import net.ryanh.butler.runtime.Butler;
import picocli.AutoComplete;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * The top-level command, which is itself runnable: a bare {@code butler} starts the daemon, and
 * everything a human does interactively is a subcommand. That keeps the systemd unit to
 * {@code ExecStart=/usr/bin/butler --config ...}.
 */
@Command(
        name = "butler",
        header = "Watches for events on a host and runs declarative pipelines in response.",
        description = "Run with no subcommand to start the daemon.",
        mixinStandardHelpOptions = true,
        versionProvider = ButlerCommand.Version.class,
        sortOptions = false,
        subcommands = {
                ValidateCommand.class,
                CheckCommand.class,
                TriggerCommand.class,
                AdoptCommand.class,
                StepsCommand.class,
                // picocli's own, so completion cannot fall behind a new subcommand.
                AutoComplete.GenerateCompletion.class
        })
public final class ButlerCommand implements Callable<Integer> {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILURE = 1;
    public static final int EXIT_USAGE = 2;

    @Mixin
    ConfigMixin configOptions;

    @Option(names = "--check-only", hidden = true,
            description = "Validate and exit; used by tests of the daemon startup path.")
    boolean checkOnly;

    @Override
    public Integer call() {
        // Validate before anything else, so a broken config is refused rather than started with
        // and then silently watched for nothing.
        var result = configOptions.loadAndValidate();
        var diags = result.diagnostics();
        if (!diags.isEmpty()) {
            System.err.print(diags.render(configOptions.config().toString()));
        }
        if (diags.hasErrors()) {
            System.err.println("refusing to start with an invalid config");
            return EXIT_FAILURE;
        }
        if (checkOnly) {
            return EXIT_OK;
        }
        return daemon();
    }

    /**
     * Runs until the process is asked to stop. A JVM shutdown hook catches both the {@code TERM}
     * systemd sends and the {@code INT} a terminal sends, so there is one path rather than two.
     * Draining what is in flight is {@link Butler#stop}'s job.
     */
    private int daemon() {
        Logging.configure(configOptions.environment().config().settings().logFormat());
        Butler butler = new Butler(configOptions.environment(), configOptions.triggers(),
                configOptions.dryRun(), System.out);
        Runtime.getRuntime().addShutdownHook(new Thread(butler::stop, "butler-shutdown"));
        butler.start();
        try {
            butler.awaitShutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            butler.stop();
        }
        return EXIT_OK;
    }

    /**
     * Reads the version from the jar manifest, falling back to a dev marker.
     */
    public static final class Version implements IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = ButlerCommand.class.getPackage().getImplementationVersion();
            return new String[]{"butler " + (v == null ? "(dev)" : v)};
        }
    }
}
