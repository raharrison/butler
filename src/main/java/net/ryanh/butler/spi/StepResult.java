package net.ryanh.butler.spi;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What every step produces, whether it ran for real or only simulated a run.
 *
 * <p>{@code outputs} carries the step-specific fields - {@code previous_target}, {@code json},
 * {@code stdout} - and is what {@code register:} exposes as {@code steps.<name>.*} together with
 * the common fields, via {@link #asContext()}.
 *
 * <p>{@code vars} is separate because a value written there lands in the {@code vars.*} namespace
 * rather than under the step's own name. That is how {@code control.set} works without the runtime
 * knowing anything about it.
 */
public record StepResult(Status status, String message, Duration duration, int attempts,
                         Map<String, Object> outputs, Map<String, Object> vars) {

    public enum Status {
        OK, FAILED, SKIPPED;

        /**
         * Lowercase, because this is what {@code steps.x.status == "ok"} compares against.
         */
        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public StepResult {
        outputs = ordered(outputs);
        vars = ordered(vars);
    }

    public static StepResult ok() {
        return new StepResult(Status.OK, null, Duration.ZERO, 1, Map.of(), Map.of());
    }

    public static StepResult failed(String message) {
        return new StepResult(Status.FAILED, message, Duration.ZERO, 1, Map.of(), Map.of());
    }

    public static StepResult skipped(String reason) {
        return new StepResult(Status.SKIPPED, reason, Duration.ZERO, 1, Map.of(), Map.of());
    }

    public StepResult output(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(outputs);
        merged.put(key, value);
        return new StepResult(status, message, duration, attempts, merged, vars);
    }

    public StepResult outputs(Map<String, Object> values) {
        Map<String, Object> merged = new LinkedHashMap<>(outputs);
        merged.putAll(values);
        return new StepResult(status, message, duration, attempts, merged, vars);
    }

    /**
     * Adds a value to be merged into the {@code vars.*} namespace after this step.
     */
    public StepResult var(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(vars);
        merged.put(key, value);
        return new StepResult(status, message, duration, attempts, outputs, merged);
    }

    public StepResult vars(Map<String, Object> values) {
        Map<String, Object> merged = new LinkedHashMap<>(vars);
        merged.putAll(values);
        return new StepResult(status, message, duration, attempts, outputs, merged);
    }

    public StepResult duration(Duration taken) {
        return new StepResult(status, message, taken, attempts, outputs, vars);
    }

    public StepResult attempts(int count) {
        return new StepResult(status, message, duration, count, outputs, vars);
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    public boolean isSkipped() {
        return status == Status.SKIPPED;
    }

    /**
     * The shape {@code steps.<name>.*} sees: the common fields, then the step-specific ones.
     *
     * <p>Outputs are put last, so a step that produces its own {@code status} - an HTTP probe
     * reporting 200 - shadows the result's. That is the reading a config author expects, and
     * {@code ok}, {@code failed} and {@code skipped} still say how the step itself went.
     */
    public Map<String, Object> asContext() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.toString());
        m.put("ok", isOk());
        m.put("failed", isFailed());
        m.put("skipped", isSkipped());
        m.put("duration", duration);
        m.put("attempts", (long) attempts);
        if (message != null) {
            m.put("message", message);
        }
        m.putAll(outputs);
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Object> ordered(Map<String, Object> in) {
        return in == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(in));
    }
}
