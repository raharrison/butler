package net.ryanh.butler.expr;

import net.ryanh.butler.util.Literals;

import java.time.Duration;
import java.util.*;

/**
 * Evaluates a parsed expression against a {@link Scope}.
 */
public final class Evaluator {

    private final Scope scope;

    /**
     * Set only while {@link #decide} is running, so the explanation and the verdict see the same
     * values. Null the rest of the time, when every node is evaluated on demand.
     */
    private Map<Node, Object> memo;

    public Evaluator(Scope scope) {
        this.scope = scope;
    }

    public Object eval(Node node) {
        if (memo != null && memo.containsKey(node)) {
            return memo.get(node);
        }
        Object value = evalUncached(node);
        if (memo != null) {
            memo.put(node, value);
        }
        return value;
    }

    private Object evalUncached(Node node) {
        // No default branch: Node is sealed, so adding a node type breaks this switch on purpose.
        return switch (node) {
            case Node.Lit lit -> lit.value();
            case Node.Var var -> resolve(var);
            case Node.Not not -> !truthy(eval(not.operand()));
            case Node.Call call -> {
                List<Object> args = new ArrayList<>(call.args().size());
                for (Node a : call.args()) {
                    args.add(eval(a));
                }
                yield Functions.call(call.name(), args);
            }
            case Node.Bin bin -> binary(bin);
        };
    }

    public boolean evalCondition(Node node) {
        return truthy(eval(node));
    }

    /**
     * A condition's verdict together with the explained form behind it.
     */
    public record Decision(String explained, boolean result) {
    }

    /**
     * Judges a condition and explains it in one pass, so the two cannot disagree. Evaluating twice
     * would let a report show values the verdict was not made from, since {@code now()} is not
     * pure.
     */
    public Decision decide(Node node) {
        memo = new IdentityHashMap<>();
        try {
            String explained = explain(node);
            return new Decision(explained, truthy(eval(node)));
        } finally {
            memo = null;
        }
    }

    /**
     * The expression with its operands replaced by the values they evaluate to, so a dry run can
     * show {@code semver("1.2.4") > semver("1.2.3")} rather than repeating the config back.
     *
     * <p>Comparison and boolean structure is kept; everything below it is evaluated, which is why
     * a call keeps its name but shows resolved arguments. An operand that cannot be evaluated
     * falls back to how it was written.
     */
    public String explain(Node node) {
        return switch (node) {
            case Node.Lit lit -> Literals.of(lit.value());
            case Node.Var var -> operand(var);
            case Node.Call call -> {
                List<String> args = new ArrayList<>(call.args().size());
                for (Node a : call.args()) {
                    args.add(operand(a));
                }
                yield call.name() + "(" + String.join(", ", args) + ")";
            }
            case Node.Not not -> "not " + parenthesize(not.operand(), PREC_NOT, false);
            case Node.Bin bin -> parenthesize(bin.left(), precedence(bin.op()), false)
                    + " " + bin.op().symbol() + " "
                    + parenthesize(bin.right(), precedence(bin.op()), true);
        };
    }

    private String operand(Node node) {
        try {
            return Literals.of(eval(node));
        } catch (ExprException e) {
            return explain(node);
        }
    }

    private String parenthesize(Node child, int outer, boolean onTheRight) {
        int inner = precedence(child);
        boolean needed = onTheRight ? inner <= outer : inner < outer;
        String rendered = explain(child);
        return needed ? "(" + rendered + ")" : rendered;
    }

    private static final int PREC_OR = 0;
    private static final int PREC_AND = 1;
    private static final int PREC_CMP = 2;
    private static final int PREC_NOT = 3;
    private static final int PREC_ATOM = 4;

    private static int precedence(Node node) {
        return switch (node) {
            case Node.Bin bin -> precedence(bin.op());
            case Node.Not ignored -> PREC_NOT;
            case Node.Lit ignored -> PREC_ATOM;
            case Node.Var ignored -> PREC_ATOM;
            case Node.Call ignored -> PREC_ATOM;
        };
    }

    private static int precedence(Node.Op op) {
        return switch (op) {
            case OR -> PREC_OR;
            case AND -> PREC_AND;
            case EQ, NE, LT, LE, GT, GE, MATCHES, CONTAINS -> PREC_CMP;
        };
    }

