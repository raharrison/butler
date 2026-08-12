package net.ryanh.butler.expr;

import java.util.List;

/**
 * The expression AST.
 *
 * <p>Node types are a closed set, so {@link Evaluator}'s switch has no {@code default} branch and
 * adding a node type makes the compiler point at every site that must handle it. This is the only
 * sealed hierarchy in Butler; steps and triggers are open.
 */
public sealed interface Node {

    /**
     * A literal value: string, number, duration, boolean or null.
     */
    record Lit(Object value) implements Node {
    }

    /**
     * A variable reference such as {@code steps.health.status} or {@code items[0].name}.
     */
    record Var(List<Seg> path) implements Node {
        /**
         * The leading segment, which is the namespace or a step-injected local.
         */
        public String root() {
            return path.getFirst().name();
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            for (Seg s : path) {
                if (s.isIndex()) {
                    sb.append('[').append(s.index()).append(']');
                } else {
                    if (!sb.isEmpty()) sb.append('.');
                    sb.append(s.name());
                }
            }
            return sb.toString();
        }
    }

    /**
     * A function call such as {@code semver(trigger.version)}.
     */
    record Call(String name, List<Node> args) implements Node {
    }

    /**
     * A binary operation.
     */
    record Bin(Op op, Node left, Node right) implements Node {
    }

    /**
     * Logical negation.
     */
    record Not(Node operand) implements Node {
    }

    /**
     * One step of a variable path: either a name or an array index.
     */
    record Seg(String name, int index) {
        public static Seg name(String n) {
            return new Seg(n, -1);
        }

        public static Seg index(int i) {
            return new Seg(null, i);
        }

        public boolean isIndex() {
            return name == null;
        }
    }

    enum Op {
        OR("or"), AND("and"),
        EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="),
        MATCHES("matches"), CONTAINS("contains");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }
}
