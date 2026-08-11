package net.ryanh.butler.config;

import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.StepDef;
import net.ryanh.butler.config.model.TriggerDef;
import net.ryanh.butler.expr.ExprException;
import net.ryanh.butler.expr.Expressions;
import net.ryanh.butler.expr.Node;
import net.ryanh.butler.util.Suggestions;

import java.util.*;

/**
 * Semantic checks over a loaded config: expressions parse, references resolve, names are unique.
 *
 * <p>Structural checks and unknown keys are handled during loading. This pass runs afterwards on
 * whatever model was built, so a config with structural errors still gets its expressions
 * checked and the author sees everything at once.
 */
public final class ConfigValidator {

    /**
     * Context namespaces available to every expression (DESIGN.md §2.2).
     */
    public static final List<String> NAMESPACES =
            List.of("vars", "trigger", "steps", "state", "env", "secret", "run", "butler");

    /**
     * Locals a step may inject into a condition's scope, such as {@code status} and {@code json}
     * for {@code http.wait}'s {@code until:}. Which ones a given step actually provides is known
     * only to the step registry, so this pass accepts the union of them all.
     */
    private static final List<String> STEP_LOCALS =
            List.of("status", "headers", "body", "json", "value", "stdout", "stderr", "exit_code");

    private ConfigValidator() {
    }

    /**
     * @param conditionParams step parameters holding a bare condition rather than a string
     *                        template, such as {@code until:}. Without them such a value is treated
     *                        as a template and one containing no {@code ${}} is never parsed at
     *                        all. The set comes from {@code StepRegistry.conditionParams()}, passed
     *                        in because {@code config} does not depend on the runtime.
     */
    public static void validate(ButlerConfig config, Diagnostics diags,
                                Set<String> conditionParams) {
        if (config == null) {
            return;
        }
        config.vars().forEach((k, v) -> checkTemplate(diags, "/vars/" + k, v, NAMESPACES));
        config.notifiers().forEach((name, n) ->
                n.params().forEach((k, v) ->
                        checkTemplate(diags, n.path() + "/" + k, v, NAMESPACES)));

        config.jobs().values().forEach(job -> job(config, job, diags, conditionParams));
    }

    private static void job(ButlerConfig config, JobDef job, Diagnostics diags,
                            Set<String> conditionParams) {
        job.vars().forEach((k, v) -> checkTemplate(diags, job.path() + "/vars/" + k, v, NAMESPACES));
        job.env().forEach((k, v) -> checkTemplate(diags, job.path() + "/env/" + k, v, NAMESPACES));

        for (TriggerDef t : job.on()) {
            t.params().forEach((k, v) -> checkTemplate(diags, t.path() + "/" + k, v, NAMESPACES));
        }

        checkCondition(diags, job.path() + "/when", job.when(), NAMESPACES);

        checkSteps(job.discover(), diags, true, conditionParams);
        checkSteps(job.steps(), diags, false, conditionParams);
        checkSteps(job.onFailure(), diags, false, conditionParams);
        checkSteps(job.onSuccess(), diags, false, conditionParams);
        checkSteps(job.always(), diags, false, conditionParams);

        job.persist().forEach((k, v) ->
                checkTemplate(diags, job.path() + "/persist/" + k, v, NAMESPACES));

        if (job.notifyPolicy() != null) {
            String to = job.notifyPolicy().to();
            if (to != null && !config.notifiers().containsKey(to)) {
                diags.error(job.path() + "/notify/to",
                        "no notifier named \"" + to + "\""
                                + Suggestions.from(to, config.notifiers().keySet()));
            }
            job.notifyPolicy().messages().forEach((k, v) ->
                    checkTemplate(diags, job.path() + "/notify/" + k, v, NAMESPACES));
        }

        checkRegisterNames(job, diags, conditionParams);
        checkStateWithoutDiscover(job, diags);
    }

