package net.ryanh.butler.runtime;

import net.ryanh.butler.spi.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The {@link ProcessRunner} the daemon installs (DESIGN.md §5.2).
 *
 * <p>Output is drained on separate threads, because a full pipe blocks the child forever; into a
 * bounded buffer, because a chatty process would otherwise exhaust memory; and a timeout kills the
 * whole process tree, because destroying a shell leaves whatever it started still running and still
 * holding the pipe.
 */
public final class ForkingProcessRunner implements ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ForkingProcessRunner.class);

    /**
     * Default {@link #captureBytes}: enough to diagnose a failure, small enough that a chatty
     * process costs nothing.
     */
    static final int CAPTURE_LIMIT = 256 * 1024;

    /**
     * Bytes kept per stream, from {@code settings.process_capture_bytes}.
     */
    private final int captureBytes;

    public ForkingProcessRunner() {
        this(CAPTURE_LIMIT);
    }

    public ForkingProcessRunner(int captureBytes) {
        this.captureBytes = captureBytes;
    }

    /**
     * How long a killed process gets to exit before it is killed harder.
     */
    private static final Duration GRACE = Duration.ofSeconds(2);

    /**
     * How long the last of a process's output is waited for once it has gone. The pipe holds at
     * most a buffer's worth by then, so only a process that left the pipe held reaches this.
     */
    private static final Duration LINGER = Duration.ofSeconds(2);

    @Override
    public Completed run(Command command) throws IOException {
        if (command.argv().isEmpty()) {
            throw new IOException("no command to run");
        }

        ProcessBuilder builder = new ProcessBuilder(withRunAs(command));
        if (command.workingDir() != null) {
            builder.directory(command.workingDir().toFile());
        }
        builder.environment().putAll(command.env());

        Instant started = Instant.now();
        Process process = builder.start();

        Drain out = drain("stdout", process.getInputStream());
        Drain err = drain("stderr", process.getErrorStream());

        boolean timedOut = false;
        try {
            if (command.timeout() == null) {
                process.waitFor();
            } else if (!process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                kill(process);
            }
        } catch (InterruptedException e) {
            // Being interrupted means the run is cancelling this step, so the process goes; what it
            // printed before that is reported rather than thrown away. Cleanup needs a thread that
            // is not already interrupted, so the flag is cleared here and restored before returning.
            Thread.interrupted();
            timedOut = true;
            kill(process);
            Completed cancelled = finish(out, err, started, -1, true);
            Thread.currentThread().interrupt();
            return cancelled;
        }

        return finish(out, err, started, timedOut ? -1 : process.exitValue(), timedOut);
    }

    /**
     * Waits for the last of the output to arrive and packages it up. One deadline for both
     * streams, so a process holding both costs the wait once.
     */
    private static Completed finish(Drain out, Drain err, Instant started, int exitCode,
                                    boolean timedOut) {
        Instant lastCall = Instant.now().plus(LINGER);
        out.join(lastCall);
        err.join(lastCall);
        return new Completed(exitCode, out.text(), err.text(),
                Duration.between(started, Instant.now()), timedOut);
    }

    /**
     * {@code run_as:} is {@code sudo -u <user>} wrapping the command, and this is the only place
     * that is true, so replacing it later touches one class (DESIGN.md §10.2).
     */
    static List<String> withRunAs(Command command) {
        if (command.runAs() == null || command.runAs().isBlank()) {
            return command.argv();
        }
        List<String> argv = new ArrayList<>(command.argv().size() + 3);
        argv.add("sudo");
        argv.add("-u");
        argv.add(command.runAs());
        argv.addAll(command.argv());
        return argv;
    }

    /**
     * Destroys the process and everything it started.
     */
    private static void kill(Process process) {
        // Collected before the parent dies: an orphan is no longer reachable from its handle.
        List<ProcessHandle> tree = new ArrayList<>(process.toHandle().descendants().toList());
        tree.add(process.toHandle());
        tree.forEach(ProcessHandle::destroy);
        try {
            if (!process.waitFor(GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                tree.forEach(ProcessHandle::destroyForcibly);
            }
        } catch (InterruptedException e) {
            tree.forEach(ProcessHandle::destroyForcibly);
            Thread.currentThread().interrupt();
        }
    }

    private Drain drain(String name, InputStream stream) {
        Ring ring = new Ring(captureBytes);
        Thread thread = Thread.ofVirtual().name("drain-" + name).start(() -> {
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] chunk = new char[8192];
                int read;
                while ((read = reader.read(chunk)) >= 0) {
                    ring.append(chunk, read);
                }
            } catch (IOException e) {
                // A killed process closes its pipes mid-read; whatever arrived is still worth
                // reporting.
                log.debug("stopped reading {} of a process: {}", name, e.toString());
            }
        });
        return new Drain(name, thread, ring);
    }

    private record Drain(String name, Thread thread, Ring ring) {
        /**
         * A pipe closes when the last holder does, which is after the process exits if it left
         * something in the background holding it, so the wait is bounded. The reader is abandoned
         * rather than closed out of: closing the read end waits on the same read on some
         * platforms, which is why {@link Ring} is synchronized.
         */
        void join(Instant deadline) {
            Duration left = Duration.between(Instant.now(), deadline);
            try {
                if (left.isPositive() && thread.join(left)) {
                    return;
                }
                log.warn("the process left something running that still holds its {}, so only the "
                        + "output that had arrived is reported", name);
            } catch (InterruptedException e) {
                // An interrupt here would discard output that has already been read.
                Thread.currentThread().interrupt();
            }
        }

        String text() {
            return ring.text();
        }
    }

    /**
     * Keeps the last {@code limit} characters written to it. The tail is what says why a command
     * failed; the head of a 10MB build log is not worth the heap.
     */
    private static final class Ring {

        private final StringBuilder buffer = new StringBuilder();
        private final int limit;
        private boolean truncated;

        Ring(int limit) {
            this.limit = limit;
        }

        synchronized void append(char[] chunk, int length) {
            buffer.append(chunk, 0, length);
            if (buffer.length() > limit) {
                buffer.delete(0, buffer.length() - limit);
                truncated = true;
            }
        }

        synchronized String text() {
            return truncated ? "[earlier output dropped]\n" + buffer : buffer.toString();
        }
    }
}
