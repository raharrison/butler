package net.ryanh.butler.runtime;

import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.StepDef;
import net.ryanh.butler.expr.ExprException;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.util.Literals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code discover:} phase: observation steps that run before the job's {@code when:} is judged,
 * on every event, and whose {@code extract:} expressions populate {@code state.*} (DESIGN.md §6.2).
 *
 * <p>Two rules here are load-bearing. A failing discovery step is not a failing run: it contributes
 * nothing and the persisted value stands, so a health endpoint that is briefly down cannot read as
 * "nothing is deployed". And discovery executes for real even under {@code --dry-run}, because a
 * dry run that skipped it would report a decision made against memory rather than the host.
 */
final class Discovery {

    private static final Logger log = LoggerFactory.getLogger(Discovery.class);

    private Discovery() {
    }

    /**
     * Runs the block, overlaying what it observed onto {@code ctx}'s {@code state.*}.
     *
     * @return one entry per declared step, in source order, for the report and the run record
     */
    static List<Plan.Entry> run(JobDef job, StepRegistry registry, Context ctx) {
        List<Plan.Entry> entries = new ArrayList<>();
        int number = 0;
        for (StepDef def : job.discover()) {
            Plan.Entry entry = one(def, job, registry, ctx, number + 1);
            if (entry.number() > 0) {
                number++;
            }
            entries.add(entry);
        }
        return List.copyOf(entries);
    }

    private static Plan.Entry one(StepDef def, JobDef job, StepRegistry registry, Context ctx,
                                  int number) {
        StepResolver.Resolved resolved = StepResolver.resolve(def, job, registry, ctx,
                def.timeout());
        switch (resolved) {
            case StepResolver.Skipped skipped -> {
                return Plan.Entry.skipped("discover", def.label(), def.uses(), skipped.reason());
            }
            case StepResolver.Unresolvable bad -> {
                log.warn("discover step {} could not run: {}", def.label(), bad.problem());
                return Plan.Entry.failed("discover", def.label(), def.uses(), bad.problem());
            }
            case StepResolver.Ready ready -> {
                StepResult result = StepExecution.once(ready, def.timeout()).result();
                StepResolver.record(def, result, ctx);

                if (result.isFailed()) {
                    log.warn("discover step {} failed ({}); the persisted state stands",
                            def.label(), result.message());
                    return Plan.Entry.failed("discover", def.label(), def.uses(),
                            "observed nothing: " + result.message()
                                    + " (a discovery failure is not a run failure)");
                }
                return new Plan.Entry("discover", number, def.label(), def.uses(),
                        body(def, result, ctx), List.of(), null, null);
            }
        }
    }

    /**
     * What the step reported, then what it taught {@code state.*}.
     */
    private static List<String> body(StepDef def, StepResult result, Context ctx) {
        List<String> lines = new ArrayList<>();
        if (result.message() != null && !result.message().isBlank()) {
            lines.addAll(List.of(result.message().stripTrailing().split("\n")));
        }
        // An extract expression is scoped to the step's own result fields: json.version for an HTTP
        // probe, value for a symlink read, stdout for a command.
        Context scoped = ctx.withLocals(result.asContext());
        for (Map.Entry<String, String> extract : def.extract().entrySet()) {
            String key = extract.getKey();
            try {
                Object value = scoped.evaluateValue(extract.getValue());
                if (value == null) {
                    // The step answered, but not with this. Observing the null would overwrite what
                    // was persisted and then be written back as the truth, which is the failure
                    // DESIGN.md §6.1 exists to prevent: a health endpoint that changed shape must
                    // not read as "nothing is deployed".
                    log.warn("discover step {} extracted nothing for {}; the persisted value stands",
                            def.label(), key);
                    lines.add("state." + key + " not extracted: the expression produced no value");
                    continue;
                }
                ctx.observe(key, value);
                lines.add("state." + key + " = " + Literals.of(value));
            } catch (ExprException e) {
                log.warn("discover step {} could not extract {}: {}", def.label(), key,
                        e.getMessage());
                lines.add("state." + key + " not extracted: " + e.getMessage());
            }
        }
        return List.copyOf(lines);
    }
}
