package net.ryanh.butler.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * Reports every problem in a config and exits non-zero if any is an error. Built for CI.
 */
@Command(
        name = "validate",
        header = "Check a config and report every problem found.",
        description = "Exits 0 when the config is valid, 1 when it is not. Warnings alone do not "
                + "fail the command.",
        mixinStandardHelpOptions = true)
public final class ValidateCommand implements Callable<Integer> {

    @Mixin
    ConfigMixin configOptions;

    @Override
    public Integer call() {
        var result = configOptions.loadAndValidate();
        var diags = result.diagnostics();
        String file = configOptions.config().toString();

        if (diags.isEmpty()) {
            System.out.println(file + ": ok");
            return ButlerCommand.EXIT_OK;
        }

        System.err.print(diags.render(file));
        return diags.hasErrors() ? ButlerCommand.EXIT_FAILURE : ButlerCommand.EXIT_OK;
    }
}
