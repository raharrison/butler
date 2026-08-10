package net.ryanh.butler.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Not implemented until M2, when the step registry exists to generate it from.
 */
@Command(
        name = "steps",
        header = "List the registered step types and their parameters.",
        description = "Generated from the step registry, so a newly registered step is documented "
                + "the moment it exists. (Arrives in M2.)",
        mixinStandardHelpOptions = true)
public final class StepsCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<name>",
            description = "Show only this step type.")
    String name;

    @Override
    public Integer call() {
        System.err.println("the step registry does not exist yet (milestone M2)");
        return ButlerCommand.EXIT_FAILURE;
    }
}
