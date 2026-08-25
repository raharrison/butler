package net.ryanh.butler.runtime;

import net.ryanh.butler.config.model.ConcurrencyDef;
import net.ryanh.butler.config.model.JobDef;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serialises runs within a concurrency group (DESIGN.md §5.3).
 *
 * <p>Entered before the global {@code max_concurrent_runs} permit, so an event waiting its turn in
 * a group does not occupy a slot another job could be using.
 */
public final class ConcurrencyGate {

    private final Map<String, Group> groups = new ConcurrentHashMap<>();

    /**
     * A place in a group, held for as long as the run lasts.
     */
    public static final class Ticket {

        private final Group group;
        private final Cancellation cancellation;
        private boolean displaced;
        private String reason;

        private Ticket(Group group, Cancellation cancellation) {
            this.group = group;
            this.cancellation = cancellation;
        }
    }

    /**
     * @param ticket the place to hand back when the run ends, or null if it never started
     * @param reason why it never started, or null
     */
    public record Admission(Ticket ticket, String reason) {

        public boolean admitted() {
            return ticket != null;
        }
    }

    /**
     * Waits for this event's turn in its job's group. An admitted caller must {@link #leave} its
     * ticket.
     */
    public Admission enter(JobDef job, Cancellation cancellation) {
        ConcurrencyDef policy = job.concurrency();
        Group group = groups.computeIfAbsent(policy.group(), Group::new);
        return group.enter(policy, cancellation);
    }

    public void leave(Ticket ticket) {
        if (ticket != null) {
            ticket.group.leave(ticket);
        }
    }

    /**
     * One group's state, and the monitor everything waiting on it parks against.
     */
    private static final class Group {

        private final String name;
        private Ticket running;
        private final Deque<Ticket> waiting = new ArrayDeque<>();

        Group(String name) {
            this.name = name;
        }

        synchronized Admission enter(ConcurrencyDef policy, Cancellation cancellation) {
            if (cancellation.isCancelled()) {
                return new Admission(null, withdrawn(cancellation));
            }
            Ticket mine = new Ticket(this, cancellation);
            if (running == null && waiting.isEmpty()) {
                running = mine;
                return new Admission(mine, null);
            }

            switch (policy.mode()) {
                case SKIP -> {
                    return new Admission(null, "concurrency group \"" + name + "\" is busy");
                }
                case CANCEL_PREVIOUS -> {
                    displaceWaiting("displaced by a newer event");
                    if (running != null) {
                        running.cancellation.cancel("displaced by a newer event");
                    }
                }
                case QUEUE -> {
                    if (policy.queueNewestOnly()) {
                        displaceWaiting("superseded by a newer event before it started");
                    }
                }
            }
            waiting.addLast(mine);

            while (!mine.displaced && (running != null || waiting.peekFirst() != mine)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    waiting.remove(mine);
                    notifyAll();
                    return new Admission(null, cancellation.isCancelled()
                            ? withdrawn(cancellation) : "interrupted while waiting for its turn");
                }
            }
            if (mine.displaced) {
                waiting.remove(mine);
                return new Admission(null, mine.reason);
            }
            waiting.removeFirst();
            running = mine;
            return new Admission(mine, null);
        }

        synchronized void leave(Ticket ticket) {
            if (running == ticket) {
                running = null;
            } else {
                waiting.remove(ticket);
            }
            notifyAll();
        }

        private static String withdrawn(Cancellation cancellation) {
            return cancellation.reason() == null ? "the run was cancelled" : cancellation.reason();
        }

        /**
         * A flag rather than an interrupt: a waiter has no step to get out of.
         */
        private void displaceWaiting(String why) {
            for (Ticket ticket : waiting) {
                ticket.displaced = true;
                ticket.reason = why;
            }
            waiting.clear();
            notifyAll();
        }
    }
}
