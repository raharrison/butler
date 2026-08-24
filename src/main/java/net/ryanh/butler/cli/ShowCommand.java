package net.ryanh.butler.cli;

import net.ryanh.butler.runtime.Run;
import net.ryanh.butler.runtime.RunRecorder;
import net.ryanh.butler.runtime.RunRenderer;
import net.ryanh.butler.util.Suggestions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Prints one recorded run in full, through the same renderer that reported it when it happened, so
 * history and the report a run printed at the time read alike.
 */
@Command(
        name = "show",
        header = "Show one recorded run in full.",
        description = "Takes the id butler runs prints. To start a job rather than read one, "
                + "use butler trigger.",
        mixinStandardHelpOptions = true)
public final class ShowCommand implements Callable<Integer> {

    @Mixin
    ConfigMixin configOptions;

    @Parameters(index = "0", paramLabel = "<id>",
            description = "Run id, as butler runs prints it.")
    String id;

    @Override
    public Integer call() {
        var result = configOptions.loadAndValidate();
        var diags = result.diagnostics();
        if (!diags.isEmpty()) {
            System.err.print(diags.render(configOptions.describe()));
        }
        if (diags.hasErrors()) {
            return ButlerCommand.EXIT_FAILURE;
        }

        RunRecorder runs = configOptions.environment().runs();
        Run run;
        try {
            run = runs.read(id);
        } catch (RuntimeException e) {
            System.err.println("could not read the record for run \"" + id + "\": " + e.getMessage());
            return ButlerCommand.EXIT_FAILURE;
        }
        if (run == null) {
            System.err.println(missing(runs, result.config().jobs()));
            return ButlerCommand.EXIT_FAILURE;
        }
        System.out.print(RunRenderer.render(run));
        return ButlerCommand.EXIT_OK;
    }

    /**
     * A job name here is the likeliest mistake, since {@code trigger} is the verb that takes one.
     */
    private String missing(RunRecorder runs, Map<String, ?> jobs) {
        if (jobs.containsKey(id)) {
            return "\"" + id + "\" is a job, not a run id: "
                    + "butler runs " + id + " lists its runs, butler trigger " + id + " starts one";
        }
        List<String> known = runs.history().stream().map(RunRecorder.Summary::id).toList();
        return "no run recorded with id \"" + id + "\"" + Suggestions.from(id, known);
    }
}