    private Object binary(Node.Bin bin) {
        // Short-circuit before evaluating the right side.
        if (bin.op() == Node.Op.AND) {
            return truthy(eval(bin.left())) && truthy(eval(bin.right()));
        }
        if (bin.op() == Node.Op.OR) {
            return truthy(eval(bin.left())) || truthy(eval(bin.right()));
        }

        Object l = eval(bin.left());
        Object r = eval(bin.right());

        return switch (bin.op()) {
            case EQ -> equal(l, r);
            case NE -> !equal(l, r);
            case LT -> compare(l, r, bin.op()) < 0;
            case LE -> compare(l, r, bin.op()) <= 0;
            case GT -> compare(l, r, bin.op()) > 0;
            case GE -> compare(l, r, bin.op()) >= 0;
            case MATCHES -> l != null && r != null
                    && Functions.compile(Functions.str(r)).matcher(Functions.str(l)).find();
            case CONTAINS -> contains(l, r);
            case AND, OR -> throw new IllegalStateException("handled above");
        };
    }

    /**
     * Walks a path. An unknown segment yields null rather than throwing, so
     * {@code default(state.deployed_version, "0.0.0")} works on a first run. Unknown
     * <em>namespaces</em> are caught earlier, at config validation.
     */
    private Object resolve(Node.Var var) {
        List<Node.Seg> path = var.path();
        Object current = scope.root(path.getFirst().name());
        for (int i = 1; i < path.size() && current != null; i++) {
            Node.Seg seg = path.get(i);
            if (seg.isIndex()) {
                if (current instanceof List<?> list) {
                    int idx = seg.index();
                    current = idx >= 0 && idx < list.size() ? list.get(idx) : null;
                } else {
                    current = null;
                }
            } else if (current instanceof Map<?, ?> map) {
                current = map.get(seg.name());
            } else {
                current = null;
            }
        }
        return current;
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    private static boolean equal(Object l, Object r) {
        if (l == null || r == null) return l == r;
        if (l instanceof Number a && r instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        if (l.getClass() == r.getClass()) return Objects.equals(l, r);
        if (l instanceof Semver || r instanceof Semver) {
            Semver a = asSemver(l);
            Semver b = asSemver(r);
            return a != null && b != null && a.compareTo(b) == 0;
        }
        // Otherwise compare rendered forms, so both 200 == "200" and 30s == "30s" hold, which is
        // what a config author writing YAML scalars expects.
        return Objects.equals(Functions.str(l), Functions.str(r));
    }

    private static Semver asSemver(Object v) {
        if (v instanceof Semver s) return s;
        if (v instanceof String s) return Semver.tryParse(s);
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object l, Object r, Node.Op op) {
        if (l == null || r == null) {
            throw new ExprException("cannot compare null with " + op.symbol()
                    + " (left=" + Literals.of(l) + ", right=" + Literals.of(r) + ")");
        }
        if (l instanceof Number a && r instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (l instanceof Semver || r instanceof Semver) {
            Semver a = asSemver(l);
            Semver b = asSemver(r);
            if (a == null || b == null) {
                throw new ExprException("cannot compare version with non-version: "
                        + Literals.of(l) + " " + op.symbol() + " " + Literals.of(r));
            }
            return a.compareTo(b);
        }
        if (l.getClass() == r.getClass() && l instanceof Comparable) {
            return ((Comparable) l).compareTo(r);
        }
        throw new ExprException("cannot compare " + typeName(l) + " with " + typeName(r)
                + " using " + op.symbol());
    }

    private static boolean contains(Object l, Object r) {
        if (l == null || r == null) return false;
        if (l instanceof Collection<?> c) return c.contains(r);
        if (l instanceof Map<?, ?> m) return m.containsKey(r);
        return Functions.str(l).contains(Functions.str(r));
    }

    private static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof Number) return "number";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Duration) return "duration";
        if (v instanceof Semver) return "version";
        if (v instanceof Collection) return "list";
        if (v instanceof Map) return "map";
        return v.getClass().getSimpleName();
    }
}
