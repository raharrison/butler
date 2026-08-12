package net.ryanh.butler.spi;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;

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

    /**
     * Ranks candidates least first by an {@code order_by} expression over each one's facts.
     *
     * <p>The runtime evaluates on the trigger's behalf, since a trigger may not reach the
     * expression language directly. A candidate the expression cannot judge sorts lowest.
     */
    Comparator<Map<String, Object>> ordering(String expression);
}
