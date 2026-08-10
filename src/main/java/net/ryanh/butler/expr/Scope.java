package net.ryanh.butler.expr;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Value lookup for expression evaluation, keyed by the leading path segment.
 *
 * <p>Roots are either context namespaces ({@code vars}, {@code trigger}, {@code state}, ...) or
 * locals a step injects into a condition's scope, such as {@code status} and {@code json} for
 * {@code http.wait}'s {@code until:}.
 */
@FunctionalInterface
public interface Scope {

    /**
     * @return the value bound to a leading segment, or null if there is none
     */
    Object root(String name);

    static Scope of(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        return copy::get;
    }

    /** A scope with step-injected locals layered on top, taking priority over the base. */
    default Scope with(Map<String, Object> locals) {
        Map<String, Object> extra = new LinkedHashMap<>(locals);
        return name -> extra.containsKey(name) ? extra.get(name) : root(name);
    }
}
