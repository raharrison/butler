package net.ryanh.butler.cli;

import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.runtime.Plan;
import net.ryanh.butler.runtime.PlanBuilder;
import net.ryanh.butler.runtime.PlanRenderer;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.util.Suggestions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Runs one job once, against the facts its trigger would have supplied.
 *
 * <p>With {@code --dry-run} it renders the plan instead, which together with {@code butler check}
 * is the authoring loop: change the config, read the plan, change it again.
 */
@Command(
        name = "trigger",
        header = "Run a job once, or with --dry-run report what it would do.",
        description = "Facts come from --set. Evaluating the job's own configured triggers to "
                + "build a realistic event needs the file and schedule triggers, which arrive in "
                + "M4.",
        mixinStandardHelpOptions = true)
public final class TriggerCommand implements Callable<Integer> {

    @Mixin
    ConfigMixin configOptions;

    @Parameters(index = "0", paramLabel = "<job>", description = "Job to run.")
    String job;

    @Option(names = "--set", paramLabel = "<key=value>",
            description = "Supply or override a trigger fact.")
    Map<String, String> facts = Map.of();

    @Override
    public Integer call() {
        var result = configOptions.loadAndValidate();
        var diags = result.diagnostics();
        String file = configOptions.config().toString();

        if (!diags.isEmpty()) {
            System.err.print(diags.render(file));
        }
        if (diags.hasErrors()) {
            return ButlerCommand.EXIT_FAILURE;
        }

        JobDef definition = result.config().jobs().get(job);
        if (definition == null) {
            System.err.println("no job named \"" + job + "\""
                    + Suggestions.from(job, result.config().jobs().keySet())
                    + " (known jobs: " + String.join(", ", result.config().jobs().keySet()) + ")");
            return ButlerCommand.EXIT_FAILURE;
        }

        if (!configOptions.dryRun()) {
            System.err.println("running a job for real is not implemented yet (milestone M3)");
            System.err.println("try: butler trigger " + job + " --dry-run");
            return ButlerCommand.EXIT_FAILURE;
        }

        Plan plan = PlanBuilder.build(result.config(), definition, event(definition),
                configOptions.steps(), diags);
        System.out.print(PlanRenderer.render(plan));

        // Printed first: a plan with the bad step marked says more than the error alone.
        if (diags.hasErrors()) {
            System.err.print(diags.render(file));
            return ButlerCommand.EXIT_FAILURE;
        }
        return ButlerCommand.EXIT_OK;
    }

    /**
     * The event the job's first trigger would produce, as far as {@code --set} describes it. It
     * carries no dedupe key: a run asked for by hand is never suppressed as already done.
     */
    private Event event(JobDef definition) {
        String trigger = definition.on().isEmpty() ? "manual" : definition.on().getFirst().uses();
        return new Event(trigger, new LinkedHashMap<>(facts), null);
    }
}
