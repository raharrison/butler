package net.ryanh.butler.cli;

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
                StepsCommand.class
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
        // Validate before anything else: a daemon that starts on a broken config and silently
        // does nothing is worse than one that refuses to start.
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
        System.err.println("the daemon is not implemented yet (milestone M4)");
        System.err.println("try: butler validate | butler check");
        return EXIT_FAILURE;
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
