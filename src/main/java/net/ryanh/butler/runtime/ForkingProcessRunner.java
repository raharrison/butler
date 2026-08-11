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
 * The {@link ProcessRunner} the daemon installs (DESIGN.md §5.3).
 *
 * <p>Output is drained on separate threads, because a full pipe blocks the child forever; into a
 * bounded buffer, because a chatty process would otherwise exhaust memory; and a timeout kills the
 * whole process tree, because destroying a shell leaves whatever it started still running and still
 * holding the pipe.
 */
public final class ForkingProcessRunner implements ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ForkingProcessRunner.class);

    /**
     * How much of each stream is kept: enough to diagnose a failure from the run record, small
     * enough that a process printing a progress bar for an hour costs nothing.
     */
    static final int CAPTURE_LIMIT = 256 * 1024;

    /**
     * How long a killed process gets to exit before it is killed harder.
     */
    private static final Duration GRACE = Duration.ofSeconds(2);

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
            // printed before that is still the most useful thing about the failure and is reported
            // rather than thrown away. Cleanup needs a thread that is not already interrupted, so
            // the flag is cleared here and restored before returning.
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
     * Waits for the last of the output to arrive and packages it up.
     */
    private static Completed finish(Drain out, Drain err, Instant started, int exitCode,
                                    boolean timedOut) {
        out.join();
        err.join();
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

    private static Drain drain(String name, InputStream stream) {
        Ring ring = new Ring();
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
        return new Drain(thread, ring);
    }

    private record Drain(Thread thread, Ring ring) {
        /**
         * The pipes close when the process dies, so this ends on its own; an interrupt here would
         * cost the output that has already been read for nothing.
         */
        void join() {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String text() {
            return ring.text();
        }
    }

    /**
     * Keeps the last {@link #CAPTURE_LIMIT} characters written to it. The tail is what says why a
     * command failed; the head of a 10MB build log is not worth the heap.
     */
    private static final class Ring {

        private final StringBuilder buffer = new StringBuilder();
        private boolean truncated;

        synchronized void append(char[] chunk, int length) {
            buffer.append(chunk, 0, length);
            if (buffer.length() > CAPTURE_LIMIT) {
                buffer.delete(0, buffer.length() - CAPTURE_LIMIT);
                truncated = true;
            }
        }

        synchronized String text() {
            return truncated ? "[earlier output dropped]\n" + buffer : buffer.toString();
        }
    }
}
