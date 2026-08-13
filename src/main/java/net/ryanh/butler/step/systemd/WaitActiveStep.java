package net.ryanh.butler.step.systemd;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Durations;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Waits for a unit to reach a state, without changing anything itself.
 */
public final class WaitActiveStep implements StepType<WaitActiveStep.Config> {

    /**
     * Reads {@code wait_for:} in the config; a record component cannot be named after
     * {@code Object.wait()}.
     *
     * @param state   how the unit should end up; {@code active} unless the config says otherwise
     * @param waitFor how long to give it
     */
    public record Config(String unit, String state, Duration waitFor) {
        public Config {
            state = state == null || state.isBlank() ? "active" : state;
            waitFor = waitFor == null ? Duration.ofSeconds(30) : waitFor;
        }
    }

    @Override
    public String name() {
        return "systemd.wait_active";
    }

    @Override
    public List<String> required() {
        return List.of("unit");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Wait for a unit to reach a state";
    }

    @Override
    public List<String> locals() {
        return List.of("active_state");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.unit() == null || c.unit().isBlank()) {
            return StepResult.failed("systemd.wait_active needs a unit:");
        }
        return Systemd.await(ctx, c.unit(), c.waitFor(), c.state(), StepResult.ok());
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.unit() == null || c.unit().isBlank()) {
            return "would fail: systemd.wait_active needs a unit:";
        }
        return "would wait up to " + Durations.format(c.waitFor()) + " for " + c.unit()
                + " to be " + c.state();
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        return Systemd.preflight(ctx, c.unit(), false, "is-active");
    }
}
