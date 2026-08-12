package net.ryanh.butler.step.systemd;

import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the {@code systemd.*} steps share: the {@code systemctl} command, waiting for a unit to
 * reach a state, and the checks worth making before any of it.
 *
 * <p>{@code sudo} is separate from {@code run_as:}: it says root is required, not which user to
 * become, and the mutating verbs need it because the daemon runs unprivileged (DESIGN.md §10.2).
 */
final class Systemd {

    private static final Logger log = LoggerFactory.getLogger(Systemd.class);

    /**
     * How often a wait re-asks. Short enough not to bill a fast restart a whole second.
     */
    private static final Duration POLL = Duration.ofMillis(250);

    private Systemd() {
    }

    static List<String> argv(boolean sudo, String... args) {
        List<String> argv = new ArrayList<>(args.length + 2);
        if (sudo) {
            argv.add("sudo");
        }
        argv.add("systemctl");
        argv.addAll(List.of(args));
        return List.copyOf(argv);
    }

    static ProcessRunner.Completed run(RunContext ctx, List<String> argv) throws IOException {
        return ctx.processes().run(ctx.command().argv(argv));
    }

    /**
     * Runs one verb against a unit, then waits for {@code wanted} if a wait was asked for.
     *
     * <p>The wait is not decoration: {@code systemctl restart} returns once systemd has accepted
     * the job, not once the service is up, so a health check that follows it immediately is as
     * likely to be testing the old process.
     */
    static StepResult act(RunContext ctx, String verb, String unit, boolean sudo, Duration wait,
                          String wanted) throws IOException {
        StepResult result = StepResult.of(run(ctx, argv(sudo, verb, unit)), "systemctl " + verb);
        if (result.isFailed() || wait == null) {
            return result;
        }
        return await(ctx, unit, wait, wanted, result);
    }

    /**
     * Polls {@code is-active} until the unit reports the state asked for, or the wait runs out.
     */
    static StepResult await(RunContext ctx, String unit, Duration wait, String wanted,
                            StepResult soFar) throws IOException {
        Instant deadline = Instant.now().plus(wait);
        while (true) {
            String state = isActive(ctx, unit);
            StepResult seen = soFar.output("active_state", state);
            if (state.equals(wanted)) {
                return seen;
            }
            if (!Instant.now().isBefore(deadline)) {
                return failing(seen, unit + " is " + state + " after " + Durations.format(wait)
                        + ", not " + wanted);
            }
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failing(seen, unit + " was still " + state + " when the step was cut off");
            }
        }
    }

    /**
     * The same result, failed, keeping the outputs it already carried.
     */
    private static StepResult failing(StepResult result, String message) {
        return StepResult.failed(message).outputs(result.outputs()).duration(result.duration());
    }

    /**
     * What the unit is doing: {@code active}, {@code inactive}, {@code failed}, {@code activating}.
     *
     * <p>The exit code is ignored because {@code is-active} uses a non-zero one to say "no".
     */
    static String isActive(RunContext ctx, String unit) throws IOException {
        ProcessRunner.Completed done = run(ctx, argv(false, "is-active", unit));
        String state = done.stdout().strip();
        return state.isEmpty() ? "unknown" : state;
    }

    /**
     * The properties systemd reports for a unit, as {@code Key=Value} lines.
     */
    static Map<String, String> show(RunContext ctx, String unit, String... properties)
            throws IOException {
        List<String> args = new ArrayList<>();
        args.add("show");
        for (String property : properties) {
            args.add("--property=" + property);
        }
        args.add(unit);
        ProcessRunner.Completed done = run(ctx, argv(false, args.toArray(String[]::new)));

        Map<String, String> out = new LinkedHashMap<>();
        for (String line : done.stdout().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return out;
    }

    /**
     * Warns if systemd has not heard of the unit, or if no sudoers rule permits the command.
     */
    static List<String> preflight(RunContext ctx, String unit, boolean sudo, String verb) {
        if (unit == null || unit.isBlank()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        try {
            String loadState = show(ctx, unit, "LoadState").getOrDefault("LoadState", "");
            if (loadState.equals("not-found")) {
                warnings.add("no unit named " + unit + " is known to systemd");
            }
        } catch (IOException e) {
            // The exception names the program and a platform error code; neither adds anything.
            log.debug("systemctl could not be run: {}", e.toString());
            warnings.add("systemctl could not be run, so " + unit + " was not checked");
            return List.copyOf(warnings);
        }
        if (sudo && !sudoAllows(ctx, verb, unit)) {
            warnings.add("no NOPASSWD sudoers rule matches `systemctl " + verb + " " + unit + "`");
        }
        return List.copyOf(warnings);
    }

    /**
     * {@code -n} makes sudo fail rather than prompt, so this is safe to run from a dry run on a
     * terminal nobody is watching.
     */
    private static boolean sudoAllows(RunContext ctx, String verb, String unit) {
        try {
            return run(ctx, List.of("sudo", "-n", "-l", "systemctl", verb, unit)).ok();
        } catch (IOException e) {
            return false;
        }
    }
}
