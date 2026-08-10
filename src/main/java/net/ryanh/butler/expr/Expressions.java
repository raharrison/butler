package net.ryanh.butler.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * The entry point to the expression language. Two evaluation contexts, per DESIGN.md §4:
 *
 * <ul>
 *   <li><b>Condition</b> ({@code when:}, {@code until:}, {@code assert}) - a bare expression.
 *       {@code ${x}} is tolerated as a synonym for {@code x} because it reads better inline, and
 *       is rewritten to {@code (x)} before parsing.</li>
 *   <li><b>String</b> (every other value) - literal text with {@code ${expr}} holes, where
 *       {@code $${} escapes a literal dollar-brace.</li>
 * </ul>
 */
public final class Expressions {

    private Expressions() {
    }

    /**
     * Parses a bare condition, tolerating {@code ${...}} around sub-expressions.
     */
    public static Node condition(String source) {
        if (source == null) {
            throw new ExprException("empty condition");
        }
        String rewritten = rewriteHolesToParens(source);
        if (rewritten.isBlank()) {
            throw new ExprException("empty condition");
        }
        return Parser.parse(rewritten);
    }

    /**
     * Parses a string with {@code ${expr}} holes.
     */
    public static Template template(String source) {
        if (source == null) {
            return new Template(List.of(new Template.Part.Text("")));
        }
        List<Template.Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c != '$') {
                literal.append(c);
                i++;
                continue;
            }
            if (startsEscape(source, i)) {
                literal.append("${");
                i += 3;
                continue;
            }
            if (startsHole(source, i)) {
                int close = findClosingBrace(source, i + 2);
                String inner = source.substring(i + 2, close);
                if (!literal.isEmpty()) {
                    parts.add(new Template.Part.Text(literal.toString()));
                    literal.setLength(0);
                }
                parts.add(new Template.Part.Hole(Parser.parse(inner), inner));
                i = close + 1;
                continue;
            }
            literal.append(c);
            i++;
        }
        if (!literal.isEmpty() || parts.isEmpty()) {
            parts.add(new Template.Part.Text(literal.toString()));
        }
        return new Template(List.copyOf(parts));
    }

    /**
     * Rewrites {@code a == ${b.c}} to {@code a == (b.c)} so conditions can carry holes.
     */
    private static String rewriteHolesToParens(String source) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '$' && startsEscape(source, i)) {
                out.append("${");
                i += 3;
            } else if (c == '$' && startsHole(source, i)) {
                int close = findClosingBrace(source, i + 2);
                out.append('(').append(source, i + 2, close).append(')');
                i = close + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean startsEscape(String s, int i) {
        return i + 2 < s.length() && s.charAt(i + 1) == '$' && s.charAt(i + 2) == '{';
    }

    private static boolean startsHole(String s, int i) {
        return i + 1 < s.length() && s.charAt(i + 1) == '{';
    }

    /**
     * Finds the brace closing a hole opened at {@code from}, counting nesting and skipping over
     * quoted strings so a {@code }} inside a literal does not end the hole early.
     */
    private static int findClosingBrace(String s, int from) {
        int depth = 1;
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipString(s, i);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        throw new ExprException("unclosed \"${\" in \"" + s + "\"");
    }

    private static int skipString(String s, int start) {
        char quote = s.charAt(start);
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        throw new ExprException("unterminated string inside \"${...}\"");
    }

    /**
     * Every variable reference in a tree, for validation.
     */
    public static List<Node.Var> variables(Node node) {
        List<Node.Var> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(Node node, List<Node.Var> out) {
        switch (node) {
            case Node.Var v -> out.add(v);
            case Node.Lit ignored -> {
            }
            case Node.Not n -> collect(n.operand(), out);
            case Node.Bin b -> {
                collect(b.left(), out);
                collect(b.right(), out);
            }
            case Node.Call c -> {
                for (Node a : c.args()) {
                    collect(a, out);
                }
            }
        }
    }
}
