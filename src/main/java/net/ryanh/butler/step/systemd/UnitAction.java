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
 * The shape shared by {@code systemd.restart}, {@code start} and {@code reload}: run one verb
 * against a unit, then wait for it to become active.
 */
abstract class UnitAction implements StepType<UnitAction.Config> {

    /**
     * @param waitActive how long to wait for the unit to become active, or nothing to return as
     *                   soon as systemd accepts the job
     * @param sudo       whether to put {@code sudo} in front; true unless the config says otherwise
     */
    public record Config(String unit, Duration waitActive, Boolean sudo) {
        public Config {
            sudo = sudo == null || sudo;
        }
    }

    abstract String verb();

    @Override
    public final Class<Config> configType() {
        return Config.class;
    }

    @Override
    public final List<String> locals() {
        return List.of("active_state", "stdout", "stderr", "exit_code");
    }

    @Override
    public final StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.unit() == null || c.unit().isBlank()) {
            return StepResult.failed(name() + " needs a unit:");
        }
        return Systemd.act(ctx, verb(), c.unit(), c.sudo(), c.waitActive(), "active");
    }

    @Override
    public final String describe(Config c, RunContext ctx) {
        if (c.unit() == null || c.unit().isBlank()) {
            return "would fail: " + name() + " needs a unit:";
        }
        List<String> lines = new ArrayList<>();
        lines.add("would run    "
                + String.join(" ", Systemd.argv(c.sudo(), verb(), c.unit())));
        if (c.waitActive() != null) {
            lines.add("      then wait up to " + Durations.format(c.waitActive())
                    + " for the unit to become active");
        }
        return String.join("\n", lines);
    }

    @Override
    public final List<String> preflight(Config c, RunContext ctx) {
        return Systemd.preflight(ctx, c.unit(), c.sudo(), verb());
    }
}
