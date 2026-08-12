package net.ryanh.butler.runtime;

import net.ryanh.butler.config.Diagnostics;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.TriggerDef;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.TriggerType;
import net.ryanh.butler.spi.Watcher;
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
 * a job, and how many may run at once is {@code settings.max_concurrent_runs}. That is a global
 * bound; the per-job {@code concurrency:} gate is not built yet, so every event that gets a slot
 * runs.
 */
public final class Butler {

    private static final Logger log = LoggerFactory.getLogger(Butler.class);

    /**
     * How long a shutdown waits for runs already in flight before leaving them behind.
     */
    private static final Duration DRAIN = Duration.ofSeconds(30);

    private final RunEnvironment env;
    private final TriggerRegistry triggers;
    private final boolean dryRun;
    private final PrintStream out;

    private final Semaphore slots;
    private final List<Watcher> watchers = new ArrayList<>();
    private final List<Thread> runs = new ArrayList<>();
    private final CountDownLatch stopped = new CountDownLatch(1);

    public Butler(RunEnvironment env, TriggerRegistry triggers, boolean dryRun, PrintStream out) {
        this.env = env;
        this.triggers = triggers;
        this.dryRun = dryRun;
        this.out = out;
        this.slots = new Semaphore(env.config().settings().maxConcurrentRuns());
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
     * Stops the watchers and gives the runs already going a chance to finish.
     */
    public void stop() {
        watchers.forEach(Watcher::stop);
        List<Thread> inFlight;
        synchronized (runs) {
            inFlight = List.copyOf(runs);
        }
        // One deadline for the whole drain, or ten queued runs hold shutdown open for ten times
        // the grace period.
        Instant deadline = Instant.now().plus(DRAIN);
        for (Thread run : inFlight) {
            Duration left = Duration.between(Instant.now(), deadline);
            try {
                if (left.isPositive() && run.join(left)) {
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            log.warn("{} was still running when the drain period ended", run.getName());
        }
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
     */
    private void handle(JobDef job, Event event) {
        Thread thread = Thread.ofVirtual().name("run-" + job.name()).unstarted(() -> {
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                report(job, event);
            } catch (RuntimeException e) {
                log.error("job {} ended in an unhandled error: {}", job.name(), e.toString(), e);
            } finally {
                slots.release();
                synchronized (runs) {
                    runs.remove(Thread.currentThread());
                }
            }
        });
        synchronized (runs) {
            runs.add(thread);
        }
        thread.start();
    }

    /**
     * A dry-run daemon watches and discovers as usual, and prints what each firing would do
     * instead of doing it.
     */
    private void report(JobDef job, Event event) {
        if (!dryRun) {
            new JobRunner(env).run(job, event);
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
