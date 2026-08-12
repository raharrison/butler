package net.ryanh.butler.config.model;

import java.time.Duration;
import java.util.List;
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
     * The keys the loader lifts into the typed fields above, and which a step type may therefore
     * never name a parameter (DESIGN.md §3.1). {@code StepRegistry} refuses one at startup.
     */
    public static final List<String> RESERVED =
            List.of("name", "uses", "when", "register", "timeout", "retry", "continue_on_error",
                    "env", "working_dir", "run_as", "extract");

    /**
     * Label for logs and dry-run output: the author's name if given, else the step type.
     */
    public String label() {
        return name != null && !name.isBlank() ? name : uses;
    }
}
