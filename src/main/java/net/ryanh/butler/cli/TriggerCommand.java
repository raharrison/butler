package net.ryanh.butler.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Not implemented until M3. Registered now so {@code --help} is honest about what is coming.
 */
@Command(
        name = "trigger",
        header = "Run a job once against the event its triggers would produce.",
        description = "Evaluates the job's configured triggers and runs it against the resulting "
                + "event. Combine with --dry-run to rehearse a pipeline. (Arrives in M3.)",
        mixinStandardHelpOptions = true)
public final class TriggerCommand implements Callable<Integer> {

    @Mixin
    ConfigMixin configOptions;

    @Parameters(index = "0", paramLabel = "<job>", description = "Job to run.")
    String job;

    @Option(names = "--set", paramLabel = "<key=value>",
            description = "Override or supply a trigger fact.")
    Map<String, String> facts = Map.of();

    @Override
    public Integer call() {
        var result = configOptions.loadAndValidate();
        var diags = result.diagnostics();
        if (!diags.isEmpty()) {
            System.err.print(diags.render(configOptions.config().toString()));
        }
        if (diags.hasErrors()) {
            return ButlerCommand.EXIT_FAILURE;
        }
        if (result.config() != null && !result.config().jobs().containsKey(job)) {
            System.err.println("no job named \"" + job + "\" (known jobs: "
                    + String.join(", ", result.config().jobs().keySet()) + ")");
            return ButlerCommand.EXIT_FAILURE;
        }
        System.err.println("running jobs is not implemented yet (milestone M3)");
        return ButlerCommand.EXIT_FAILURE;
    }
}
