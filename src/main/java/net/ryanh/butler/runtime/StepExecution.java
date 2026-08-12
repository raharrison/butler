package net.ryanh.butler.runtime;

import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.util.Durations;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One call to a step's {@code execute()}, with its timeout enforced around it.
 *
 * <p>The step runs on its own virtual thread and is interrupted if it overstays, which is also how
 * the job-level timeout works: the runner gives each step whatever is left of the job's time
 * (DESIGN.md §5.1). Interruption is cooperative, so a step that blocks on nothing outlives its
 * timeout and is reported as stranded.
 *
 * <p>A step that turns its interrupt into a result of its own keeps it, so a process killed for
 * overstaying still reports the tail of what it printed.
 */
final class StepExecution {

    /**
     * How long an overstaying step is given to notice the interrupt before it is left behind.
     */
    private static final Duration GRACE = Duration.ofSeconds(5);

    private StepExecution() {
    }

    /**
     * @param timedOut whether the step ran out of time rather than deciding anything, which is what
     *                 {@code retry: { on: timeout }} keys off
     * @param stranded whether the step ignored its interrupt and is still running
     */
    record Attempt(StepResult result, boolean timedOut, boolean stranded) {
    }

    /**
     * How long a step may take: its own timeout, or what is left of the job's, whichever runs out
     * first. Capping each step is the whole of the job-level timeout; nothing races the run.
     *
     * @param deadline when the job's own time runs out, or null if it has no {@code timeout:}
     */
    static Duration budget(Duration own, Instant deadline) {
        if (deadline == null) {
            return own;
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        return own == null || own.compareTo(remaining) > 0 ? remaining : own;
    }

    static Attempt once(StepResolver.Ready ready, Duration timeout) {
        if (timeout == null) {
            return new Attempt(call(ready), false, false);
        }
        AtomicReference<StepResult> holder = new AtomicReference<>();
        Instant started = Instant.now();
        // A virtual thread does not inherit the MDC, and every line a step logs has to say which
        // run, job and step it belongs to.
        Map<String, String> logContext = MDC.getCopyOfContextMap();
        Thread thread = Thread.ofVirtual()
                .name("step-" + ready.type().name())
                .start(() -> {
                    if (logContext != null) {
                        MDC.setContextMap(logContext);
                    }
                    holder.set(call(ready));
                });
        try {
            if (thread.join(timeout)) {
                return new Attempt(result(holder, started), false, false);
            }
            thread.interrupt();
            boolean stopped = thread.join(GRACE);
            StepResult late = holder.get();
            StepResult result = timedOut(timeout, started);
            // A step that caught its own interrupt knows what the runtime does not: what the
            // process it killed printed, how far a probe got. Outputs tell that apart from a step
            // that merely let the interrupt escape, which has none and nothing to add.
            if (late != null && !late.outputs().isEmpty()) {
                result = result.outputs(late.outputs());
                if (late.message() != null && !late.message().isBlank()) {
                    result = result.message(result.message() + ": " + late.message());
                }
            }
            return new Attempt(result, true, !stopped);
        } catch (InterruptedException e) {
            thread.interrupt();
            Thread.currentThread().interrupt();
            return new Attempt(StepResult.failed("cancelled")
                    .duration(Duration.between(started, Instant.now())), false, thread.isAlive());
        }
    }

    /**
     * The step thread dies without setting a result if it was killed by an {@link Error} the JVM
     * could not deliver to {@link #call}, so an absent result is a failure like any other.
     */
    private static StepResult result(AtomicReference<StepResult> holder, Instant started) {
        StepResult result = holder.get();
        return result != null ? result
                : StepResult.failed("the step ended without producing a result")
                .duration(Duration.between(started, Instant.now()));
    }

    private static StepResult timedOut(Duration timeout, Instant started) {
        return StepResult.failed("timed out after " + Durations.format(timeout))
                .duration(Duration.between(started, Instant.now()));
    }

    /**
     * A step that throws has failed. Letting it escape would take the whole run with it, including
     * the {@code on_failure:} that exists to clean up after exactly this, so even an {@link Error}
     * from a plugin is caught and reported as that step's failure.
     */
    private static StepResult call(StepResolver.Ready ready) {
        Instant started = Instant.now();
        try {
            StepResult result = ready.type().execute(ready.params(), ready.ctx());
            StepResult produced = result == null ? StepResult.ok() : result;
            return produced.duration(Duration.between(started, Instant.now()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StepResult.failed("cancelled")
                    .duration(Duration.between(started, Instant.now()));
        } catch (Throwable t) {
            return StepResult.failed(message(t))
                    .duration(Duration.between(started, Instant.now()));
        }
    }

    private static String message(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
    }
}
