package net.ryanh.butler.config.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A job's notify policy.
 *
 * @param to       the channels to send through, each a name from the {@code notifiers:} block
 * @param messages message template per outcome, keyed by the lowercase outcome name
 */
public record NotifyDef(List<String> to, List<Enums.Outcome> on, Map<String, String> messages) {

    /**
     * The message to send for an outcome, or null when the policy stays quiet about it.
     *
     * <p>A recovery falls back to what a plain success would have done, so naming
     * {@code recovered} is what opts a config into telling the two apart.
     */
    public String templateFor(Enums.Outcome outcome) {
        String template = on.contains(outcome)
                ? messages.get(outcome.name().toLowerCase(Locale.ROOT)) : null;
        return template == null && outcome == Enums.Outcome.RECOVERED
                ? templateFor(Enums.Outcome.SUCCESS) : template;
    }
}
