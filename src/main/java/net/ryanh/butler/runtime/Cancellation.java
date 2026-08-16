package net.ryanh.butler.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The switch that produces a {@code CANCELLED} run (DESIGN.md §2.1), flipped by
 * {@link ConcurrencyGate} and by {@link Butler} at shutdown.
 *
 * <p>Cancelling raises the flag and interrupts the run thread. The interrupt gets a step out of
 * whatever it is blocked on; the flag is what tells {@link JobRunner} the step failed because the
 * work was withdrawn rather than because anything was wrong.
 */
public final class Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Thread thread;
    private volatile String reason;

    /**
     * A token nothing holds, for a run nothing can displace: {@code butler trigger} and tests.
     */
    public static Cancellation none() {
        return new Cancellation();
    }

    /**
     * Names the thread to interrupt. A run cancelled before it got here is interrupted now; both
     * fields are volatile, so a cancel racing this is delivered by one side or the other.
     */
    public void on(Thread runThread) {
        this.thread = runThread;
        if (cancelled.get()) {
            runThread.interrupt();
        }
    }

    public void cancel(String why) {
        if (cancelled.compareAndSet(false, true)) {
            reason = why;
            Thread t = thread;
            if (t != null) {
                t.interrupt();
            }
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * @return why the run was withdrawn, or null if it was not
     */
    public String reason() {
        return reason;
    }
}
