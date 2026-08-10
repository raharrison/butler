package net.ryanh.butler.runtime;

import net.ryanh.butler.config.Diagnostics;
import net.ryanh.butler.config.Secrets;
import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.StepDef;
import net.ryanh.butler.expr.ExprException;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a job and an event into a {@link Plan}.
 *
 * <p>Resolution is lazy per step rather than eager over the whole pipeline, because a step's
 * parameters may refer to {@code steps.earlier.*} and cannot be resolved until that result exists.
 * Each step is therefore resolved, described and simulated in turn, and what it produced is in the
 * context before the next one is looked at.
 *
 * <p>Nothing here calls {@code execute()}. The choice between executing and describing belongs to
 * the runtime, so a step author has no way to leak a side effect into a dry run.
 */
public final class PlanBuilder {

    private PlanBuilder() {
    }

    public static Plan build(ButlerConfig config, JobDef job, Event event,
                             StepRegistry registry, Diagnostics diags) {
        Context ctx = Context.forPlan(config, job, event, Secrets.load(config.secrets(), diags));

        List<Plan.Entry> discover = entries("discover", job.discover(), ctx, registry, diags);
        Plan.Decision when = decide(job, ctx, diags);

        boolean wouldRun = when == null || when.result();
        List<Plan.Entry> steps = wouldRun
                ? entries("step", job.steps(), ctx, registry, diags)
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
     * A job whose condition cannot be evaluated is reported and treated as not running, rather
     * than guessed at: the whole point of showing the decision is that it can be trusted.
     */
    private static Plan.Decision decide(JobDef job, Context ctx, Diagnostics diags) {
        if (job.when() == null) {
            return null;
        }
        try {
            String explained = ctx.explain(job.when());
            return new Plan.Decision(job.when(), explained, ctx.evaluate(job.when()), null);
        } catch (ExprException e) {
            diags.error(job.path() + "/when", "could not be evaluated: " + e.getMessage());
            return new Plan.Decision(job.when(), job.when(), false, e.getMessage());
        }
    }

    private static Plan.Notification notification(JobDef job, Context ctx) {
        if (job.notifyPolicy() == null) {
            return null;
        }
        String message = job.notifyPolicy().messages().get("success");
        if (message == null) {
            return null;
        }
        return new Plan.Notification(job.notifyPolicy().to(), ctx.resolve(message));
    }

    private static List<Plan.Entry> entries(String section, List<StepDef> defs, Context ctx,
                                            StepRegistry registry, Diagnostics diags) {
        List<Plan.Entry> out = new ArrayList<>();
        int number = 0;
        for (StepDef def : defs) {
            Plan.Entry entry = entry(section, number + 1, def, ctx, registry, diags);
            if (entry.number() > 0) {
                number++;
            }
            out.add(entry);
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static Plan.Entry entry(String section, int number, StepDef def, Context ctx,
                                    StepRegistry registry, Diagnostics diags) {
        StepType<Object> type = (StepType<Object>) registry.find(def.uses());
        if (type == null) {
            String problem = "unknown step type \"" + def.uses() + "\"";
            diags.error(def.path() + "/uses", problem);
            return Plan.Entry.failed(section, def.label(), def.uses(), problem);
        }

        if (def.when() != null) {
            try {
                if (!ctx.evaluate(def.when())) {
                    return Plan.Entry.skipped(section, def.label(), def.uses(),
                            "skipped: when=false");
                }
            } catch (ExprException e) {
                diags.error(def.path() + "/when", "could not be evaluated: " + e.getMessage());
                return Plan.Entry.failed(section, def.label(), def.uses(),
                        "when could not be evaluated: " + e.getMessage());
            }
        }

        Object params;
        Map<String, Object> resolved;
        try {
            resolved = resolved(def, ctx);
            params = Params.bind(type.configType(), resolved);
        } catch (ExprException | Params.BindingException e) {
            diags.error(def.path(), e.getMessage());
            return Plan.Entry.failed(section, def.label(), def.uses(), e.getMessage());
        }

        String described;
        List<String> warnings;
        StepResult simulated;
        try {
            described = type.describe(params, ctx);
            warnings = List.copyOf(type.preflight(params, ctx));
            simulated = type.simulate(params, ctx);
        } catch (RuntimeException e) {
            String problem = def.uses() + " could not describe itself: " + e;
            diags.error(def.path(), problem);
            return Plan.Entry.failed(section, def.label(), def.uses(), problem);
        }

        // Only a hole the step invented is a defect: $${ and a shell script full of ${VAR} both
        // reach describe() legitimately, and the resolved parameters are what tell them apart.
        if (described != null && described.contains("${") && !Params.containsTemplate(resolved)) {
            String problem = def.uses() + " left an unresolved ${...} in its description: "
                    + described.strip();
            diags.error(def.path(), problem);
            return Plan.Entry.failed(section, def.label(), def.uses(), problem);
        }

        if (def.register() != null) {
            ctx.register(def.register(), simulated);
        }
        ctx.applyVars(simulated.vars());

        List<String> lines = described == null || described.isBlank()
                ? List.of()
                : List.of(described.stripTrailing().split("\n"));
        return new Plan.Entry(section, number, def.label(), def.uses(), lines, warnings, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolved(StepDef def, Context ctx) {
        return (Map<String, Object>) ctx.resolveDeep(def.params());
    }
}
