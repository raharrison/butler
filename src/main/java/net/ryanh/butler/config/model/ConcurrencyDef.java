package net.ryanh.butler.config.model;


/**
 * A job's concurrency policy. Defaults to queueing with newest-wins collapsing, so two artifacts
 * landing seconds apart converge on the newer one rather than deploying both.
 */
public record ConcurrencyDef(String group, Enums.ConcurrencyMode mode, boolean queueNewestOnly) {

    public static ConcurrencyDef defaultFor(String jobName) {
        return new ConcurrencyDef(jobName, Enums.ConcurrencyMode.QUEUE, true);
    }
}
