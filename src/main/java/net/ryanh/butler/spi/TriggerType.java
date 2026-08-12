package net.ryanh.butler.spi;

import java.util.List;

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

    /**
     * Parameters read as a bare expression rather than a string template, e.g. {@code order_by:}
     * (DESIGN.md §4), exactly as {@link StepType#conditions()}.
     */
    default List<String> conditions() {
        return List.of();
    }

    /**
     * The events this trigger would fire for as things stand right now, oldest first, without
     * starting a watcher.
     *
     * <p>{@code butler trigger} rehearses against the newest of these, and {@code butler adopt}
     * records its dedupe key so an artifact already present does not fire when the daemon starts
     * (DESIGN.md §6.3). A trigger with nothing to observe has no candidates.
     */
    default List<Event> current(C config, TriggerContext ctx) {
        return List.of();
    }
}
