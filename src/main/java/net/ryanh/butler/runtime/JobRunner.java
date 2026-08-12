package net.ryanh.butler.runtime;

import net.ryanh.butler.config.model.Enums;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.RetryDef;
import net.ryanh.butler.config.model.StepDef;
import net.ryanh.butler.expr.ExprException;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs a job for real: the step loop, retries, timeouts, lifecycle hooks and the run status rules
 * of DESIGN.md §2.1.
 *
 * <p>Shares {@link StepResolver} and {@link Discovery} with {@link PlanBuilder}, differing in one
 * call: {@code execute()} where the plan calls {@code simulate()}.
 */
public final class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private final RunEnvironment env;

    public JobRunner(RunEnvironment env) {
        this.env = env;
    }

    /**
     * Whether this event is new work. A key that matches the last one processed means the job has
     * already seen it, and is how a restart does not redo everything (DESIGN.md §6.4).
     */
    public boolean isNewWork(JobDef job, Event event) {
        String key = event.dedupeKey();
        return key == null || !key.equals(env.state().read(job.name()).dedupeKey());
    }

    /**
     * Runs the job for one event, or drops it as already done, in which case nothing is
     * discovered, no hook fires and nothing is written.
     */
    public Run run(JobDef job, Event event) {
        Instant started = Instant.now();
        String id = runId(started);
        StateStore.JobState persisted = env.state().read(job.name());

        MDC.put("run_id", id);
        MDC.put("job", job.name());
        try {
            if (!isNewWork(job, event)) {
                log.info("event already processed, dropping (dedupe key {})", event.dedupeKey());
                return new Run(id, job.name(), event.trigger(), event.facts(), Run.Status.SKIPPED,
                        started, Duration.ZERO, List.of(), null, List.of(), persisted.values(),
                        null, null, "already processed: the dedupe key has not changed");
            }
            return execute(job, event, id, started, persisted);
        } finally {
            MDC.remove("run_id");
            MDC.remove("job");
            MDC.remove("step");
        }
    }

    /**
     * What {@code butler adopt} recorded for one job.
     */
    public record Adoption(String job, List<Plan.Entry> discover, Map<String, Object> state,
                           String dedupeKey) {
    }

    /**
     * Records current reality as state without executing any steps, and records the dedupe key of
     * whatever is already present: the install-time step on a host that is already serving
     * (DESIGN.md §6.3).
     *
     * <p>Both halves matter. Without the state the first event judges against nothing; without the
     * dedupe key an artifact already sitting in the watch directory fires the moment the daemon
     * starts.
     */
    public Adoption adopt(JobDef job, Event candidate) {
        Instant now = Instant.now();
        Event event = candidate == null ? new Event("adopt", Map.of(), null) : candidate;
        StateStore.JobState persisted = env.state().read(job.name());

        MDC.put("run_id", runId(now));
        MDC.put("job", job.name());
        try {
            Context ctx = Context.forRun(env, job, event, persisted.values(), runId(now), now);
            // No deadline: adopting is not a run, so a step is held only to its own timeout.
            List<Plan.Entry> discovered = Discovery.run(job, env.steps(), ctx, null);

            String dedupeKey = event.dedupeKey() == null
                    ? persisted.dedupeKey() : event.dedupeKey();
            StateStore.JobState state =
                    new StateStore.JobState(dedupeKey, now, ctx.state());
            try {
                env.state().write(job.name(), state);
            } catch (IOException e) {
                log.error("could not write state to {}: {}", env.state().fileFor(job.name()),
                        e.toString());
            }
            return new Adoption(job.name(), discovered, state.values(), dedupeKey);
        } finally {
            MDC.remove("run_id");
            MDC.remove("job");
            MDC.remove("step");
        }
    }

    private Run execute(JobDef job, Event event, String id, Instant started,
                        StateStore.JobState persisted) {
        Context ctx = Context.forRun(env, job, event, persisted.values(), id, started);
        Instant deadline = job.timeout() == null ? null : started.plus(job.timeout());

        List<Plan.Entry> discovered = Discovery.run(job, env.steps(), ctx, deadline);
        List<Run.Step> steps = new ArrayList<>();

        Plan.Decision decision = decide(job, ctx);
        Outcome outcome = judged(job, decision, ctx, deadline, steps);

        Duration took = Duration.between(started, Instant.now());
        ctx.outcome(outcome.status().toString(), took, outcome.failedStep(), outcome.message());

        // The lifecycle order of DESIGN.md §2.1: hooks, persist, record, notify.
        hooks(job, ctx, outcome.status(), steps);

        Map<String, Object> persist = outcome.status() == Run.Status.SUCCESS
                ? evaluate(job.persist(), ctx)
                : Map.of();
        record(job, event, ctx, outcome.status(), persist, persisted);
        Plan.Notification notification = notify(job, ctx, outcome.status());

        log.info("{} in {}", outcome.status(), Durations.format(took));
        return new Run(id, job.name(), event.trigger(), event.facts(), outcome.status(), started,
                took, discovered, decision, List.copyOf(steps), persist, notification,
                outcome.failedStep(), outcome.message());
    }

    /**
     * How the run ended, and why.
     */
    private record Outcome(Run.Status status, String failedStep, String message) {
    }

    /**
     * The job-level {@code when:}, judged against what discovery just observed.
     *
     * @return null when the job has no condition
     */
    private Plan.Decision decide(JobDef job, Context ctx) {
        if (job.when() == null) {
            return null;
        }
        try {
            var judged = ctx.decide(job.when());
            return new Plan.Decision(job.when(), judged.explained(), judged.result(), null);
        } catch (ExprException e) {
            return new Plan.Decision(job.when(), job.when(), false, e.getMessage());
        }
    }

    /**
     * What the decision means for the run. A condition that cannot be evaluated fails the run
     * rather than skipping it: "we could not tell" and "there is nothing to do" are different
     * answers and only one of them is safe to assume.
     */
    private Outcome judged(JobDef job, Plan.Decision decision, Context ctx, Instant deadline,
                           List<Run.Step> steps) {
        if (decision == null) {
            return pipeline(job, ctx, deadline, steps);
        }
        if (decision.error() != null) {
            log.error("the job's when could not be evaluated: {}", decision.error());
            return new Outcome(Run.Status.FAILED, null,
                    "when could not be evaluated: " + decision.error());
        }
        if (!decision.result()) {
            log.info("nothing to do: when is false ({})", decision.explained());
            return new Outcome(Run.Status.SKIPPED, null,
                    "the job's when is false: " + decision.explained());
        }
        return pipeline(job, ctx, deadline, steps);
    }

    private Outcome pipeline(JobDef job, Context ctx, Instant deadline, List<Run.Step> steps) {
        for (StepDef def : job.steps()) {
            if (expired(deadline)) {
                return timedOut(job, def.label());
            }
            Executed executed = step("step", def, job, ctx, deadline, steps);
            if (!executed.result().isFailed() || def.continueOnError()) {
                continue;
            }
            // A step cut short by the job's deadline is the job's timeout, not its own failure.
            // Anything else keeps its own message: "the disk is full" is more use than "too slow".
            return executed.timedOut() && expired(deadline)
                    ? timedOut(job, def.label())
                    : new Outcome(Run.Status.FAILED, def.label(), executed.result().message());
        }
        return new Outcome(Run.Status.SUCCESS, null, null);
    }

    /**
     * A job timeout ends the run FAILED rather than CANCELLED, so {@code on_failure:} runs. The
     * step it stopped at is named, because that is what a notification template asks for.
     */
    private Outcome timedOut(JobDef job, String step) {
        String message = "the job's timeout of " + Durations.format(job.timeout())
                + " was exceeded";
        log.error(message);
        return new Outcome(Run.Status.FAILED, step, message);
    }

    /**
     * What a step came to: its result, and whether it ran out of time rather than deciding
     * anything.
     */
    private record Executed(StepResult result, boolean timedOut) {
    }

    /**
     * Runs one step, honouring its {@code when:}, {@code retry:} and {@code timeout:}, and putting
     * what it produced where the rest of the run can read it.
     */
    private Executed step(String section, StepDef def, JobDef job, Context ctx,
                          Instant deadline, List<Run.Step> steps) {
        MDC.put("step", def.label());
        try {
            Duration budget = StepExecution.budget(def.timeout(), deadline);
            StepResolver.Resolved resolved =
                    StepResolver.resolve(def, job, env.steps(), ctx, budget);

            Executed executed = switch (resolved) {
                case StepResolver.Skipped skipped -> {
                    log.info("skipped: when is false");
                    yield new Executed(StepResult.skipped(skipped.reason()), false);
                }
                case StepResolver.Unresolvable bad -> {
                    log.error("cannot run: {}", bad.problem());
                    yield new Executed(StepResult.failed(bad.problem()), false);
                }
                case StepResolver.Ready ready -> attempts(def, ready, deadline);
            };

            StepResult result = executed.result();
            StepResolver.record(def, result, ctx);
            steps.add(new Run.Step(section, def.label(), def.uses(), result.status(),
                    result.duration(), result.attempts(), result.message()));

            if (result.isFailed()) {
                log.error("failed after {} attempt(s): {}", result.attempts(), result.message());
            } else if (result.isOk()) {
                log.info("ok in {}", Durations.format(result.duration()));
            }
            return executed;
        } finally {
            MDC.remove("step");
        }
    }

    /**
     * The retry policy of DESIGN.md §3.4. The result carries how many tries it took, which is what
     * {@code steps.x.attempts} reports.
     */
    private Executed attempts(StepDef def, StepResolver.Ready ready, Instant deadline) {
        RetryDef retry = def.retry();
        int allowed = retry == null ? 1 : retry.attempts();

        Executed executed = null;
        for (int attempt = 1; attempt <= allowed; attempt++) {
            StepExecution.Attempt made =
                    StepExecution.once(ready, StepExecution.budget(def.timeout(), deadline));
            executed = new Executed(made.result().attempts(attempt), made.timedOut());
            if (made.stranded()) {
                log.warn("the step ignored its interrupt and is still running; it is no longer "
                        + "part of this run");
            }
            if (!executed.result().isFailed() || attempt == allowed
                    || !retryable(retry, made.timedOut())) {
                break;
            }
            Duration delay = delay(retry, attempt);
            log.warn("attempt {} of {} failed ({}); retrying in {}", attempt, allowed,
                    executed.result().message(), Durations.format(delay));
            if (!sleep(delay, deadline)) {
                break;
            }
        }
        return executed;
    }

    private static boolean retryable(RetryDef retry, boolean timedOut) {
        if (retry == null) {
            return false;
        }
        return switch (retry.on()) {
            case ALWAYS -> true;
            case TIMEOUT -> timedOut;
            case FAILURE -> !timedOut;
        };
    }

    private static Duration delay(RetryDef retry, int attempt) {
        if (retry.backoff() == Enums.Backoff.FIXED) {
            return retry.delay();
        }
        // Doubles per attempt already made. Capped because a large attempts: would overflow the
        // shift, and Duration.multipliedBy throws rather than saturating.
        long doublings = 1L << Math.min(attempt - 1, 32);
        return retry.delay().multipliedBy(doublings);
    }

    /**
     * @return false if the wait was cut short, in which case there is no point trying again
     */
    private static boolean sleep(Duration delay, Instant deadline) {
        Duration wait = StepExecution.budget(delay, deadline);
        if (wait == null || wait.isNegative() || wait.isZero()) {
            return !expired(deadline);
        }
        try {
            Thread.sleep(wait);
            return !expired(deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean expired(Instant deadline) {
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    /**
     * The lifecycle hooks. A failure inside one is logged rather than fatal, and a hook is not held
     * to the job's deadline: after a job timeout there is none left, and {@code on_failure:} exists
     * to clean up after exactly that. Each hook step still honours its own {@code timeout:}.
     */
    private void hooks(JobDef job, Context ctx, Run.Status status, List<Run.Step> steps) {
        if (status == Run.Status.CANCELLED) {
            return;
        }
        if (status == Run.Status.FAILED) {
            hook("on_failure", job.onFailure(), job, ctx, steps);
        }
        if (status == Run.Status.SUCCESS) {
            hook("on_success", job.onSuccess(), job, ctx, steps);
        }
        hook("always", job.always(), job, ctx, steps);
    }

    private void hook(String section, List<StepDef> defs, JobDef job, Context ctx,
                      List<Run.Step> steps) {
        for (StepDef def : defs) {
            if (step(section, def, job, ctx, null, steps).result().isFailed()) {
                log.error("{} step \"{}\" failed; the run's own status stands",
                        section, def.label());
            }
        }
    }

    private static Map<String, Object> evaluate(Map<String, String> templates, Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        templates.forEach((k, v) -> out.put(k, ctx.resolveValue(v)));
        return Collections.unmodifiableMap(out);
    }

    /**
     * Renders the job's notify policy and sends it. A channel that refuses the message is logged
     * and no more: the run has already ended, and failing it now would report a deployment that
     * worked as one that did not.
     */
    private Plan.Notification notify(JobDef job, Context ctx, Run.Status status) {
        if (job.notifyPolicy() == null) {
            return null;
        }
        Enums.Outcome outcome = switch (status) {
            case SUCCESS -> Enums.Outcome.SUCCESS;
            case FAILED -> Enums.Outcome.FAILURE;
            case SKIPPED, CANCELLED -> null;
        };
        if (outcome == null || !job.notifyPolicy().on().contains(outcome)) {
            return null;
        }
        String template = job.notifyPolicy().messages()
                .get(outcome.name().toLowerCase(Locale.ROOT));
        if (template == null) {
            return null;
        }
        String to = job.notifyPolicy().to();
        String message = ctx.resolve(template);
        log.info("notify {}: {}", to, message);
        try {
            ctx.notifications().send(to, message);
        } catch (Exception e) {
            log.error("could not notify {}: {}", to, e.toString());
        }
        return new Plan.Notification(to, message);
    }

    /**
     * Writes the dedupe key and everything the run learned. A run that ended {@code SKIPPED} still
     * records both, or every poll would rediscover and re-skip forever (DESIGN.md §6.2 rule 6). A
     * {@code CANCELLED} run records nothing: the work was withdrawn, not done.
     */
    private void record(JobDef job, Event event, Context ctx, Run.Status status,
                        Map<String, Object> persist, StateStore.JobState previous) {
        if (status == Run.Status.CANCELLED) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>(ctx.state());
        values.putAll(persist);

        StateStore.JobState state = new StateStore.JobState(
                event.dedupeKey() == null ? previous.dedupeKey() : event.dedupeKey(),
                Instant.now(), values);
        try {
            env.state().write(job.name(), state);
        } catch (IOException e) {
            log.error("could not write state to {}: {}", env.state().fileFor(job.name()),
                    e.toString());
        }
    }

    /**
     * Sortable, unique enough for one host, and readable in a file name.
     */
    private static String runId(Instant started) {
        return RUN_ID.format(started.truncatedTo(ChronoUnit.SECONDS)) + "-"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0x10000));
    }
}
