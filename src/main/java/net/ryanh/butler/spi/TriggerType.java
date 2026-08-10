package net.ryanh.butler.spi;

/**
 * A long-lived watcher that emits events. Like {@link StepType}, a record plus a class.
 *
 * @param <C> the trigger's own parameter record
 */
public interface TriggerType<C> {

    /**
     * The namespaced type name, e.g. {@code file.appeared}.
     */
    String name();

    /**
     * The parameter record. Must be a record; the registry rejects anything else.
     */
    Class<C> configType();

    Watcher start(C config, EventSink sink, TriggerContext ctx);
}
