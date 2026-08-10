package net.ryanh.butler.step.control;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds values to the {@code vars.*} namespace for the rest of the run.
 *
 * <p>The values travel back on the {@link StepResult}, which is how a step influences {@code vars}
 * without the runtime knowing which step type did it.
 */
public final class SetStep implements StepType<SetStep.Config> {

    public record Config(Map<String, Object> vars) {
        public Config {
            vars = vars == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(vars));
        }
    }

    @Override
    public String name() {
        return "control.set";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Set variables the rest of the run can read";
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) {
        return StepResult.ok().vars(c.vars());
    }

    /**
     * Setting a variable touches nothing on the host, so a dry run can do it for real.
     */
    @Override
    public StepResult simulate(Config c, RunContext ctx) {
        return execute(c, ctx);
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.vars().isEmpty()) {
            return "would set nothing";
        }
        StringBuilder sb = new StringBuilder();
        c.vars().forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("would set vars.").append(k).append(" = ").append(Literals.of(v));
        });
        return sb.toString();
    }
}
