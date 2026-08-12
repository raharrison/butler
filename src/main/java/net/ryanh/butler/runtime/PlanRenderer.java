package net.ryanh.butler.runtime;

import net.ryanh.butler.util.Literals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link Plan} as the dry-run report of DESIGN.md §5.5.
 *
 * <p>The output is deterministic: nothing that varies between runs of the same config reaches it,
 * which is what makes the rendered plan a golden file and a diff in it the clearest review
 * artefact for a config change. It is also plain ASCII, because a plan is read through pipes,
 * redirects and CI logs as often as on a terminal.
 */
public final class PlanRenderer {

    /**
     * Section labels pad to this, so the note beside each one starts in the same column.
     */
    private static final int NOTE_COLUMN = 54;

    /**
     * "  persist   (not written)   " and "  notify    (not sent)      " are both this wide.
     */
    private static final int PREVIEW_INDENT = 28;

    /**
     * "step 3" and "discover 1" pad to this, so the warnings beside them line up.
     */
    private static final int WARNING_LABEL = 12;

    /**
     * Indent for the lines a step contributes below its own heading.
     */
    private static final String BODY_INDENT = "         ";

    private PlanRenderer() {
    }

    public static String render(Plan plan) {
        List<String> out = new ArrayList<>();

        StringBuilder header = new StringBuilder("DRY RUN  job=").append(plan.job())
                .append("  trigger=").append(plan.trigger());
        plan.facts().forEach((k, v) -> header.append("  ").append(k).append('=').append(v));
        out.add(header.toString());

        if (!plan.discover().isEmpty()) {
            out.add("");
            // Labelled because this is the one place a dry run touches the host.
            out.add("  discover  (executed for real)");
            section(out, plan.discover());
        }

        if (plan.when() != null) {
            out.add("");
            Plan.Decision d = plan.when();
            out.add(d.error() != null
                    ? "  when   " + d.source() + "   ->   could not be evaluated: " + d.error()
                    : "  when   " + d.explained() + "   ->   " + d.result());
        }

        out.add("");
        if (!plan.wouldRun()) {
            // A condition nobody could evaluate is not one that came out false: the run it
            // describes would end FAILED.
            out.add(note("steps", plan.when().error() != null
                    ? "not shown: the job's when could not be evaluated"
                    : "not run: the job's when is false"));
            // Discovery still ran, and what its preflight found is still true of this host.
            warnings(out, plan);
            return String.join("\n", out) + "\n";
        }
        out.add("  steps");
        section(out, plan.steps());

        if (!plan.hooks().isEmpty() || !plan.persist().isEmpty() || plan.notification() != null) {
            out.add("");
        }
        for (Plan.Hook hook : plan.hooks()) {
            out.add(note(hook.name(), hook.note()));
        }
        persist(out, plan.persist());
        if (plan.notification() != null) {
            out.add(pad("  notify    (not sent)", PREVIEW_INDENT)
                    + plan.notification().to() + " <- "
                    + Literals.of(plan.notification().message()));
        }

        warnings(out, plan);
        return String.join("\n", out) + "\n";
    }

    static void section(List<String> out, List<Plan.Entry> entries) {
        int width = 0;
        for (Plan.Entry e : entries) {
            width = Math.max(width, e.label().length());
        }
        for (Plan.Entry e : entries) {
            out.add("    " + marker(e) + "  " + pad(e.label(), width + 2) + e.uses());
            if (e.skipped() != null) {
                out.add(BODY_INDENT + e.skipped());
            }
            if (e.error() != null) {
                out.add(BODY_INDENT + "error: " + e.error());
            }
            for (String line : e.body()) {
                out.add(BODY_INDENT + line);
            }
        }
    }

    private static String marker(Plan.Entry e) {
        if (e.error() != null) {
            return "!";
        }
        return e.number() > 0 ? String.valueOf(e.number()) : "-";
    }

    private static void persist(List<String> out, Map<String, Object> persist) {
        if (persist.isEmpty()) {
            return;
        }
        int width = persist.keySet().stream().mapToInt(String::length).max().orElse(0);
        boolean first = true;
        for (Map.Entry<String, Object> e : persist.entrySet()) {
            String prefix = first
                    ? pad("  persist   (not written)", PREVIEW_INDENT)
                    : " ".repeat(PREVIEW_INDENT);
            out.add(prefix + pad(e.getKey(), width) + " = " + Literals.of(e.getValue()));
            first = false;
        }
    }

    /**
     * Preflight findings, gathered from every section so they read as one list.
     */
    private static void warnings(List<String> out, Plan plan) {
        List<String> lines = new ArrayList<>();
        collectWarnings(lines, plan.discover());
        collectWarnings(lines, plan.steps());
        if (lines.isEmpty()) {
            return;
        }
        out.add("");
        out.add("  " + lines.size() + (lines.size() == 1 ? " warning" : " warnings"));
        out.addAll(lines);
    }

    private static void collectWarnings(List<String> lines, List<Plan.Entry> entries) {
        for (Plan.Entry e : entries) {
            for (String warning : e.warnings()) {
                lines.add("    " + pad(e.section() + " " + e.number(), WARNING_LABEL) + warning);
            }
        }
    }

    private static String note(String label, String text) {
        return pad("  " + label, NOTE_COLUMN) + text;
    }

    /**
     * Pads to exactly {@code width}; call sites add whatever separator they want after it.
     */
    static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
}
