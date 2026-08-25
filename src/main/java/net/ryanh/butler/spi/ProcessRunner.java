package net.ryanh.butler.spi;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * The one way a step starts a process.
 *
 * <p>On the SPI rather than in the runtime because a step may not depend on the runtime and a test
 * may not fork. The implementation the daemon installs does the draining, timeout and kill-the-tree
 * work of DESIGN.md §5.2, and applies {@code run_as:}; a test hands the step a fake and asserts on
 * the command it was given.
 */
public interface ProcessRunner {

    /**
     * Runs a command to completion, until its timeout expires, or until the calling thread is
     * interrupted, which is how the runtime cancels a step that has run out of time.
     *
     * <p>An interrupt destroys the process tree and comes back as a {@link Completed} that
     * {@link Completed#timedOut()}, so whatever the process printed is still reported.
     */
    Completed run(Command command) throws IOException;

    /**
     * What to run, and the process settings the reserved step keys supply. A step gets its own
     * already filled in from {@link RunContext#command()} and only says what to execute.
     *
     * @param argv       the program and its arguments, with no shell involved unless the step put
     *                   one there itself
     * @param workingDir directory to start in, or null for the daemon's own
     * @param env        variables added to the daemon's environment
     * @param runAs      user to run as, or null for the daemon's own identity
     * @param timeout    how long the process may take, or null for no limit of its own
     */
    record Command(List<String> argv, Path workingDir, Map<String, String> env, String runAs,
                   Duration timeout) {

        public Command {
            argv = argv == null ? List.of() : List.copyOf(argv);
            env = env == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }

        /**
         * A command with no process settings, which is what a context outside a step has.
         */
        public static Command none() {
            return new Command(List.of(), null, Map.of(), null, null);
        }

        public Command argv(List<String> program) {
            return new Command(program, workingDir, env, runAs, timeout);
        }

        public Command argv(String... program) {
            return argv(Arrays.asList(program));
        }

        public Command timeout(Duration limit) {
            return new Command(argv, workingDir, env, runAs, limit);
        }

        /**
         * The command as it would be typed, for {@code describe()} and log lines. Arguments holding
         * whitespace are quoted so the reader can see where each one ends.
         */
        public String display() {
            List<String> parts = new ArrayList<>(argv.size());
            for (String arg : argv) {
                parts.add(arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace)
                        ? "'" + arg + "'" : arg);
            }
            return String.join(" ", parts);
        }
    }

    /**
     * What a finished process left behind.
     *
     * @param stdout   captured output, truncated at the front if the process was chatty
     * @param timedOut whether the process was killed for exceeding its timeout rather than exiting
     */
    record Completed(int exitCode, String stdout, String stderr, Duration duration,
                     boolean timedOut) {

        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }
}
