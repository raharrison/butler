package net.ryanh.butler.runtime;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.testing.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The concurrency policy of DESIGN.md §5.3: which event gets the group, and what the ones that do
 * not are told.
 *
 * <p>Platform threads, because these assertions turn on {@link Thread#getState()} reporting a
 * thread parked at the gate.
 */
class ConcurrencyGateTest {

    private final ConcurrencyGate gate = new ConcurrencyGate();

    private static JobDef job(String concurrency) {
        String yaml = """
                jobs:
                  j:
                    on: [{uses: manual}]
                %s    steps: [{uses: control.log, message: hi}]
                """.formatted(concurrency);
        ConfigLoader.Result result = Fixture.config(yaml, StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return result.config().jobs().get("j");
    }

    /**
     * Enters the gate on its own thread, so the caller can watch it queue.
     */
    private Entrant enter(JobDef job, String name) {
        Entrant entrant = new Entrant(job, name);
        entrant.thread.start();
        return entrant;
    }

    private final class Entrant {

        final Cancellation cancel = new Cancellation();
        final AtomicReference<ConcurrencyGate.Admission> admission = new AtomicReference<>();
        final Thread thread;

        Entrant(JobDef job, String name) {
            this.thread = Thread.ofPlatform().name(name)
                    .unstarted(() -> admission.set(gate.enter(job, cancel)));
        }

        ConcurrencyGate.Admission awaitAdmission() throws InterruptedException {
            assertTrue(thread.join(Duration.ofSeconds(10)),
                    thread.getName() + " never got an answer from the gate");
            return admission.get();
        }

        void awaitQueued() {
            Instant deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline)) {
                if (thread.getState() == Thread.State.WAITING) {
                    return;
                }
                Thread.onSpinWait();
            }
            fail(thread.getName() + " never parked at the gate");
        }

        void leave() {
            gate.leave(admission.get().ticket());
        }
    }

    @Test
    @DisplayName("an idle group admits at once")
    void firstThroughTheDoor() throws InterruptedException {
        ConcurrencyGate.Admission admitted = enter(job(""), "first").awaitAdmission();
        assertTrue(admitted.admitted());
        assertNull(admitted.reason());
    }

    @Test
    @DisplayName("queue_newest_only collapses the queue: the event waiting is replaced by a newer")
    void queueKeepsOnlyTheNewest() throws InterruptedException {
        JobDef job = job("");

        Entrant running = enter(job, "running");
        assertTrue(running.awaitAdmission().admitted());

        Entrant older = enter(job, "older");
        older.awaitQueued();

        Entrant newer = enter(job, "newer");
        newer.awaitQueued();

        ConcurrencyGate.Admission displaced = older.awaitAdmission();
        assertFalse(displaced.admitted(), "the older waiting event should have been replaced");
        assertEquals("superseded by a newer event before it started", displaced.reason());
        assertFalse(older.cancel.isCancelled(),
                "a waiter has no step to interrupt; displacement is the answer it reads back");

        running.leave();
        assertTrue(newer.awaitAdmission().admitted(), "the newest event gets the group");
    }

    @Test
    @DisplayName("queue_newest_only: false runs everything, oldest first")
    void queueKeepsEveryoneInOrder() throws InterruptedException {
        JobDef job = job("""
                    concurrency: {mode: queue, queue_newest_only: false}
                """);

        Entrant running = enter(job, "running");
        assertTrue(running.awaitAdmission().admitted());

        Entrant second = enter(job, "second");
        second.awaitQueued();
        Entrant third = enter(job, "third");
        third.awaitQueued();

        running.leave();
        assertTrue(second.awaitAdmission().admitted());
        assertNull(third.admission.get(), "the third is still behind the second");

        second.leave();
        assertTrue(third.awaitAdmission().admitted());
    }

    @Test
    @DisplayName("skip drops the arriving event and does not wait")
    void skipDropsIt() throws InterruptedException {
        JobDef job = job("""
                    concurrency: {mode: skip}
                """);

        Entrant running = enter(job, "running");
        assertTrue(running.awaitAdmission().admitted());

        ConcurrencyGate.Admission skipped = enter(job, "arriving").awaitAdmission();
        assertFalse(skipped.admitted());
        assertEquals("concurrency group \"j\" is busy", skipped.reason());
    }

    @Test
    @DisplayName("cancel_previous withdraws the run in flight and takes its place")
    void cancelPreviousDisplacesTheRunner() throws InterruptedException {
        JobDef job = job("""
                    concurrency: {mode: cancel_previous}
                """);

        Entrant running = enter(job, "running");
        assertTrue(running.awaitAdmission().admitted());

        Entrant newer = enter(job, "newer");
        newer.awaitQueued();

        assertTrue(running.cancel.isCancelled(), "the run in flight should have been cancelled");
        assertEquals("displaced by a newer event", running.cancel.reason());

        running.leave();
        assertTrue(newer.awaitAdmission().admitted());
    }

    @Test
    @DisplayName("a run withdrawn while it queues is let go, and the queue behind it moves up")
    void cancellingAWaiterLetsItGo() throws InterruptedException {
        JobDef job = job("""
                    concurrency: {mode: queue, queue_newest_only: false}
                """);

        Entrant running = enter(job, "running");
        assertTrue(running.awaitAdmission().admitted());

        Entrant second = enter(job, "second");
        second.awaitQueued();
        Entrant third = enter(job, "third");
        third.awaitQueued();

        // A waiter has no step to interrupt, but it does have a park to get out of.
        second.cancel.on(second.thread);
        second.cancel.cancel("butler is shutting down");

        ConcurrencyGate.Admission withdrawn = second.awaitAdmission();
        assertFalse(withdrawn.admitted());
        assertEquals("butler is shutting down", withdrawn.reason());

        running.leave();
        assertTrue(third.awaitAdmission().admitted(),
                "the one behind it still gets its turn");
    }

    @Test
    @DisplayName("a run already withdrawn takes no place in the group at all")
    void cancelledBeforeItAsks() throws InterruptedException {
        JobDef job = job("");

        Entrant running = enter(job, "running");
        assertTrue(running.awaitAdmission().admitted());

        Entrant arriving = new Entrant(job, "arriving");
        arriving.cancel.cancel("butler is shutting down");
        arriving.thread.start();

        ConcurrencyGate.Admission refused = arriving.awaitAdmission();
        assertFalse(refused.admitted());
        assertEquals("butler is shutting down", refused.reason());
    }

    @Test
    @DisplayName("groups do not block each other, and a group defaults to the job name")
    void groupsAreIndependent() throws InterruptedException {
        JobDef mine = job("");
        JobDef theirs = job("""
                    concurrency: {group: other}
                """);
        assertEquals("j", mine.concurrency().group());

        assertTrue(enter(mine, "mine").awaitAdmission().admitted());
        assertTrue(enter(theirs, "theirs").awaitAdmission().admitted());
    }

    @Test
    @DisplayName("leaving a group nobody is waiting for is harmless")
    void leavingAnEmptyGroup() throws InterruptedException {
        Entrant only = enter(job(""), "only");
        only.awaitAdmission();
        only.leave();
        gate.leave(null);
        assertTrue(enter(job(""), "next").awaitAdmission().admitted());
    }
}
