package net.ryanh.butler.spi;

import java.time.Duration;

/**
 * What a watcher is told about the job it watches for.
 */
public interface TriggerContext {

    String job();

    /**
     * {@code settings.poll_interval}, the default cadence for a polling trigger.
     */
    Duration pollInterval();

    /**
     * In a dry run a watcher still observes, but the runtime only describes what would follow.
     */
    boolean dryRun();
}
