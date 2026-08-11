package net.ryanh.butler.runtime;

import net.ryanh.butler.config.Diagnostics;
import net.ryanh.butler.config.Secrets;
import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.spi.ProcessRunner;

/**
 * What a run needs from outside itself: the config it belongs to, the step vocabulary this build
 * can run, the state directory, a way to start processes and the resolved secrets.
 *
 * <p>{@link PlanBuilder} and {@link JobRunner} both take one, so a test swaps the state directory
 * for a temporary one and the process runner for a fake without either path differing otherwise.
 */
public record RunEnvironment(ButlerConfig config, StepRegistry steps, StateStore state,
                             ProcessRunner processes, Secrets secrets) {

    /**
     * The environment the daemon and the CLI run in: the configured state directory, real
     * processes, and secrets from wherever {@code secrets:} points.
     */
    public static RunEnvironment of(ButlerConfig config, StepRegistry steps, Diagnostics diags) {
        return new RunEnvironment(config, steps,
                StateStore.at(config.settings().stateDir()),
                new ForkingProcessRunner(),
                Secrets.load(config.secrets(), diags));
    }
}
