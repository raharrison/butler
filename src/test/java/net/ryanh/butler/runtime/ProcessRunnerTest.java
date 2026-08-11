package net.ryanh.butler.runtime;

import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.testing.Chatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one place a test really forks: a full pipe deadlocking the child, an unbounded capture eating
 * the heap, a timeout that leaves the process running.
 */
class ProcessRunnerTest {

    private final ProcessRunner runner = new ForkingProcessRunner();

    private static ProcessRunner.Command command(String... args) {
        return ProcessRunner.Command.none().argv(Chatter.argv(args));
    }

    @Test
    void capturesStdoutAndTheExitCode() throws Exception {
        ProcessRunner.Completed done = runner.run(command("echo", "hello"));
        assertTrue(done.ok());
        assertEquals(0, done.exitCode());
        assertEquals("hello", done.stdout().strip());
        assertEquals("", done.stderr().strip());
    }

    @Test
    void capturesStderrSeparately() throws Exception {
        ProcessRunner.Completed done = runner.run(command("complain", "that went badly"));
        assertEquals("that went badly", done.stderr().strip());
        assertEquals("", done.stdout().strip());
    }

    @Test
    void reportsANonZeroExit() throws Exception {
        ProcessRunner.Completed done = runner.run(command("exit", "3"));
        assertFalse(done.ok());
        assertEquals(3, done.exitCode());
    }

    @Test
    @DisplayName("a chatty process neither deadlocks nor exhausts memory")
    void tenMegabytesOfOutput() throws Exception {
        ProcessRunner.Completed done = runner.run(
                command("flood", String.valueOf(10 * 1024 * 1024)).timeout(Duration.ofMinutes(1)));

        assertTrue(done.ok(), "the process finished, so nothing deadlocked on a full pipe");
        assertTrue(done.stdout().length() < ForkingProcessRunner.CAPTURE_LIMIT + 100,
                "capture is bounded, got " + done.stdout().length() + " characters");
        assertTrue(done.stdout().startsWith("[earlier output dropped]"),
                "and says so rather than pretending that was all of it");
    }

    @Test
    void aTimeoutKillsTheProcess() throws Exception {
        Instant started = Instant.now();
        ProcessRunner.Completed done = runner.run(
                command("sleep", "60000").timeout(Duration.ofMillis(500)));

        assertTrue(done.timedOut());
        assertFalse(done.ok());
        assertTrue(Duration.between(started, Instant.now()).compareTo(Duration.ofSeconds(30)) < 0,
                "it should not have waited for the process to finish on its own");
    }

    @Test
    @DisplayName("a timeout kills the children too, not just the process that was started")
    void aTimeoutKillsTheWholeTree(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        runner.run(command("spawn", pidFile.toString()).timeout(Duration.ofSeconds(20)));

        long pid = Long.parseLong(Files.readString(pidFile).strip());
        assertFalse(alive(pid), "the grandchild outlived the kill: pid " + pid);
    }

    @Test
    void runsInTheGivenDirectoryWithTheGivenEnvironment(@TempDir Path dir) throws Exception {
        ProcessRunner.Command command = new ProcessRunner.Command(
                Chatter.argv("echo", "wherever"), dir, Map.of("BUTLER_TEST", "1"), null, null);
        assertTrue(runner.run(command).ok());
    }

    @Test
    void anEmptyCommandIsAnError() {
        assertThrows(IOException.class, () -> runner.run(ProcessRunner.Command.none()));
    }

    @Test
    @DisplayName("run_as: wraps the command in sudo -u, which is the whole privilege model")
    void runAsWrapsInSudo() {
        ProcessRunner.Command command = new ProcessRunner.Command(
                List.of("/usr/bin/systemctl", "restart", "api.service"), null, Map.of(),
                "appuser", null);

        assertEquals(List.of("sudo", "-u", "appuser", "/usr/bin/systemctl", "restart",
                "api.service"), ForkingProcessRunner.withRunAs(command));
    }

    @Test
    @DisplayName("without run_as: the command runs as the daemon, with nothing wrapped round it")
    void noRunAsRunsAsTheDaemon() {
        ProcessRunner.Command command = ProcessRunner.Command.none().argv("/bin/true");
        assertEquals(List.of("/bin/true"), ForkingProcessRunner.withRunAs(command));
        assertEquals(List.of("/bin/true"), ForkingProcessRunner.withRunAs(
                new ProcessRunner.Command(List.of("/bin/true"), null, Map.of(), "  ", null)));
    }

    @Test
    @DisplayName("a cancelled process still reports what it printed before it was killed")
    void cancellationKeepsTheOutput() throws Exception {
        Thread caller = Thread.currentThread();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            caller.interrupt();
        });

        ProcessRunner.Completed done = runner.run(command("chatter", "5000"));

        assertTrue(Thread.interrupted(), "the interrupt is handed back to the caller");
        assertTrue(done.timedOut());
        assertTrue(done.stdout().contains("still here"),
                "the tail is the most useful thing about a killed process:\n" + done.stdout());
    }

    /**
     * A killed process is reaped asynchronously, so this gives the OS a moment to agree.
     */
    private static boolean alive(long pid) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) {
                return false;
            }
            Thread.sleep(100);
        }
        return true;
    }
}