    private static void checkSteps(List<StepDef> steps, Diagnostics diags, boolean isDiscover,
                                   Set<String> conditionParams) {
        List<String> allowed = new ArrayList<>(NAMESPACES);
        allowed.addAll(STEP_LOCALS);

        for (StepDef step : steps) {
            // A step's own "when:" decides whether to run it at all, before there is any probe to
            // describe, so the locals a probe injects are not in scope there.
            checkCondition(diags, step.path() + "/when", step.when(), NAMESPACES);
            step.env().forEach((k, v) ->
                    checkTemplate(diags, step.path() + "/env/" + k, v, NAMESPACES));

            step.params().forEach((k, v) -> {
                if (conditionParams.contains(k)) {
                    checkCondition(diags, step.path() + "/" + k, asText(v), allowed);
                } else {
                    checkTemplate(diags, step.path() + "/" + k, v, allowed);
                }
            });

            if (!step.extract().isEmpty() && !isDiscover) {
                diags.error(step.path() + "/extract",
                        "\"extract\" is only valid inside a discover block; "
                                + "elsewhere use \"register\" to expose a step's result");
            }
            step.extract().forEach((k, v) ->
                    checkCondition(diags, step.path() + "/extract/" + k, v, allowed));

            if (step.register() != null && !isIdentifier(step.register())) {
                diags.error(step.path() + "/register",
                        "\"" + step.register() + "\" is not a usable name: "
                                + "use letters, digits and underscores so it can be referenced as steps."
                                + step.register());
            }
        }
    }

    /**
     * Register names are unique across the whole job, and a step may only reference results from
     * steps that have already run. Discovery runs before everything, so what it registers is
     * visible to the pipeline; hooks run after the pipeline and can see all of it.
     */
    private static void checkRegisterNames(JobDef job, Diagnostics diags,
                                           Set<String> conditionParams) {
        Set<String> everyName = new LinkedHashSet<>();
        for (StepDef step : allSteps(job)) {
            if (step.register() != null) {
                everyName.add(step.register());
            }
        }

        Set<String> declared = new LinkedHashSet<>();
        Set<String> afterDiscover =
                checkSection(job.discover(), diags, Set.of(), declared, everyName, conditionParams);
        Set<String> afterSteps =
                checkSection(job.steps(), diags, afterDiscover, declared, everyName, conditionParams);
        checkSection(job.onFailure(), diags, afterSteps, declared, everyName, conditionParams);
        checkSection(job.onSuccess(), diags, afterSteps, declared, everyName, conditionParams);
        checkSection(job.always(), diags, afterSteps, declared, everyName, conditionParams);
    }

    private static List<StepDef> allSteps(JobDef job) {
        List<StepDef> out = new ArrayList<>();
        out.addAll(job.discover());
        out.addAll(job.steps());
        out.addAll(job.onFailure());
        out.addAll(job.onSuccess());
        out.addAll(job.always());
        return out;
    }

    /**
     * @param available names visible to the first step of this section
     * @param declared  accumulates every name seen so far in the job, for uniqueness
     * @param everyName every name registered anywhere in the job, for better messages
     * @return the names visible after this section has run
     */
    private static Set<String> checkSection(List<StepDef> steps, Diagnostics diags,
                                            Set<String> available, Set<String> declared,
                                            Set<String> everyName, Set<String> conditionParams) {
        Set<String> visible = new LinkedHashSet<>(available);

        for (StepDef step : steps) {
            for (String referenced : referencedSteps(step, conditionParams)) {
                if (visible.contains(referenced)) {
                    continue;
                }
                if (everyName.contains(referenced)) {
                    diags.error(step.path(),
                            "references steps." + referenced + ", which is registered later in "
                                    + "this job; a step can only use results from steps that "
                                    + "already ran");
                } else {
                    diags.error(step.path(),
                            "references steps." + referenced + " but no step registers that name"
                                    + Suggestions.from(referenced, everyName));
                }
            }
            if (step.register() != null) {
                if (!declared.add(step.register())) {
                    diags.error(step.path() + "/register",
                            "duplicate register name \"" + step.register() + "\" in this job");
                }
                visible.add(step.register());
            }
        }
        return visible;
    }

