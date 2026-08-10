package net.ryanh.butler.config.model;

import java.time.Duration;
import java.util.Map;

/**
 * One step.
 *
 * <p>{@code params} holds everything that is not a reserved key, unbound and in source order.
 * Binding it to a step's own config record is the step registry's job, which keeps structural
 * validation independent of the step vocabulary.
 *
 * @param path document path, carried for diagnostics
 */
public record StepDef(
        String name,
        String uses,
        String when,
        String register,
        Duration timeout,
        RetryDef retry,
        boolean continueOnError,
        Map<String, String> env,
        String workingDir,
        String runAs,
        Map<String, String> extract,
        Map<String, Object> params,
        String path) {

    /**
     * Label for logs and dry-run output: the author's name if given, else the step type.
     */
    public String label() {
        return name != null && !name.isBlank() ? name : uses;
    }
}
