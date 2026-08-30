package net.ryanh.butler.runtime;

import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.util.Durations;
import net.ryanh.butler.util.Literals;

import java.util.*;

/**
 * Renders a finished {@link Run} the way {@link PlanRenderer} renders a {@link Plan}, so the two
 * can be compared line for line.
 *
 * <p>Carries real durations, so unlike a plan it is not deterministic and is not snapshot-tested;
 * tests assert against {@link Run} itself.
 */
public final class RunRenderer {

    private RunRenderer() {
    }

    public static String render(Run run) {
        List<String> out = new ArrayList<>();

        StringBuilder header = new StringBuilder("RUN  job=").append(run.job())
                .append("  trigger=").append(run.trigger());
        run.facts().forEach((k, v) -> header.append("  ").append(k).append('=').append(v));
        header.append("  id=").append(run.id());
        out.add(header.toString());

        if (!run.discover().isEmpty()) {
            out.add("");
            out.add("  discover");
            PlanRenderer.section(out, run.discover());
        }

        if (run.decision() != null) {
            out.add("");
            Plan.Decision d = run.decision();
            out.add(d.error() != null
                    ? "  when   " + d.source() + "   ->   could not be evaluated: " + d.error()
                    : "  when   " + d.explained() + "   ->   " + d.result());
        }

        List<Run.Step> pipeline = run.steps().stream()
                .filter(s -> s.section().equals("step")).toList();
        if (!pipeline.isEmpty()) {
            out.add("");
            out.add("  steps");
            steps(out, pipeline);
        }

        for (String section : List.of("on_failure", "on_success", "always")) {
            List<Run.Step> hook = run.steps().stream()
                    .filter(s -> s.section().equals(section)).toList();
            if (!hook.isEmpty()) {
                out.add("");
                out.add("  " + section);
                steps(out, hook);
            }
        }

        if (!run.persisted().isEmpty() || run.notification() != null) {
            out.add("");
        }
        persist(out, run.persisted());
        if (run.notification() != null) {
            out.add(PlanRenderer.pad("  notify", 12) + run.notification().channels() + " <- "
                    + Literals.of(run.notification().message()));
        }

        out.add("");
        out.add("  " + run.status().toString().toUpperCase(Locale.ROOT)
                + " in " + Durations.format(run.duration())
                + (run.message() == null ? "" : ": " + run.message()));
        return String.join("\n", out) + "\n";
    }

    private static void steps(List<String> out, List<Run.Step> steps) {
        int width = steps.stream().mapToInt(s -> s.label().length()).max().orElse(0);
        int number = 0;
        for (Run.Step s : steps) {
            boolean ran = s.status() != StepResult.Status.SKIPPED;
            String marker = ran ? String.valueOf(++number) : "-";
            out.add("    " + marker + "  " + PlanRenderer.pad(s.label(), width + 2)
                    + PlanRenderer.pad(s.uses(), 16) + outcome(s));
            output(out, s);
        }
    }

    /**
     * The fields of a step's own outputs worth showing inline. The full text always lands in the
     * run record on disk; this is a terminal-sized preview of it.
     */
    private static final Set<String> OUTPUT_FIELDS = Set.of("stdout", "stderr", "body");
    private static final int MAX_OUTPUT_LINES = 20;

    private static void output(List<String> out, Run.Step s) {
        for (String field : OUTPUT_FIELDS) {
            if (!(s.outputs().get(field) instanceof String text) || text.isBlank()) {
                continue;
            }
            String[] lines = text.strip().split("\n");
            out.add(PlanRenderer.BODY_INDENT + field + ":");
            int shown = Math.min(lines.length, MAX_OUTPUT_LINES);
            for (int i = 0; i < shown; i++) {
                out.add(PlanRenderer.BODY_INDENT + "  " + lines[i]);
            }
            if (shown < lines.length) {
                out.add(PlanRenderer.BODY_INDENT + "  ... " + (lines.length - shown)
                        + " more line(s) - full text is in the run record");
            }
        }
    }

    private static String outcome(Run.Step s) {
        StringBuilder sb = new StringBuilder(s.status().toString());
        if (s.status() != StepResult.Status.SKIPPED) {
            sb.append(" in ").append(Durations.format(s.duration()));
        }
        if (s.attempts() > 1) {
            sb.append(" after ").append(s.attempts()).append(" attempts");
        }
        if (s.message() != null && !s.message().isBlank()) {
            sb.append("  ").append(s.message().strip().split("\n")[0]);
        }
        return sb.toString();
    }

    private static void persist(List<String> out, Map<String, Object> persisted) {
        if (persisted.isEmpty()) {
            return;
        }
        int width = persisted.keySet().stream().mapToInt(String::length).max().orElse(0);
        boolean first = true;
        for (Map.Entry<String, Object> e : persisted.entrySet()) {
            out.add(PlanRenderer.pad(first ? "  persist" : "", 12)
                    + PlanRenderer.pad(e.getKey(), width) + " = " + Literals.of(e.getValue()));
            first = false;
        }
    }
}
