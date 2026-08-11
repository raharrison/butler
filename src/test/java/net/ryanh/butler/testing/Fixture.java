package net.ryanh.butler.testing;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.config.ConfigValidator;
import net.ryanh.butler.config.Secrets;
import net.ryanh.butler.runtime.RunEnvironment;
import net.ryanh.butler.runtime.StateStore;
import net.ryanh.butler.runtime.StepRegistry;
import net.ryanh.butler.spi.ProcessRunner;

import java.nio.file.Path;

/**
 * The scaffolding a run needs in a test: a config, a state directory under {@code @TempDir} and a
 * process runner that forks nothing.
 */
public final class Fixture {

    private Fixture() {
    }

    /**
     * Loads and validates a config the way the CLI does, so a mistake in a fixture surfaces as a
     * diagnostic.
     */
    public static ConfigLoader.Result config(String yaml, StepRegistry steps) {
        ConfigLoader.Result result = ConfigLoader.parse(yaml);
        ConfigValidator.validate(result.config(), result.diagnostics(), steps.conditionParams());
        return result;
    }

    public static RunEnvironment environment(ConfigLoader.Result config, StepRegistry steps,
                                             Path stateDir) {
        return environment(config, steps, stateDir, new FakeProcessRunner());
    }

    public static RunEnvironment environment(ConfigLoader.Result config, StepRegistry steps,
                                             Path stateDir, ProcessRunner processes) {
        return new RunEnvironment(config.config(), steps, StateStore.at(stateDir), processes,
                Secrets.none());
    }
}