    /**
     * The {@code steps.<name>} references a step makes, across all its expressions.
     */
    private static Set<String> referencedSteps(StepDef step, Set<String> conditionParams) {
        Set<String> out = new LinkedHashSet<>();

        collectStepRefs(parseQuietly(step.when()), out);
        step.extract().values().forEach(v -> collectStepRefs(parseQuietly(v), out));

        step.params().forEach((k, v) -> {
            if (conditionParams.contains(k)) {
                collectStepRefs(parseQuietly(asText(v)), out);
            } else {
                collectStepRefsFromValue(v, out);
            }
        });
        step.env().values().forEach(v -> collectStepRefsFromValue(v, out));
        return out;
    }

    private static void collectStepRefsFromValue(Object value, Set<String> out) {
        switch (value) {
            case null -> {
            }
            case Map<?, ?> m -> m.values().forEach(v -> collectStepRefsFromValue(v, out));
            case List<?> l -> l.forEach(v -> collectStepRefsFromValue(v, out));
            default -> {
                try {
                    for (Node hole : Expressions.template(String.valueOf(value)).holes()) {
                        collectStepRefs(hole, out);
                    }
                } catch (ExprException ignored) {
                    // Reported separately by the template check.
                }
            }
        }
    }

    private static void collectStepRefs(Node node, Set<String> out) {
        if (node == null) {
            return;
        }
        for (Node.Var v : Expressions.variables(node)) {
            if (v.root().equals("steps") && v.path().size() >= 2 && !v.path().get(1).isIndex()) {
                out.add(v.path().get(1).name());
            }
        }
    }

    /**
     * Parses a condition, yielding null when it is absent or malformed.
     */
    private static Node parseQuietly(String condition) {
        if (condition == null) {
            return null;
        }
        try {
            return Expressions.condition(condition);
        } catch (ExprException e) {
            return null;
        }
    }

    /**
     * Warns when a job decides using {@code state.*} but never observes the host. That
     * combination is almost always the first-run bug: with an empty state directory the
     * condition passes and the job deploys over a host that is already correct.
     */
    private static void checkStateWithoutDiscover(JobDef job, Diagnostics diags) {
        if (!job.discover().isEmpty() || job.when() == null) {
            return;
        }
        Node node = parseQuietly(job.when());
        if (node == null) {
            return;
        }
        boolean usesState = Expressions.variables(node).stream()
                .anyMatch(v -> v.root().equals("state"));
        if (usesState) {
            diags.warn(job.path() + "/when",
                    "decides using state.* but the job has no discover block, so on a fresh "
                            + "install or after the state directory is lost it will act as if "
                            + "nothing is deployed");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void checkCondition(Diagnostics diags, String path, String source,
                                       List<String> allowedRoots) {
        if (source == null) {
            return;
        }
        try {
            checkRoots(diags, path, Expressions.condition(source), allowedRoots);
        } catch (ExprException e) {
            diags.error(path, "invalid condition: " + e.getMessage());
        }
    }

    private static void checkTemplate(Diagnostics diags, String path, Object value,
                                      List<String> allowedRoots) {
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> checkTemplate(diags, path + "/" + k, v, allowedRoots));
            return;
        }
        if (value instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                checkTemplate(diags, path + "/" + i, l.get(i), allowedRoots);
            }
            return;
        }
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (!text.contains("${")) {
            return;
        }
        try {
            for (Node hole : Expressions.template(text).holes()) {
                checkRoots(diags, path, hole, allowedRoots);
            }
        } catch (ExprException e) {
            diags.error(path, "invalid expression: " + e.getMessage());
        }
    }

    /**
     * An unknown namespace is an error, while an unknown path within a known namespace is not:
     * {@code default(state.deployed_version, "0.0.0")} must keep working on a first run, but
     * {@code ${triger.version}} should be caught before the daemon ever starts.
     */
    private static void checkRoots(Diagnostics diags, String path, Node node,
                                   List<String> allowedRoots) {
        for (Node.Var v : Expressions.variables(node)) {
            String root = v.root();
            if (!allowedRoots.contains(root)) {
                diags.error(path, "unknown namespace \"" + root + "\" in \"" + v.render() + "\""
                        + Suggestions.from(root, allowedRoots));
            }
        }
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || !Character.isLetter(s.charAt(0)) && s.charAt(0) != '_') {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }
}
