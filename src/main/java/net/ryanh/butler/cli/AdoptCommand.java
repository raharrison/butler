package net.ryanh.butler.cli;

import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.runtime.JobRunner;
import net.ryanh.butler.runtime.Plan;
import net.ryanh.butler.runtime.RunEnvironment;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.util.Literals;
import net.ryanh.butler.util.Suggestions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Seeds state from the host without executing anything: the install-time step on a server that is
 * already serving (DESIGN.md §6.3).
 */
@Command(
        name = "adopt",
        header = "Seed state from the host without executing anything.",
        description = "Runs each job's discover block, records the resulting state and the dedupe "
                + "key of whatever is already present, then exits. Run once at install time on a "
                + "host that is already serving.",
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

        Map<String, JobDef> jobs = result.config().jobs();
        if (job != null && !jobs.containsKey(job)) {
            System.err.println("no job named \"" + job + "\"" + Suggestions.from(job, jobs.keySet())
                    + " (known jobs: " + String.join(", ", jobs.keySet()) + ")");
            return ButlerCommand.EXIT_FAILURE;
        }

        if (configOptions.dryRun()) {
            System.err.println("adopt writes state; there is nothing it could do under --dry-run");
            return ButlerCommand.EXIT_FAILURE;
        }

        RunEnvironment env = configOptions.environment();
        JobRunner runner = new JobRunner(env);
        for (JobDef definition : jobs.values()) {
            if (job != null && !definition.name().equals(job)) {
                continue;
            }
            Event candidate = Events.candidate(result.config(), definition,
                    configOptions.triggers());
            report(runner.adopt(definition, candidate), env);
        }
        return ButlerCommand.EXIT_OK;
    }

    private static void report(JobRunner.Adoption adopted, RunEnvironment env) {
        System.out.println("adopted " + adopted.job()
                + "  ->  " + Literals.path(env.state().fileFor(adopted.job())));
        for (Plan.Entry entry : adopted.discover()) {
            System.out.println("    " + entry.label() + "  " + entry.uses());
            List<String> lines = entry.error() != null
                    ? List.of("error: " + entry.error())
                    : entry.skipped() != null ? List.of(entry.skipped()) : entry.body();
            lines.forEach(line -> System.out.println("        " + line));
        }

        // What discovery observed, laid over whatever was already recorded.
        System.out.println("  recorded");
        if (adopted.state().isEmpty()) {
            System.out.println("    nothing: this job's discover block observed nothing");
        }
        adopted.state().forEach((k, v) ->
                System.out.println("    state." + k + " = " + Literals.of(v)));
        System.out.println("    dedupe_key = " + Literals.of(adopted.dedupeKey()));
    }
}
