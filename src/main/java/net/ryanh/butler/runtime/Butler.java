package net.ryanh.butler.runtime;

import net.ryanh.butler.config.Diagnostics;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.TriggerDef;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.TriggerType;
import net.ryanh.butler.spi.Watcher;
import net.ryanh.butler.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

/**
 * The daemon: one watcher per trigger, each on its own virtual thread, draining events into runs.
 *
 * <p>No executor service. A watcher is a thread that polls and sleeps, a run is a thread that runs
 * a job, and two policies bound how much happens at once: {@code settings.max_concurrent_runs}
 * globally, and the per-job {@link ConcurrencyGate} within a group.
 */
public final class Butler {

    private static final Logger log = LoggerFactory.getLogger(Butler.class);

    /**
     * How long a cancelled run is given to notice. Short, because the generous wait already
     * happened.
     */
    private static final Duration LAST_CALL = Duration.ofSeconds(5);

    private final RunEnvironment env;
    private final TriggerRegistry triggers;
    private final boolean dryRun;
    private final PrintStream out;

    private final Semaphore slots;
    private final ConcurrencyGate gate = new ConcurrencyGate();
    private final List<Watcher> watchers = new ArrayList<>();
    private final List<InFlight> runs = new ArrayList<>();
    private final CountDownLatch stopped = new CountDownLatch(1);

    public Butler(RunEnvironment env, TriggerRegistry triggers, boolean dryRun, PrintStream out) {
        this.env = env;
        this.triggers = triggers;
        this.dryRun = dryRun;
        this.out = out;
        this.slots = new Semaphore(env.config().settings().maxConcurrentRuns());
    }

    private record InFlight(Thread thread, Cancellation cancel) {
    }

    /**
     * Starts a watcher for every trigger of every job. One that will not start is logged and
     * skipped, since the other jobs are still worth watching for.
     */
    @SuppressWarnings("unchecked")
    public void start() {
        for (JobDef job : env.config().jobs().values()) {
            for (TriggerDef def : job.on()) {
                TriggerType<Object> type = (TriggerType<Object>) triggers.find(def.uses());
                if (type == null) {
                    log.error("job {} watches with unknown trigger type {}", job.name(),
                            def.uses());
                    continue;
                }
                try {
                    Object params = Params.bind(type.configType(), def.params());
                    watchers.add(type.start(params, event -> handle(job, event),
                            new Triggering(job.name(), env.config().settings().pollInterval(),
                                    dryRun)));
                    log.info("watching {} for job {}", def.uses(), job.name());
                } catch (RuntimeException e) {
                    log.error("job {} could not start its {} trigger: {}", job.name(), def.uses(),
                            e.toString());
                }
            }
        }
        log.info("butler is up: {} watcher(s), up to {} concurrent run(s){}", watchers.size(),
                env.config().settings().maxConcurrentRuns(),
                dryRun ? ", reporting only" : "");
    }

    /**
     * Stops the watchers, lets the runs already going finish within {@code settings.shutdown_grace}
     * and cancels whatever outlasts it.
     *
     * <p>The grace period is one deadline for the whole drain rather than per run, or ten in-flight
     * runs would hold shutdown open for ten times as long.
     */
    public void stop() {
        watchers.forEach(Watcher::stop);
        List<InFlight> inFlight;
        synchronized (runs) {
            inFlight = List.copyOf(runs);
        }
        if (inFlight.isEmpty()) {
            finish();
            return;
        }

        Duration grace = env.config().settings().shutdownGrace();
        log.info("stopping: {} run(s) in flight, letting them finish for up to {}",
                inFlight.size(), Durations.format(grace));

        List<InFlight> remaining = drain(inFlight, Instant.now().plus(grace));
        for (InFlight run : remaining) {
            log.warn("{} outlasted the {} drain period and is being cancelled",
                    run.thread().getName(), Durations.format(grace));
            run.cancel().cancel("butler is shutting down");
        }
        for (InFlight run : drain(remaining, Instant.now().plus(LAST_CALL))) {
            log.warn("{} did not stop when cancelled and is being left behind",
                    run.thread().getName());
        }
        finish();
    }

    /**
     * @return the runs still going when the deadline passed
     */
    private static List<InFlight> drain(List<InFlight> runs, Instant deadline) {
        List<InFlight> left = new ArrayList<>();
        boolean interrupted = false;
        for (InFlight run : runs) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (interrupted || !remaining.isPositive()) {
                if (run.thread().isAlive()) {
                    left.add(run);
                }
                continue;
            }
            try {
                if (!run.thread().join(remaining)) {
                    left.add(run);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                left.add(run);
            }
        }
        return left;
    }

    private void finish() {
        stopped.countDown();
        log.info("butler is down");
    }

    /**
     * Blocks until {@link #stop} has finished, which is what a daemon's main thread does.
     */
    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    /**
     * One event, on its own virtual thread, so a watcher is never held up by the run it caused.
     *
     * <p>The concurrency gate is entered before the global permit rather than after it: an event
     * waiting its turn within its own group has no business occupying a slot another job could use.
     */
    private void handle(JobDef job, Event event) {
        Cancellation cancel = new Cancellation();
        Thread thread = Thread.ofVirtual().name("run-" + job.name())
                .unstarted(() -> admitted(job, event, cancel));
        synchronized (runs) {
            runs.add(new InFlight(thread, cancel));
        }
        thread.start();
    }

    /**
     * Waits for this event's turn in its group, then runs it. The thread takes itself off the
     * in-flight list however it ends, so a shutdown drain waits only for what is still going.
     */
    private void admitted(JobDef job, Event event, Cancellation cancel) {
        try {
            ConcurrencyGate.Admission admission = gate.enter(job, cancel);
            if (!admission.admitted()) {
                log.info("job {} did not run this event: {}", job.name(), admission.reason());
                return;
            }
            try {
                bounded(job, event, cancel);
            } finally {
                gate.leave(admission.ticket());
            }
        } finally {
            synchronized (runs) {
                runs.removeIf(inFlight -> inFlight.thread() == Thread.currentThread());
            }
        }
    }

    /**
     * The run itself, inside the {@code max_concurrent_runs} bound.
     */
    private void bounded(JobDef job, Event event, Cancellation cancel) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            report(job, event, cancel);
        } catch (RuntimeException e) {
            log.error("job {} ended in an unhandled error: {}", job.name(), e.toString(), e);
        } finally {
            slots.release();
        }
    }

    /**
     * A dry-run daemon watches and discovers as usual, and prints what each firing would do
     * instead of doing it.
     */
    private void report(JobDef job, Event event, Cancellation cancel) {
        if (!dryRun) {
            new JobRunner(env, cancel).run(job, event);
            return;
        }
        Diagnostics diags = new Diagnostics();
        Plan plan = PlanBuilder.build(env, job, event, diags);
        synchronized (out) {
            out.print(PlanRenderer.render(plan));
            out.println();
        }
        if (diags.hasErrors()) {
            log.error("job {} has a step that could not be resolved", job.name());
        }
    }
}
