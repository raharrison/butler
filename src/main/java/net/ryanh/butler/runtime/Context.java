package net.ryanh.butler.runtime;

import net.ryanh.butler.config.Secrets;
import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.expr.Evaluator;
import net.ryanh.butler.expr.Expressions;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link RunContext} implementation: the eight namespaces of DESIGN.md §2.2, plus the two
 * things a run adds to them as it goes - results registered by {@code register:} and variables
 * written by steps.
 *
 * <p>Everything else is fixed once the context is built. The namespaces are distinct names on
 * purpose, so a later one never shadows an earlier one.
 */
public final class Context implements RunContext {

    private final Map<String, Object> namespaces = new LinkedHashMap<>();
    private final Map<String, Object> vars = new LinkedHashMap<>();
    private final Map<String, Object> steps = new LinkedHashMap<>();
    private final boolean dryRun;
    private final Evaluator evaluator;

    private Context(boolean dryRun) {
        this.dryRun = dryRun;
        // Reads the live map, so a result registered mid-run is visible to the next step.
        this.evaluator = new Evaluator(namespaces::get);
    }

    /**
     * The context a plan is built against. Values that only a real run can know - the run id, how
     * long it took, which step failed - render as placeholders, because a plan has to be
     * deterministic and there is no honest value to put there.
     */
    public static Context forPlan(ButlerConfig config, JobDef job, Event event, Secrets secrets) {
        Context ctx = new Context(true);

        ctx.namespaces.put("vars", ctx.vars);
        ctx.namespaces.put("trigger", event.facts());
        ctx.namespaces.put("steps", ctx.steps);
        // Nothing is read from the state directory and discovery has not run.
        ctx.namespaces.put("state", Map.of());
        ctx.namespaces.put("env", System.getenv());
        ctx.namespaces.put("secret", secrets);
        ctx.namespaces.put("run", runNamespace(job.name(), event));
        ctx.namespaces.put("butler", butlerNamespace());

        // Job vars may refer to global ones, so they are resolved second.
        ctx.defineVars(config.vars());
        ctx.defineVars(job.vars());
        return ctx;
    }

    private static Map<String, Object> runNamespace(String job, Event event) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", "<run-id>");
        run.put("job", job);
        run.put("trigger", event.trigger());
        run.put("started_at", "<started_at>");
        run.put("dry_run", true);
        run.put("status", "<status>");
        run.put("duration", "<duration>");
        run.put("failed_step", "<failed_step>");
        run.put("error", "<error>");
        return Collections.unmodifiableMap(run);
    }

    private static Map<String, Object> butlerNamespace() {
        Map<String, Object> butler = new LinkedHashMap<>();
        String version = Context.class.getPackage().getImplementationVersion();
        butler.put("version", version == null ? "dev" : version);
        butler.put("host", hostname());
        return Collections.unmodifiableMap(butler);
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    private void defineVars(Map<String, Object> declared) {
        declared.forEach((k, v) -> vars.put(k, resolveDeep(v)));
    }

    // ------------------------------------------------------------------ RunContext

    @Override
    public Map<String, Object> namespace(String name) {
        Object ns = namespaces.get(name);
        return ns instanceof Map<?, ?> m ? asStringKeyed(m) : Map.of();
    }

    @Override
    public String resolve(String template) {
        return Expressions.template(template).render(evaluator);
    }

    @Override
    public Object resolveValue(String template) {
        return Expressions.template(template).renderValue(evaluator);
    }

    @Override
    public boolean evaluate(String condition) {
        return evaluator.evalCondition(Expressions.condition(condition));
    }

    @Override
    public boolean dryRun() {
        return dryRun;
    }

    // ------------------------------------------------------------- for the runtime, not steps

    /**
     * The explained form of a condition: its structure, with operands replaced by their values.
     */
    public String explain(String condition) {
        return evaluator.explain(Expressions.condition(condition));
    }

    /**
     * Exposes a step's result as {@code steps.<name>.*}.
     */
    public void register(String name, StepResult result) {
        steps.put(name, result.asContext());
    }

    /**
     * Merges what a step wrote into {@code vars.*}.
     */
    public void applyVars(Map<String, Object> values) {
        vars.putAll(values);
    }

    /**
     * Resolves every template in a parameter tree, keeping the value's type when a string is
     * exactly one hole so {@code keep: ${vars.keep}} stays a number.
     */
    public Object resolveDeep(Object value) {
        switch (value) {
            case null -> {
                return null;
            }
            case String s -> {
                return s.contains("${") ? resolveValue(s) : s;
            }
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), resolveDeep(v)));
                return Collections.unmodifiableMap(out);
            }
            case List<?> l -> {
                return l.stream().map(this::resolveDeep).toList();
            }
            default -> {
                return value;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyed(Map<?, ?> m) {
        return Collections.unmodifiableMap((Map<String, Object>) m);
    }
}
