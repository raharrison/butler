package net.ryanh.butler.runtime;

import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.StepDef;
import net.ryanh.butler.expr.ExprException;
import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that happens to a step before something is done with it: find its type, judge its
 * {@code when:}, resolve its parameters and bind them to the step's own record.
 *
 * <p>{@link PlanBuilder} and {@link JobRunner} share this and differ in one call,
 * {@code simulate()} against {@code execute()}. A second copy either side would drift until the
 * dry run stopped predicting the real run.
 *
 * <p>Problems come back as {@link Unresolvable} rather than as a diagnostic, so the caller
 * decides whether it is a {@code file:line:col} error or a failed step.
 */
public final class StepResolver {

    private StepResolver() {
    }

    public sealed interface Resolved {
    }

    /**
     * The step, ready to be described or executed, in the context view it should see.
     *
     * @param params    the step's own config record
     * @param rawParams the same parameters as a map, after interpolation
     */
    public record Ready(StepType<Object> type, Object params, Map<String, Object> rawParams,
                        Context ctx) implements Resolved {
    }

    /**
     * The step's {@code when:} was false.
     */
    public record Skipped(String reason) implements Resolved {
    }

    /**
     * The step could not be got ready at all: unknown type, an unevaluable condition, or a
     * parameter that does not fit.
     */
    public record Unresolvable(String problem) implements Resolved {
    }

    @SuppressWarnings("unchecked")
    public static Resolved resolve(StepDef def, JobDef job, StepRegistry registry, Context ctx,
                                   Duration timeout) {
        StepType<Object> type = (StepType<Object>) registry.find(def.uses());
        if (type == null) {
            return new Unresolvable("unknown step type \"" + def.uses() + "\"");
        }

        if (def.when() != null) {
            try {
                if (!ctx.evaluate(def.when())) {
                    return new Skipped("skipped: when=false");
                }
            } catch (ExprException e) {
                return new Unresolvable("when could not be evaluated: " + e.getMessage());
            }
        }

        try {
            Map<String, Object> resolved = parameters(def, type, ctx);
            Object params = Params.bind(type.configType(), resolved);
            return new Ready(type, params, resolved, ctx.forStep(command(def, job, ctx, timeout)));
        } catch (ExprException | Params.BindingException e) {
            return new Unresolvable(e.getMessage());
        }
    }

    /**
     * Interpolates the step's parameters, except the ones it reads as conditions: a condition is
     * parsed rather than rendered, and rendering {@code json.version == ${trigger.version}} would
     * turn a comparison into text no parser accepts.
     */
    private static Map<String, Object> parameters(StepDef def, StepType<Object> type, Context ctx) {
        List<String> conditions = type.conditions();
        Map<String, Object> out = new LinkedHashMap<>();
        def.params().forEach((k, v) ->
                out.put(k, conditions.contains(k) ? v : ctx.resolveDeep(v)));
        return out;
    }

    /**
     * The reserved keys that describe a process, resolved: {@code working_dir:}, the step's
     * {@code env:} merged over the job's, {@code run_as:} and the time the step is allowed.
     */
    private static ProcessRunner.Command command(StepDef def, JobDef job, Context ctx,
                                                 Duration timeout) {
        Map<String, String> env = new LinkedHashMap<>();
        job.env().forEach((k, v) -> env.put(k, ctx.resolve(v)));
        def.env().forEach((k, v) -> env.put(k, ctx.resolve(v)));

        String workingDir = def.workingDir() == null ? null : ctx.resolve(def.workingDir());
        String runAs = def.runAs() == null ? null : ctx.resolve(def.runAs());

        return new ProcessRunner.Command(List.of(),
                workingDir == null ? null : Path.of(workingDir), env, runAs, timeout);
    }

    /**
     * Puts what a step produced where the rest of the run can read it: under {@code register:} if
     * it was given a name, and in {@code vars.*} for anything the result carried there.
     */
    public static void record(StepDef def, StepResult result, Context ctx) {
        if (def.register() != null) {
            ctx.register(def.register(), result);
        }
        ctx.applyVars(result.vars());
    }
}
