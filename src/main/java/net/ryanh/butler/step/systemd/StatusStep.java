package net.ryanh.butler.step.systemd;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Reports a unit's state, sub-state and main PID without changing anything.
 */
public final class StatusStep implements StepType<StatusStep.Config> {

    public record Config(String unit) {
    }

    @Override
    public String name() {
        return "systemd.status";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Report a unit's state, sub-state and main PID";
    }

    @Override
    public List<String> locals() {
        return List.of("active_state", "sub_state", "pid", "load_state");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.unit() == null || c.unit().isBlank()) {
            return StepResult.failed("systemd.status needs a unit:");
        }
        Map<String, String> shown =
                Systemd.show(ctx, c.unit(), "LoadState", "ActiveState", "SubState", "MainPID");
        if (shown.getOrDefault("LoadState", "").equals("not-found")) {
            return StepResult.failed("no unit named " + c.unit() + " is known to systemd");
        }
        return StepResult.ok()
                .output("load_state", shown.get("LoadState"))
                .output("active_state", shown.get("ActiveState"))
                .output("sub_state", shown.get("SubState"))
                .output("pid", pid(shown.get("MainPID")));
    }

    /**
     * Asking changes nothing, so a dry run reports the real answer.
     */
    @Override
    public StepResult simulate(Config c, RunContext ctx) {
        try {
            return execute(c, ctx);
        } catch (IOException e) {
            return StepResult.failed(e.toString());
        }
    }

    /**
     * @return the main PID, or null when there is no process, which systemd reports as 0
     */
    private static Long pid(String value) {
        try {
            long pid = value == null ? 0 : Long.parseLong(value.strip());
            return pid == 0 ? null : pid;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.unit() == null || c.unit().isBlank()) {
            return "would fail: systemd.status needs a unit:";
        }
        return "would ask    systemctl show " + c.unit();
    }
}
