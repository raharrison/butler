package net.ryanh.butler.step.systemd;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Durations;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Stops a unit, and waits for it to become inactive if asked.
 *
 * <p>Not a {@link UnitAction} because the state it waits for is the opposite one, and
 * {@code wait_active:} would be the wrong name for it.
 */
public final class StopStep implements StepType<StopStep.Config> {

    public record Config(String unit, Duration waitInactive, Boolean sudo) {
        public Config {
            sudo = sudo == null || sudo;
        }
    }

    @Override
    public String name() {
        return "systemd.stop";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Stop a unit, waiting for it to become inactive";
    }

    @Override
    public List<String> locals() {
        return List.of("active_state", "stdout", "stderr", "exit_code");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.unit() == null || c.unit().isBlank()) {
            return StepResult.failed("systemd.stop needs a unit:");
        }
        return Systemd.act(ctx, "stop", c.unit(), c.sudo(), c.waitInactive(), "inactive");
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.unit() == null || c.unit().isBlank()) {
            return "would fail: systemd.stop needs a unit:";
        }
        List<String> lines = new ArrayList<>();
        lines.add("would run    " + String.join(" ", Systemd.argv(c.sudo(), "stop", c.unit())));
        if (c.waitInactive() != null) {
            lines.add("      then wait up to " + Durations.format(c.waitInactive())
                    + " for the unit to become inactive");
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        return Systemd.preflight(ctx, c.unit(), c.sudo(), "stop");
    }
}
