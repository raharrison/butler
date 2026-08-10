package net.ryanh.butler.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Not implemented until M3. Registered now so {@code --help} is honest about what is coming.
 */
@Command(
        name = "adopt",
        header = "Seed state from the host without executing anything.",
        description = "Runs each job's discover block, records the resulting state and the dedupe "
                + "key of whatever is already present, then exits. Run once at install time on a "
                + "host that is already serving. (Arrives in M3.)",
        mixinStandardHelpOptions = true)
public final class AdoptCommand implements Callable<Integer> {

    @Mixin
    ConfigMixin configOptions;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<job>",
            description = "Job to adopt. Omit to adopt every job.")
    String job;

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
        System.err.println("adopt is not implemented yet (milestone M3)");
        return ButlerCommand.EXIT_FAILURE;
    }
}
