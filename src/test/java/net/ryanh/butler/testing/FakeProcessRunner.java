package net.ryanh.butler.testing;

import net.ryanh.butler.spi.ProcessRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A {@link ProcessRunner} that forks nothing and remembers what it was asked to run, which is what
 * a step test asserts on. Starting a process for real is {@code ProcessRunnerTest}'s job.
 */
public final class FakeProcessRunner implements ProcessRunner {

    private final List<Command> commands = new ArrayList<>();
    private Function<Command, Completed> reply =
            c -> new Completed(0, "", "", Duration.ZERO, false);

    public FakeProcessRunner replying(int exitCode, String stdout, String stderr) {
        this.reply = c -> new Completed(exitCode, stdout, stderr, Duration.ZERO, false);
        return this;
    }

    public FakeProcessRunner replying(Function<Command, Completed> answer) {
        this.reply = answer;
        return this;
    }

    public List<Command> commands() {
        return List.copyOf(commands);
    }

    public Command last() {
        return commands.getLast();
    }

    @Override
    public Completed run(Command command) {
        commands.add(command);
        return reply.apply(command);
    }
}
