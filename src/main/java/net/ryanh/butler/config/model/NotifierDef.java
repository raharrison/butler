package net.ryanh.butler.config.model;

import java.util.Map;

/**
 * A named notification channel. Parameters stay raw until the notifier registry exists.
 */
public record NotifierDef(String name, String uses, Map<String, Object> params, String path) {
}
