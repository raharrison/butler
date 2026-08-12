package net.ryanh.butler.runtime;

import net.ryanh.butler.config.Diagnostics;
import net.ryanh.butler.config.model.Enums;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.StepDef;
import net.ryanh.butler.expr.ExprException;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.StepResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a job and an event into a {@link Plan}.
 *
 * <p>Resolution is lazy per step rather than eager over the whole pipeline, because a step's
 * parameters may refer to {@code steps.earlier.*}. Each step is resolved, described and simulated
 * in turn, and what it produced is in the context before the next one is looked at.
 *
 * <p>Nothing in the pipeline calls {@code execute()}: the choice between executing and describing
 * belongs to the runtime, so a step author cannot leak a side effect into a dry run.
 * {@code discover:} is the one deliberate exception and runs for real (DESIGN.md §6.2).
 */
public final class PlanBuilder {

    private PlanBuilder() {
    }

    public static Plan build(RunEnvironment env, JobDef job, Event event, Diagnostics diags) {
        StateStore.JobState persisted = env.state().read(job.name());
        Context ctx = Context.forPlan(env, job, event, persisted.values());

        // Discovery runs for real, so it is held to the deadline the run would hold it to.
        Instant deadline = job.timeout() == null ? null : Instant.now().plus(job.timeout());
        List<Plan.Entry> discover = Discovery.run(job, env.steps(), ctx, deadline);
        Plan.Decision when = decide(job, ctx, diags);

        boolean wouldRun = when == null || when.result();
        List<Plan.Entry> steps = wouldRun
                ? entries("step", job.steps(), job, ctx, env.steps(), diags)
                : List.of();

        List<Plan.Hook> hooks = wouldRun ? hooks(job) : List.of();
        Map<String, Object> persist = new LinkedHashMap<>();
        Plan.Notification notify = null;
        if (wouldRun) {
            job.persist().forEach((k, v) -> persist.put(k, ctx.resolveValue(v)));
            notify = notification(job, ctx);
        }

        return new Plan(job.name(), event.trigger(), event.facts(), discover, when, steps,
                hooks, persist, notify);
    }

    /**
     * The lifecycle sections a run has, and why each is not part of the expected path.
     */
    private static List<Plan.Hook> hooks(JobDef job) {
        List<Plan.Hook> hooks = new ArrayList<>();
        if (!job.onFailure().isEmpty()) {
            hooks.add(new Plan.Hook("on_failure", "not shown: reached only on failure"));
        }
        if (!job.onSuccess().isEmpty()) {
            hooks.add(new Plan.Hook("on_success", "not shown: reached only on success"));
        }
        if (!job.always().isEmpty()) {
            hooks.add(new Plan.Hook("always", "not shown: runs after every outcome"));
        }
        return List.copyOf(hooks);
    }

    /**
     * A job whose condition cannot be evaluated is reported and treated as not running rather than
     * guessed at.
     */
    private static Plan.Decision decide(JobDef job, Context ctx, Diagnostics diags) {
        if (job.when() == null) {
            return null;
        }
        try {
            var decision = ctx.decide(job.when());
            return new Plan.Decision(job.when(), decision.explained(), decision.result(), null);
        } catch (ExprException e) {
            diags.error(job.path() + "/when", "could not be evaluated: " + e.getMessage());
            return new Plan.Decision(job.when(), job.when(), false, e.getMessage());
        }
    }

    /**
     * The message a successful run would send, judged the same way {@code JobRunner} judges it. A
     * plan promising a notification the run would not send is the divergence dry run exists to
     * eliminate.
     */
    private static Plan.Notification notification(JobDef job, Context ctx) {
        if (job.notifyPolicy() == null
                || !job.notifyPolicy().on().contains(Enums.Outcome.SUCCESS)) {
            return null;
        }
        String message = job.notifyPolicy().messages().get("success");
        if (message == null) {
            return null;
        }
        return new Plan.Notification(job.notifyPolicy().to(), ctx.resolve(message));
    }

    private static List<Plan.Entry> entries(String section, List<StepDef> defs, JobDef job,
                                            Context ctx, StepRegistry registry, Diagnostics diags) {
        List<Plan.Entry> out = new ArrayList<>();
        int number = 0;
        for (StepDef def : defs) {
            Plan.Entry entry = entry(section, number + 1, def, job, ctx, registry, diags);
            if (entry.number() > 0) {
                number++;
            }
            out.add(entry);
        }
        return List.copyOf(out);
    }

    private static Plan.Entry entry(String section, int number, StepDef def, JobDef job,
                                    Context ctx, StepRegistry registry, Diagnostics diags) {
        StepResolver.Resolved resolved = StepResolver.resolve(def, job, registry, ctx,
                def.timeout());
        switch (resolved) {
            case StepResolver.Skipped skipped -> {
                return Plan.Entry.skipped(section, def.label(), def.uses(), skipped.reason());
            }
            case StepResolver.Unresolvable bad -> {
                diags.error(pathFor(def, bad), bad.problem());
                return Plan.Entry.failed(section, def.label(), def.uses(), bad.problem());
            }
            case StepResolver.Ready ready -> {
                return described(section, number, def, ready, ctx, diags);
            }
        }
    }

    /**
     * An unknown type or an unevaluable condition points at the key that is wrong; anything else
     * is about the parameters as a whole, so it points at the step.
     */
    private static String pathFor(StepDef def, StepResolver.Unresolvable bad) {
        if (bad.problem().startsWith("unknown step type")) {
            return def.path() + "/uses";
        }
        return bad.problem().startsWith("when could not be evaluated")
                ? def.path() + "/when" : def.path();
    }

    private static Plan.Entry described(String section, int number, StepDef def,
                                        StepResolver.Ready ready, Context ctx, Diagnostics diags) {
        String described;
        List<String> warnings;
        StepResult simulated;
        try {
            described = ready.type().describe(ready.params(), ready.ctx());
            warnings = List.copyOf(ready.type().preflight(ready.params(), ready.ctx()));
            StepResult produced = ready.type().simulate(ready.params(), ready.ctx());
            simulated = produced == null ? StepResult.ok() : produced;
        } catch (RuntimeException e) {
            String problem = def.uses() + " could not describe itself: " + e;
            diags.error(def.path(), problem);
            return Plan.Entry.failed(section, def.label(), def.uses(), problem);
        }

        // Only a hole the step invented is a defect: $${ and a shell script full of ${VAR} both
        // reach describe() legitimately, and the resolved parameters are what tell them apart.
        if (described != null && described.contains("${")
                && !Params.containsTemplate(ready.rawParams())) {
            String problem = def.uses() + " left an unresolved ${...} in its description: "
                    + described.strip();
            diags.error(def.path(), problem);
            return Plan.Entry.failed(section, def.label(), def.uses(), problem);
        }

        StepResolver.record(def, simulated, ctx);

        List<String> lines = described == null || described.isBlank()
                ? List.of()
                : List.of(described.stripTrailing().split("\n"));
        return new Plan.Entry(section, number, def.label(), def.uses(), lines, warnings, null, null);
    }
}
