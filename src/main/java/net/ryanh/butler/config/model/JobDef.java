package net.ryanh.butler.config.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A job: triggers bound to a pipeline, plus hooks and policy.
 *
 * <p>Only {@code on} and {@code steps} are required. A job with just those two is valid, and that
 * is the floor the DSL stays usable at.
 */
public record JobDef(
        String name,
        String description,
        Map<String, Object> vars,
        Map<String, String> env,
        List<TriggerDef> on,
        List<StepDef> discover,
        String when,
        ConcurrencyDef concurrency,
        Duration timeout,
        List<StepDef> steps,
        List<StepDef> onFailure,
        List<StepDef> onSuccess,
        List<StepDef> always,
        Map<String, String> persist,
        /* Only the fields the job set; ButlerConfig.retentionFor fills the rest. */
        ButlerConfig.RunRetention runRetention,
        /* Reads "notify" in YAML; a record component cannot be named after Object.notify(). */
        NotifyDef notifyPolicy,
        String path) {
}
