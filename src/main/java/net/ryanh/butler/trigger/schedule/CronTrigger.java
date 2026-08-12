package net.ryanh.butler.trigger.schedule;

import net.ryanh.butler.spi.EventSink;
import net.ryanh.butler.spi.TriggerContext;
import net.ryanh.butler.spi.TriggerType;
import net.ryanh.butler.spi.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fires on a five-field cron expression.
 */
public final class CronTrigger implements TriggerType<CronTrigger.Config> {

    private static final Logger log = LoggerFactory.getLogger(CronTrigger.class);

    /**
     * @param expression a five-field cron expression, e.g. {@code 0 3 * * *}
     * @param timezone   an IANA zone name; the host's own zone otherwise
     */
    public record Config(String expression, String timezone) {
    }

    @Override
    public String name() {
        return "schedule.cron";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public Watcher start(Config config, EventSink sink, TriggerContext ctx) {
        Cron cron = Cron.parse(config.expression());
        ZoneId zone = Cron.zone(config.timezone());
        AtomicBoolean running = new AtomicBoolean(true);

        Thread thread = Thread.ofVirtual()
                .name("trigger-schedule.cron-" + ctx.job())
                .start(() -> {
                    while (running.get()) {
                        ZonedDateTime next = cron.next(ZonedDateTime.now(zone));
                        // One sleep rather than a countdown: a virtual thread parked for six hours
                        // costs nothing.
                        Duration until = Duration.between(Instant.now(), next.toInstant());
                        log.debug("next firing of {} is {}", cron, next);
                        try {
                            if (!until.isNegative()) {
                                Thread.sleep(until);
                            }
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
