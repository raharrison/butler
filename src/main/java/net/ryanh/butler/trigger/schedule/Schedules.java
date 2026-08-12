package net.ryanh.butler.trigger.schedule;

import net.ryanh.butler.spi.Event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The event a schedule fires.
 */
final class Schedules {

    private Schedules() {
    }

    /**
     * No dedupe key: a firing is new work by definition, so there is nothing to compare against.
     */
    static Event firing(String trigger, Instant at) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("fired_at", at.toString());
        return new Event(trigger, facts, null);
    }
}
