package net.ryanh.butler.runtime;

import java.util.List;
import java.util.Map;

/**
 * A fully resolved account of one run: what would happen, with every {@code ${}} already expanded.
 *
 * <p>The plan is data, and {@link PlanRenderer} is the only thing that knows how it looks. Keeping
 * those apart is what lets the rendered form be snapshot-tested as a golden file.
 */
public record Plan(
        String job,
        String trigger,
        Map<String, Object> facts,
        List<Entry> discover,
        Decision when,
        List<Entry> steps,
        List<Hook> hooks,
        Map<String, Object> persist,
        /* Reads "notify" in the config; a record component cannot be named after Object.notify(). */
        Notification notification) {

    /**
     * One step of the plan.
     *
     * @param number   position within its section, or 0 when the step would not run
     * @param describe the step's own account of its effect, one entry per line
     * @param skipped  why the step would not run, or null
     * @param error    what stopped the step being described at all, or null
     */
    public record Entry(String section, int number, String label, String uses,
                        List<String> describe, List<String> warnings,
                        String skipped, String error) {

        public static Entry skipped(String section, String label, String uses, String reason) {
            return new Entry(section, 0, label, uses, List.of(), List.of(), reason, null);
        }

        public static Entry failed(String section, String label, String uses, String problem) {
            return new Entry(section, 0, label, uses, List.of(), List.of(), null, problem);
        }
    }

    /**
     * The job-level {@code when:}, shown with both sides resolved so the decision can be checked
     * rather than taken on trust.
     *
     * @param explained the condition with its operands replaced by the values they evaluated to
     */
    public record Decision(String source, String explained, boolean result, String error) {
    }

    /**
     * A lifecycle section that exists but is not part of the expected path.
     */
    public record Hook(String name, String note) {
    }

    /**
     * The message the job's notify policy would send if the run succeeded.
     */
    public record Notification(String to, String message) {
    }

    /**
     * Whether the pipeline would run at all: a false {@code when:} ends the run as skipped.
     */
    public boolean wouldRun() {
        return when == null || when.result();
    }
}
