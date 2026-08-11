package net.ryanh.butler.cli;

import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.TriggerDef;
import net.ryanh.butler.runtime.Params;
import net.ryanh.butler.runtime.TriggerRegistry;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.TriggerContext;
import net.ryanh.butler.spi.TriggerType;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The event a job would be handed right now, for the commands that rehearse or adopt one.
 *
 * <p>The job's own triggers are asked what they can see rather than a synthetic event being
 * invented, which is what makes {@code butler trigger} a genuine rehearsal (DESIGN.md §10.1). A
 * trigger with nothing to observe offers no candidate, and then {@code --set} is the whole event.
 */
final class Events {

    private Events() {
    }

    /**
     * The newest candidate of the first of the job's triggers that can see anything, or null if
     * none of them can.
     *
     * <p>A trigger orders its own candidates; two different triggers have no common order, so the
     * choice between them is config order rather than a guess at which event is more recent.
     */
    @SuppressWarnings("unchecked")
    static Event candidate(ButlerConfig config, JobDef job, TriggerRegistry registry) {
        for (TriggerDef def : job.on()) {
            TriggerType<Object> type = (TriggerType<Object>) registry.find(def.uses());
            if (type == null) {
                continue;
            }
            Object params = Params.bind(type.configType(), def.params());
            List<Event> events = type.current(params, context(config, job));
            if (!events.isEmpty()) {
                Event newest = events.getLast();
                return new Event(def.uses(), newest.facts(), newest.dedupeKey());
            }
        }
        return null;
    }

    /**
     * The candidate with {@code --set} laid over it, or an event made only of {@code --set} when
     * there is no candidate. Facts given by hand win, since overriding one is the point.
     */
    static Event forJob(ButlerConfig config, JobDef job, TriggerRegistry registry,
                        Map<String, String> overrides) {
        Event candidate = candidate(config, job, registry);
        Map<String, Object> facts = new LinkedHashMap<>();
        if (candidate != null) {
            facts.putAll(candidate.facts());
        }
        facts.putAll(overrides);

        String trigger = candidate != null ? candidate.trigger()
                : job.on().isEmpty() ? "manual" : job.on().getFirst().uses();
        // No dedupe key: a run asked for by hand is never suppressed as already done. Adopt takes
        // the key from the candidate instead.
        return new Event(trigger, facts, null);
    }

    private static TriggerContext context(ButlerConfig config, JobDef job) {
        return new TriggerContext() {
            @Override
            public String job() {
                return job.name();
            }

            @Override
            public Duration pollInterval() {
                return config.settings().pollInterval();
            }

            @Override
            public boolean dryRun() {
                return true;
            }
        };
    }
}
