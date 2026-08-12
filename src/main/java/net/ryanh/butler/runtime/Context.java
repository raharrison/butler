package net.ryanh.butler.runtime;

import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.expr.Evaluator;
import net.ryanh.butler.expr.Expressions;
import net.ryanh.butler.expr.Scope;
import net.ryanh.butler.spi.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link RunContext} implementation: the eight namespaces of DESIGN.md §2.2, plus the three
 * things a run adds as it goes, being results registered by {@code register:}, variables written by
 * steps, and values observed by {@code discover:}. Everything else is fixed once it is built.
 *
 * <p>A step sees a view of the context rather than this object: one carrying its process settings
 * ({@link #forStep}), and inside a condition possibly one carrying step-injected locals
 * ({@link #withLocals}). Views share the namespaces with the run, so a result registered through
 * one is visible through all of them.
 */
public final class Context implements RunContext {

    private final Map<String, Object> namespaces;
    private final Map<String, Object> vars;
    private final Map<String, Object> steps;
    private final Map<String, Object> state;
    private final Map<String, Object> run;
    private final boolean dryRun;
    private final RunEnvironment env;
    private final ProcessRunner.Command command;
    private final Evaluator evaluator;

    private Context(boolean dryRun, RunEnvironment env) {
        this.namespaces = new LinkedHashMap<>();
        this.vars = new LinkedHashMap<>();
        this.steps = new LinkedHashMap<>();
        this.state = new LinkedHashMap<>();
        this.run = new LinkedHashMap<>();
        this.dryRun = dryRun;
        this.env = env;
        this.command = ProcessRunner.Command.none();
        // Reads the live map, so a result registered mid-run is visible to the next step.
        this.evaluator = new Evaluator(namespaces::get);
    }

    /**
     * A view over the same run: same namespaces, different scope or process settings.
     */
    private Context(Context base, Scope scope, ProcessRunner.Command command) {
        this.namespaces = base.namespaces;
        this.vars = base.vars;
        this.steps = base.steps;
        this.state = base.state;
        this.run = base.run;
        this.dryRun = base.dryRun;
        this.env = base.env;
        this.command = command;
        this.evaluator = new Evaluator(scope);
    }

    /**
     * The context a plan is built against. Values only a real run can know render as placeholders,
     * because a plan has to be deterministic.
     */
    public static Context forPlan(RunEnvironment env, JobDef job, Event event,
                                  Map<String, Object> persisted) {
        Context ctx = base(env, job, event, persisted, true);
        ctx.run.put("id", "<run-id>");
        ctx.run.put("job", job.name());
        ctx.run.put("trigger", event.trigger());
        ctx.run.put("started_at", "<started_at>");
        ctx.run.put("dry_run", true);
        ctx.run.put("status", "<status>");
        ctx.run.put("duration", "<duration>");
        ctx.run.put("failed_step", "<failed_step>");
        ctx.run.put("error", "<error>");
        return ctx;
    }

    /**
     * The context a real run happens in. The outcome half of {@code run.*} is empty until the run
     * has one; {@link #outcome} fills it in before hooks and notifications are rendered.
     */
    public static Context forRun(RunEnvironment env, JobDef job, Event event,
                                 Map<String, Object> persisted, String runId, Instant startedAt) {
        Context ctx = base(env, job, event, persisted, false);
        ctx.run.put("id", runId);
        ctx.run.put("job", job.name());
        ctx.run.put("trigger", event.trigger());
        ctx.run.put("started_at", startedAt.toString());
        ctx.run.put("dry_run", false);
        return ctx;
    }

    private static Context base(RunEnvironment env, JobDef job, Event event,
                                Map<String, Object> persisted, boolean dryRun) {
        Context ctx = new Context(dryRun, env);

        ctx.namespaces.put("vars", ctx.vars);
        ctx.namespaces.put("trigger", event.facts());
        ctx.namespaces.put("steps", ctx.steps);
        ctx.state.putAll(persisted);
        ctx.namespaces.put("state", ctx.state);
        ctx.namespaces.put("env", System.getenv());
        ctx.namespaces.put("secret", env.secrets());
        ctx.namespaces.put("run", ctx.run);
        ctx.namespaces.put("butler", butlerNamespace());

        // Job vars may refer to global ones, so they are resolved second.
        ctx.defineVars(env.config().vars());
        ctx.defineVars(job.vars());
        return ctx;
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
    public String resolveCondition(String condition) {
        return Expressions.template(condition).renderLiterals(evaluator);
    }

    @Override
    public Context withLocals(Map<String, Object> locals) {
        return new Context(this, Scope.of(namespaces).with(locals), command);
    }

    @Override
    public ProcessRunner processes() {
        return env.processes();
    }

    @Override
    public Notifications notifications() {
        return (to, message) ->
                Notifiers.send(env.config().notifiers(), env.notifiers(), to, message, this);
    }

    @Override
    public ProcessRunner.Command command() {
        return command;
    }

    @Override
    public boolean dryRun() {
        return dryRun;
    }

    // ------------------------------------------------------------- for the runtime, not steps

    /**
     * The view a step runs in: the same namespaces, plus the process settings its reserved keys
     * asked for.
     */
    public Context forStep(ProcessRunner.Command stepCommand) {
        return new Context(this, namespaces::get, stepCommand);
    }

    /**
     * Judges a condition and explains it in one pass, so the explanation and the verdict cannot
     * disagree.
     */
    public Evaluator.Decision decide(String condition) {
        return evaluator.decide(Expressions.condition(condition));
    }

    /**
     * Evaluates a bare expression to whatever it yields, which is what {@code extract:} needs:
     * {@code basename(value)} produces a string, not a verdict.
     */
    public Object evaluateValue(String expression) {
        return evaluator.eval(Expressions.condition(expression));
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
     * Overlays a discovered value onto {@code state.*}, where it beats what was persisted: the host
     * has just been asked, and the file is only a cache of the answer (DESIGN.md §6.2).
     *
     * <p>A null is not an observation and is ignored, so a value nobody could read leaves the
     * persisted one standing.
     */
    public void observe(String key, Object value) {
        if (value != null) {
            state.put(key, value);
        }
    }

    /**
     * Everything {@code state.*} currently holds: what was persisted, overlaid with what
     * discovery observed.
     */
    public Map<String, Object> state() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }

    /**
     * Fills in the half of {@code run.*} that only exists once a run has ended, which hooks and
     * {@code notify:} messages read.
     */
    public void outcome(String status, Duration duration, String failedStep, String error) {
        run.put("status", status);
        run.put("duration", duration == null ? null : duration);
        run.put("failed_step", failedStep);
        run.put("error", error);
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
