package net.ryanh.butler.cli;

import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.runtime.Run;
import net.ryanh.butler.runtime.RunRecorder;
import net.ryanh.butler.util.Durations;
import net.ryanh.butler.util.Suggestions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Lists what the daemon recorded, newest first.
 *
 * <p>Reads {@code runs/index.jsonl} rather than the records themselves: every line carries the same
 * summary fields as the head of the record it names, so a listing costs one file read whatever the
 * history holds.
 */
@Command(
        name = "runs",
        header = "List recorded runs, newest first.",
        description = "Reads the run index under state_dir. History is kept per job, so how far "
                + "back a job reaches is its own run_retention.",
        mixinStandardHelpOptions = true,
        sortOptions = false)
public final class RunsCommand implements Callable<Integer> {

    /**
     * "2026-08-09T03:14:07Z" and a run id are both fixed widths; the rest of a row follows them.
     */
    private static final int STARTED = 22;
    private static final int ID = 24;
    private static final int STATUS = 11;

    @Mixin
    ConfigMixin configOptions;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<job>",
            description = "Show only this job's runs. Omit for every job.")
    String job;

    @Option(names = "--last", paramLabel = "<n>",
            description = "How many to show. Default: ${DEFAULT-VALUE}.")
    int last = 20;

    @Option(names = "--failed", description = "Show only runs that failed.")
    boolean failed;

    @Option(names = "--since", paramLabel = "<duration>",
            description = "Show only runs started within this long, e.g. 24h.")
    String since;

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

        Map<String, JobDef> jobs = result.config().jobs();
        if (job != null && !jobs.containsKey(job)) {
            System.err.println("no job named \"" + job + "\"" + Suggestions.from(job, jobs.keySet())
                    + " (known jobs: " + String.join(", ", jobs.keySet()) + ")");
            return ButlerCommand.EXIT_FAILURE;
        }
        if (last < 1) {
            System.err.println("--last: expected at least 1, found " + last);
            return ButlerCommand.EXIT_USAGE;
        }
        Instant cutoff;
        try {
            cutoff = since == null ? null : Instant.now().minus(Durations.parse(since));
        } catch (IllegalArgumentException e) {
            System.err.println("--since: " + e.getMessage());
            return ButlerCommand.EXIT_USAGE;
        }

        List<RunRecorder.Summary> matched = new ArrayList<>();
        for (RunRecorder.Summary run : configOptions.environment().runs().history()) {
            if (matches(run, cutoff)) {
                matched.add(run);
            }
        }
        if (matched.isEmpty()) {
            System.out.println(nothing());
            return ButlerCommand.EXIT_OK;
        }
        // The index is in the order runs finished, and the newest is what a person came to see.
        List<RunRecorder.Summary> newest = matched.reversed();
        System.out.print(render(newest.subList(0, Math.min(last, newest.size()))));
        return ButlerCommand.EXIT_OK;
    }

    private boolean matches(RunRecorder.Summary run, Instant cutoff) {
        if (job != null && !job.equals(run.job())) {
            return false;
        }
        if (failed && run.status() != Run.Status.FAILED) {
            return false;
        }
        return cutoff == null || !run.startedAt().isBefore(cutoff);
    }

    /**
     * A job that has never run and a filter nothing matched are different answers, and a job whose
     * records have all aged out is the first one again.
     */
    private String nothing() {
        String scope = job == null ? "no runs recorded" : "no runs recorded for " + job;
        return failed || since != null ? scope + " matching those filters" : scope;
    }

    private static String render(List<RunRecorder.Summary> runs) {
        int jobWidth = "job".length();
        for (RunRecorder.Summary run : runs) {
            jobWidth = Math.max(jobWidth, run.job().length());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(row("started", "job", jobWidth, "id", "status", "took", ""));
        for (RunRecorder.Summary run : runs) {
            sb.append(row(run.startedAt().truncatedTo(ChronoUnit.SECONDS).toString(), run.job(),
                    jobWidth, run.id(), run.status().toString(),
                    Durations.format(run.duration()), note(run)));
        }
        return sb.toString();
    }

    private static String row(String started, String job, int jobWidth, String id, String status,
                              String took, String note) {
        StringBuilder sb = new StringBuilder("  ");
        sb.append(pad(started, STARTED)).append(pad(job, jobWidth + 2)).append(pad(id, ID))
                .append(pad(status, STATUS)).append(took);
        if (!note.isEmpty()) {
            sb.append("  ").append(note);
        }
        return sb.append('\n').toString();
    }

    /**
     * What ended the run, on one line: the step that failed, then why.
     */
    private static String note(RunRecorder.Summary run) {
        String why = run.message() == null || run.message().isBlank()
                ? null : run.message().strip().split("\n")[0];
        if (run.failedStep() == null) {
            return why == null ? "" : why;
        }
        return why == null ? "at \"" + run.failedStep() + "\""
                : "at \"" + run.failedStep() + "\": " + why;
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text + "  " : text + " ".repeat(width - text.length());
    }
}
