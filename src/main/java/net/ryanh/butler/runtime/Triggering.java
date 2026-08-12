package net.ryanh.butler.runtime;

import net.ryanh.butler.expr.*;
import net.ryanh.butler.spi.TriggerContext;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;

/**
 * The {@link TriggerContext} implementation: what a watcher is told about the job it watches for.
 */
public record Triggering(String job, Duration pollInterval, boolean dryRun)
        implements TriggerContext {

    /**
     * The expression is parsed once and evaluated per comparison, so a sort costs one parse.
     */
    @Override
    public Comparator<Map<String, Object>> ordering(String expression) {
        Node parsed = Expressions.condition(expression);
        return (a, b) -> {
            Object left = rank(parsed, a);
            Object right = rank(parsed, b);
            if (left == null || right == null) {
                // Unjudgeable sorts lowest, so it is never taken for the greatest candidate.
                return left == right ? 0 : left == null ? -1 : 1;
            }
            return Evaluator.rank(left, right);
        };
    }

    /**
     * @return what the expression comes to for one candidate, or null if it cannot be evaluated
     */
    private static Object rank(Node expression, Map<String, Object> facts) {
        try {
            return new Evaluator(Scope.of(Map.of()).with(facts)).eval(expression);
        } catch (ExprException e) {
            return null;
        }
    }
}
