package net.ryanh.butler.step.control;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Durations;

import java.time.Duration;

/**
 * Waits for a fixed duration, for when something needs a moment and there is nothing to poll.
 */
public final class SleepStep implements StepType<SleepStep.Config> {

    public record Config(Duration duration) {
        public Config {
            if (duration == null) {
                duration = Duration.ZERO;
            }
        }
    }

    @Override
    public String name() {
        return "control.sleep";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Wait for a fixed duration";
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws InterruptedException {
        Thread.sleep(c.duration());
        return StepResult.ok();
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        return "would sleep " + Durations.format(c.duration());
    }
}
