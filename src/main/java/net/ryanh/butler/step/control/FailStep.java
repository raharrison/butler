package net.ryanh.butler.step.control;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;

/**
 * Fails the run with a message. Paired with {@code when:} it is the end of a branch a pipeline
 * should never reach.
 */
public final class FailStep implements StepType<FailStep.Config> {

    public record Config(String message) {
        public Config {
            if (message == null || message.isBlank()) {
                message = "control.fail";
            }
        }
    }

    @Override
    public String name() {
        return "control.fail";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Fail the run with a message";
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) {
        return StepResult.failed(c.message());
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        return "would fail the run: " + c.message();
    }
}
