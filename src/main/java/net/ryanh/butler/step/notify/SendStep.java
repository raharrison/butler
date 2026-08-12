package net.ryanh.butler.step.notify;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

/**
 * Sends a message through a channel declared under {@code notifiers:}, from the middle of a
 * pipeline. Announcing how a run ended is the job-level {@code notify:} policy's work.
 */
public final class SendStep implements StepType<SendStep.Config> {

    public record Config(String to, String message) {
    }

    @Override
    public String name() {
        return "notify.send";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Send a message through a declared notifier";
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws Exception {
        if (c.to() == null || c.to().isBlank()) {
            return StepResult.failed("notify.send needs a to: naming a declared notifier");
        }
        ctx.notifications().send(c.to(), c.message() == null ? "" : c.message());
        return StepResult.ok();
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.to() == null || c.to().isBlank()) {
            return "would fail: notify.send needs a to: naming a declared notifier";
        }
        return "would send   " + c.to() + " <- " + Literals.of(c.message());
    }
}
