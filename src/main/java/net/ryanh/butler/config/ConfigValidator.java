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
     * The fields on every step's result, whatever its type. An {@code extract:} expression sees
     * these beside the step's own outputs.
     */
    private static final List<String> RESULT_FIELDS =
            List.of("status", "ok", "failed", "skipped", "duration", "attempts", "message");

    private ConfigValidator() {
    }

    /**
     * @param vocabulary what each {@code uses:} reads as a condition and what it injects into its
     *                   own expressions, supplied by the caller because {@code config} does not
     *                   depend on the runtime
     */
    public static void validate(ButlerConfig config, Diagnostics diags, Vocabulary vocabulary) {
        if (config == null) {
            return;
        }
        config.vars().forEach((k, v) -> checkTemplate(diags, "/vars/" + k, v, NAMESPACES));
        config.notifiers().forEach((name, n) ->
                n.params().forEach((k, v) ->
                        checkTemplate(diags, n.path() + "/" + k, v, NAMESPACES)));

        config.jobs().values().forEach(job -> job(config, job, diags, vocabulary));
    }

    private static void job(ButlerConfig config, JobDef job, Diagnostics diags,
                            Vocabulary vocabulary) {
        job.vars().forEach((k, v) -> checkTemplate(diags, job.path() + "/vars/" + k, v, NAMESPACES));
        job.env().forEach((k, v) -> checkTemplate(diags, job.path() + "/env/" + k, v, NAMESPACES));

        for (TriggerDef t : job.on()) {
            Vocabulary.Facts trigger = vocabulary.trigger(t.uses());
            for (Map.Entry<String, Object> param : t.params().entrySet()) {
                String path = t.path() + "/" + param.getKey();
                if (trigger != null && trigger.conditions().contains(param.getKey())) {
                    // Syntax only: its roots are whatever the trigger's own regex captured.
                    checkCondition(diags, path, asText(param.getValue()), null);
                } else {
                    checkNotTemplated(diags, path, param.getValue());
                }
            }
        }

        checkCondition(diags, job.path() + "/when", job.when(), NAMESPACES);

        checkSteps(job.discover(), diags, true, vocabulary);
        checkSteps(job.steps(), diags, false, vocabulary);
        checkSteps(job.onFailure(), diags, false, vocabulary);
        checkSteps(job.onSuccess(), diags, false, vocabulary);
        checkSteps(job.always(), diags, false, vocabulary);

        job.persist().forEach((k, v) ->
                checkTemplate(diags, job.path() + "/persist/" + k, v, NAMESPACES));

        if (job.notifyPolicy() != null) {
            // A null "to" was already reported as a missing required key by the loader.
            String to = job.notifyPolicy().to();
            if (to != null && !config.notifiers().containsKey(to)) {
                diags.error(job.path() + "/notify/to",
                        "no notifier named \"" + to + "\""
                                + Suggestions.from(to, config.notifiers().keySet()));
            }
            job.notifyPolicy().messages().forEach((k, v) ->
                    checkTemplate(diags, job.path() + "/notify/" + k, v, NAMESPACES));
        }

        checkRegisterNames(job, diags, vocabulary);
        checkStateWithoutDiscover(job, diags);
    }

    /**
     * Each kind of expression is judged against what can reach it: a step's {@code when:} against
     * the namespaces alone, a condition parameter against those plus the step's locals, and
     * {@code extract:} against those plus the fields every result carries.
     *
     * <p>An unrecognised {@code uses:} leaves the roots unjudged, since the unknown type is
     * already reported and a second message about {@code ${status}} would be the same mistake
     * twice.
     */
    private static void checkSteps(List<StepDef> steps, Diagnostics diags, boolean isDiscover,
                                   Vocabulary vocabulary) {
        for (StepDef step : steps) {
            Vocabulary.Facts facts = vocabulary.step(step.uses());
            List<String> inConditions = facts == null ? null : roots(facts.locals());
            List<String> inExtract = facts == null ? null
                    : roots(facts.locals(), RESULT_FIELDS);

            // A step's own "when:" decides whether to run it at all, before there is any probe to
            // describe, so the locals a probe injects are not in scope there.
            checkCondition(diags, step.path() + "/when", step.when(), NAMESPACES);
            step.env().forEach((k, v) ->
                    checkTemplate(diags, step.path() + "/env/" + k, v, NAMESPACES));

            step.params().forEach((k, v) -> {
                if (facts != null && facts.conditions().contains(k)) {
                    checkCondition(diags, step.path() + "/" + k, asText(v), inConditions);
                } else {
                    checkTemplate(diags, step.path() + "/" + k, v,
                            facts == null ? null : NAMESPACES);
                }
            });

            if (!step.extract().isEmpty() && !isDiscover) {
                diags.error(step.path() + "/extract",
                        "\"extract\" is only valid inside a discover block; "
                                + "elsewhere use \"register\" to expose a step's result");
            }
            step.extract().forEach((k, v) ->
                    checkCondition(diags, step.path() + "/extract/" + k, v, inExtract));

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
     *
     * <p>{@code persist:} and the {@code notify:} messages are rendered after the hooks, and which
     * hooks ran depends on how the run ended, so each is judged against its own outcome's path
     * (DESIGN.md §2.1).
     */
    private static void checkRegisterNames(JobDef job, Diagnostics diags, Vocabulary vocabulary) {
        Map<String, String> sections = registeringSections(job);
        Set<String> everyName = sections.keySet();

        Set<String> declared = new LinkedHashSet<>();
        Set<String> afterDiscover =
                checkSection(job.discover(), diags, Set.of(), declared, everyName, vocabulary);
        Set<String> afterSteps =
                checkSection(job.steps(), diags, afterDiscover, declared, everyName, vocabulary);
        Set<String> afterFailure =
                checkSection(job.onFailure(), diags, afterSteps, declared, everyName, vocabulary);
        Set<String> afterSuccess =
                checkSection(job.onSuccess(), diags, afterSteps, declared, everyName, vocabulary);
        Set<String> afterAlways =
                checkSection(job.always(), diags, afterSteps, declared, everyName, vocabulary);

        Set<String> onSuccess = union(afterSuccess, afterAlways);
        Set<String> onFailure = union(afterFailure, afterAlways);

        // persist: is written only by a run that succeeded.
        job.persist().forEach((k, v) -> checkRendered(diags, job.path() + "/persist/" + k, v,
                onSuccess, sections, "succeeds"));

        if (job.notifyPolicy() != null) {
            job.notifyPolicy().messages().forEach((outcome, message) -> {
                boolean success = outcome.equals("success");
                checkRendered(diags, job.path() + "/notify/" + outcome, message,
                        success ? onSuccess : onFailure, sections,
                        success ? "succeeds" : "fails");
            });
        }
    }

    /**
     * Which section registers each name, so a message can say why one is out of scope rather than
     * only that it is.
     */
    private static Map<String, String> registeringSections(JobDef job) {
        Map<String, String> out = new LinkedHashMap<>();
        noteRegisters(out, "discover", job.discover());
        noteRegisters(out, "steps", job.steps());
        noteRegisters(out, "on_failure", job.onFailure());
        noteRegisters(out, "on_success", job.onSuccess());
        noteRegisters(out, "always", job.always());
        return out;
    }

    private static void noteRegisters(Map<String, String> out, String section,
                                      List<StepDef> steps) {
        for (StepDef step : steps) {
            if (step.register() != null) {
                // The first wins; a name registered twice is reported by checkSection.
                out.putIfAbsent(step.register(), section);
            }
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * A template rendered once the run is over. Unlike a step, it cannot be reached by every path
     * through the job, so a name a hook the other outcome runs is a reference that would silently
     * render as nothing.
     *
     * @param outcome how the run ended for this template to be rendered at all
     */
    private static void checkRendered(Diagnostics diags, String path, String template,
                                      Set<String> visible, Map<String, String> sections,
                                      String outcome) {
        // A template that does not parse is already reported at this path, and produced no
        // references to judge.
        if (template == null || diags.hasErrorAt(path)) {
            return;
        }
        Set<String> referenced = new LinkedHashSet<>();
        collectStepRefsFromValue(template, referenced);

        for (String name : referenced) {
            if (visible.contains(name)) {
                continue;
            }
            diags.error(path, sections.containsKey(name)
                    ? "references steps." + name + ", registered in " + sections.get(name)
                      + ":, which does not run when the job " + outcome
                    : "references steps." + name + " but no step registers that name"
                      + Suggestions.from(name, sections.keySet()));
        }
    }

    /**
     * @param available names visible to the first step of this section
     * @param declared  accumulates every name seen so far in the job, for uniqueness
     * @param everyName every name registered anywhere in the job, for better messages
     * @return the names visible after this section has run
     */
    private static Set<String> checkSection(List<StepDef> steps, Diagnostics diags,
                                            Set<String> available, Set<String> declared,
                                            Set<String> everyName, Vocabulary vocabulary) {
        Set<String> visible = new LinkedHashSet<>(available);

        for (StepDef step : steps) {
            for (String referenced : referencedSteps(step, vocabulary)) {
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
    private static Set<String> referencedSteps(StepDef step, Vocabulary vocabulary) {
        Set<String> out = new LinkedHashSet<>();
        Vocabulary.Facts facts = vocabulary.step(step.uses());
        List<String> conditionParams = facts == null ? List.of() : facts.conditions();

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

    @SafeVarargs
    private static List<String> roots(List<String>... extra) {
        List<String> out = new ArrayList<>(NAMESPACES);
        for (List<String> more : extra) {
            out.addAll(more);
        }
        return List.copyOf(out);
    }

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

    /**
     * A watcher is started before any event exists, so there is no run to resolve a trigger's
     * parameters against and a {@code ${...}} in one would be watched for literally.
     */
    private static void checkNotTemplated(Diagnostics diags, String path, Object value) {
        switch (value) {
            case null -> {
            }
            case Map<?, ?> m -> m.forEach((k, v) -> checkNotTemplated(diags, path + "/" + k, v));
            case List<?> l -> {
                for (int i = 0; i < l.size(); i++) {
                    checkNotTemplated(diags, path + "/" + i, l.get(i));
                }
            }
            default -> {
                if (String.valueOf(value).contains("${")) {
                    diags.error(path, "a trigger's parameters are read once when the daemon "
                            + "starts, before there is any run to resolve against, so \"${...}\" "
                            + "here would be taken literally");
                }
            }
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
     *
     * @param allowedRoots what the expression may reference, or null when the step type is unknown
     *                     and there is no way to tell a local from a typo
     */
    private static void checkRoots(Diagnostics diags, String path, Node node,
                                   List<String> allowedRoots) {
        if (allowedRoots == null) {
            return;
        }
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
