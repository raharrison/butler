package net.ryanh.butler.expr;

import net.ryanh.butler.util.Literals;

import java.util.List;

/**
 * A string with {@code ${expr}} holes, compiled once and rendered per run.
 *
 * @param parts alternating literal text and expression holes, in source order
 */
public record Template(List<Part> parts) {

    public sealed interface Part {
        record Text(String value) implements Part {
        }

        record Hole(Node node, String source) implements Part {
        }
    }

    public String render(Evaluator evaluator) {
        if (parts.size() == 1 && parts.getFirst() instanceof Part.Text t) {
            return t.value();
        }
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            switch (p) {
                case Part.Text t -> sb.append(t.value());
                case Part.Hole h -> {
                    Object v = evaluator.eval(h.node());
                    sb.append(v == null ? "" : Functions.str(v));
                }
            }
        }
        return sb.toString();
    }

    /**
     * Renders as a single value rather than a string when the template is exactly one hole, so
     * {@code keep: ${vars.keep}} stays a number instead of becoming "5".
     */
    public Object renderValue(Evaluator evaluator) {
        if (parts.size() == 1 && parts.getFirst() instanceof Part.Hole h) {
            return evaluator.eval(h.node());
        }
        return render(evaluator);
    }

    /**
     * Renders each hole as the literal the author would have written, keeping the text around it,
     * so {@code json.version == ${v}} becomes {@code json.version == "1.2.4"} and stays an
     * expression rather than becoming text no parser accepts.
     */
    public String renderLiterals(Evaluator evaluator) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            switch (p) {
                case Part.Text t -> sb.append(t.value());
                case Part.Hole h -> sb.append(Literals.of(evaluator.eval(h.node())));
            }
        }
        return sb.toString();
    }

    public List<Node> holes() {
        return parts.stream()
                .filter(p -> p instanceof Part.Hole)
                .map(p -> ((Part.Hole) p).node())
                .toList();
    }
}
