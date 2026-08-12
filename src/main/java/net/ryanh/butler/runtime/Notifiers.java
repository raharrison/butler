package net.ryanh.butler.runtime;

import net.ryanh.butler.config.model.NotifierDef;
import net.ryanh.butler.spi.Notifier;

import java.util.Map;

/**
 * Sends a rendered message through a channel named in the config's {@code notifiers:} block.
 *
 * <p>Its parameters are resolved per send rather than at load time, since
 * {@code webhook: ${secret.SLACK_WEBHOOK}} has no value until a run resolves it.
 */
final class Notifiers {

    private Notifiers() {
    }

    @SuppressWarnings("unchecked")
    static void send(Map<String, NotifierDef> declared, NotifierRegistry registry, String to,
                     String message, Context ctx) throws Exception {
        NotifierDef def = declared.get(to);
        if (def == null) {
            throw new IllegalStateException("no notifier named \"" + to + "\"");
        }
        Notifier<Object> notifier = (Notifier<Object>) registry.find(def.uses());
        if (notifier == null) {
            throw new IllegalStateException("notifier \"" + to + "\" uses unknown type \""
                    + def.uses() + "\"");
        }
        Object params = Params.bind(notifier.configType(), (Map<String, Object>) ctx.resolveDeep(
                def.params()));
        notifier.send(params, message);
    }
}
