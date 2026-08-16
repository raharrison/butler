package net.ryanh.butler.trigger.schedule;

import net.ryanh.butler.spi.EventSink;
import net.ryanh.butler.spi.TriggerContext;
import net.ryanh.butler.spi.TriggerType;
import net.ryanh.butler.spi.Watcher;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fires on a fixed interval, each one counted from the last.
 *
 * <p>The first firing is one interval away rather than immediate, because a daemon that runs every
 * job the moment it starts turns a restart into a deployment.
 */
public final class EveryTrigger implements TriggerType<EveryTrigger.Config> {

    public record Config(Duration interval) {
        public Config {
            interval = interval == null ? Duration.ofHours(1) : interval;
        }
    }

    @Override
    public String name() {
        return "schedule.every";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public Watcher start(Config config, EventSink sink, TriggerContext ctx) {
        if (config.interval().isZero()) {
            throw new IllegalArgumentException(
                    "schedule.every needs an interval of more than zero, or it would fire in a "
                            + "loop rather than on a schedule");
        }
        AtomicBoolean running = new AtomicBoolean(true);
        Thread thread = Thread.ofVirtual()
                .name("trigger-schedule.every-" + ctx.job())
                .start(() -> {
                    while (running.get()) {
                        try {
                            Thread.sleep(config.interval());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (running.get()) {
                            sink.emit(Schedules.firing(name(), Instant.now()));
                        }
                    }
                });
        return () -> {
            running.set(false);
            thread.interrupt();
        };
    }
}
