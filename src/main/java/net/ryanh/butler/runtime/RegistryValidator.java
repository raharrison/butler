package net.ryanh.butler.runtime;

import net.ryanh.butler.config.Diagnostics;
import net.ryanh.butler.config.model.*;
import net.ryanh.butler.util.Suggestions;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The half of validation that needs the registries: that every {@code uses:} names something the
 * daemon can actually run, and that the parameters beside it are ones that step or trigger knows.
 *
 * <p>Structure and expressions are checked without any of this, which is why the two passes are
 * separate. Together they are the whole of {@code butler validate}.
 */
public final class RegistryValidator {

    private RegistryValidator() {
    }

    public static void validate(ButlerConfig config, StepRegistry steps, TriggerRegistry triggers,
                                NotifierRegistry notifiers, Diagnostics diags) {
        if (config == null) {
            return;
        }
        for (NotifierDef notifier : config.notifiers().values()) {
            notifier(notifier, notifiers, diags);
        }
        for (JobDef job : config.jobs().values()) {
            for (TriggerDef trigger : job.on()) {
                trigger(trigger, triggers, diags);
            }
            for (StepDef step : allSteps(job)) {
                step(step, steps, diags);
            }
        }
    }

    private static List<StepDef> allSteps(JobDef job) {
        return Stream.of(job.discover(), job.steps(), job.onFailure(),
                        job.onSuccess(), job.always())
                .flatMap(List::stream)
                .toList();
    }

    private static void trigger(TriggerDef def, TriggerRegistry registry, Diagnostics diags) {
        if (def.uses() == null) {
            return;
        }
        var type = registry.find(def.uses());
        if (type == null) {
            diags.error(def.path() + "/uses", unknown("trigger", def.uses(), registry.names()));
            return;
        }
        parameters(def.path(), def.uses(), "trigger", def.params(), type.configType(), diags);
    }

    private static void notifier(NotifierDef def, NotifierRegistry registry, Diagnostics diags) {
        if (def.uses() == null) {
            return;
        }
        var type = registry.find(def.uses());
        if (type == null) {
            diags.error(def.path() + "/uses", unknown("notifier", def.uses(), registry.names()));
            return;
        }
        parameters(def.path(), def.uses(), "notifier", def.params(), type.configType(), diags);
    }

    private static void step(StepDef def, StepRegistry registry, Diagnostics diags) {
        if (def.uses() == null) {
            return;
        }
        var type = registry.find(def.uses());
        if (type == null) {
            diags.error(def.path() + "/uses", unknown("step", def.uses(), registry.names()));
            return;
        }
        parameters(def.path(), def.uses(), "step", def.params(), type.configType(), diags);
    }

    private static String unknown(String kind, String uses, Set<String> known) {
        String suggestion = Suggestions.from(uses, known);
        return "unknown " + kind + " type \"" + uses + "\""
                + (suggestion.isEmpty() ? " (registered: " + String.join(", ", known) + ")"
                : suggestion);
    }

    /**
     * Unknown parameter names are always caught. Types are only checked when nothing is templated:
     * a value like {@code ${vars.keep}} has no type until the run supplies one, which is where
     * binding happens for real (see {@link PlanBuilder}).
     */
    private static void parameters(String path, String uses, String kind,
                                   Map<String, Object> params, Class<?> configType,
                                   Diagnostics diags) {
        List<String> known = Params.names(configType);
        boolean unknownKeys = false;
        for (String key : params.keySet()) {
            if (!known.contains(key)) {
                diags.error(path + "/" + key, "unknown parameter \"" + key + "\" for " + kind
                        + " type \"" + uses + "\"" + Suggestions.from(key, known));
                unknownKeys = true;
            }
        }
        if (unknownKeys || Params.containsTemplate(params)) {
            return;
        }
        try {
            Params.bind(configType, params);
        } catch (Params.BindingException e) {
            diags.error(path, "cannot use these parameters with " + uses + ": " + e.getMessage());
        }
    }
}
