package net.ryanh.butler.config.model;

import java.util.List;
import java.util.Map;

/**
 * A job's notify policy.
 *
 * @param messages message template per outcome, keyed by the lowercase outcome name
 */
public record NotifyDef(String to, List<Enums.Outcome> on, Map<String, String> messages) {
}
