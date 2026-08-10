package net.ryanh.butler.config.model;

import java.util.Map;

/**
 * One trigger. Like {@link StepDef}, parameters stay raw until the trigger registry exists.
 *
 * @param path document path, carried for diagnostics
 */
public record TriggerDef(String uses, Map<String, Object> params, String path) {
}
