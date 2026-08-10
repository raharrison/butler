package net.ryanh.butler.spi;

/**
 * Where a watcher hands the events it observes.
 */
@FunctionalInterface
public interface EventSink {
    void emit(Event event);
}
