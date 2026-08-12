package net.ryanh.butler.trigger.manual;

import net.ryanh.butler.spi.EventSink;
import net.ryanh.butler.spi.TriggerContext;
import net.ryanh.butler.spi.TriggerType;
import net.ryanh.butler.spi.Watcher;

/**
 * A job that only ever fires by hand, through {@code butler trigger}.
 *
 * <p>There is nothing to watch, so the watcher does nothing: the events come from the command
 * line.
 */
public final class ManualTrigger implements TriggerType<ManualTrigger.Config> {

    public record Config() {
    }

    @Override
    public String name() {
        return "manual";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public Watcher start(Config config, EventSink sink, TriggerContext ctx) {
        return () -> {
        };
    }
}
