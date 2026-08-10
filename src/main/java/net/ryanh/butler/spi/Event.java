package net.ryanh.butler.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable bag of facts emitted by a trigger.
 *
 * @param trigger   the trigger type that produced it, e.g. {@code file.appeared}
 * @param facts     what the pipeline sees as {@code trigger.*}, including regex capture groups
 * @param dedupeKey identifies the work this event represents; a run starts only when the key
 *                  differs from the job's last processed one. Null means "always run", which is
 *                  what a hand-fired event wants.
 */
public record Event(String trigger, Map<String, Object> facts, String dedupeKey) {

    public Event {
        facts = facts == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }
}
