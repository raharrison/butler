package net.ryanh.butler.step.control;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Writes a line into the run log. The simplest possible step, and the one pipelines start with.
 */
public final class LogStep implements StepType<LogStep.Config> {

    private static final Logger log = LoggerFactory.getLogger(LogStep.class);

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    public record Config(String message, Level level) {
        public Config {
            if (level == null) {
                level = Level.INFO;
            }
            if (message == null) {
                message = "";
            }
        }
    }

    @Override
    public String name() {
        return "control.log";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Write a message into the run log";
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) {
        switch (c.level()) {
            case DEBUG -> log.debug(c.message());
            case INFO -> log.info(c.message());
            case WARN -> log.warn(c.message());
            case ERROR -> log.error(c.message());
        }
        return StepResult.ok();
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        return "would log [" + c.level().name().toLowerCase(Locale.ROOT) + "] " + c.message();
    }
}
