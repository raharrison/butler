package net.ryanh.butler.runtime;

import net.ryanh.butler.spi.StepResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One execution of a job for one event: what was observed, what was decided, what ran and how it
 * ended.
 *
 * <p>Shaped like {@link Plan}, so the rehearsal and the real thing read alike.
 *
 * @param failedStep the label of the step that ended the run, or null
 * @param message    why the run ended as it did, when there is something to say
 */
public record Run(String id, String job, String trigger, Map<String, Object> facts,
                  Status status, Instant startedAt, Duration duration,
                  List<Plan.Entry> discover, Plan.Decision decision, List<Step> steps,
                  Map<String, Object> persisted, Plan.Notification notification,
                  String failedStep, String message) {

    /**
     * The four terminal statuses of DESIGN.md §2.1. {@code CANCELLED} is only for a run displaced
     * by {@code cancel_previous} or cut short by shutdown, and runs no hooks and records nothing.
     */
    public enum Status {
        SUCCESS, FAILED, SKIPPED, CANCELLED;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One step as it actually went. {@code outputs} is the step's own result fields - {@code
     * stdout}/{@code stderr} for a process, {@code body}/{@code json} for an HTTP step - kept in
     * full so the record answers "what happened" without the daemon's own logs.
     */
    public record Step(String section, String label, String uses, StepResult.Status status,
                       Duration duration, int attempts, String message,
                       Map<String, Object> outputs) {
    }

    public boolean ok() {
        return status == Status.SUCCESS || status == Status.SKIPPED;
    }
}
