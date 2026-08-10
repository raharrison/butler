package net.ryanh.butler.expr;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Value lookup for expression evaluation, keyed by the leading path segment.
 *
 * <p>Roots are either context namespaces ({@code vars}, {@code trigger}, {@code state}, ...) or
 * locals a step injects into a condition's scope, such as {@code status} and {@code json} for
 * {@code http.wait}'s {@code until:}.
 */
public interface Scope {

    /**
     * @return the value bound to a leading segment, or null if there is none
     */
    Object root(String name);

    Set<String> roots();

    static Scope of(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        return new Scope() {
            @Override
            public Object root(String name) {
                return copy.get(name);
            }

            @Override
            public Set<String> roots() {
                return copy.keySet();
            }
        };
    }

    /**
     * A scope with step-injected locals layered on top, taking priority over the base.
     */
    default Scope with(Map<String, Object> locals) {
        Scope base = this;
        Map<String, Object> extra = new LinkedHashMap<>(locals);
        return new Scope() {
            @Override
            public Object root(String name) {
                return extra.containsKey(name) ? extra.get(name) : base.root(name);
            }

            @Override
            public Set<String> roots() {
                Set<String> all = new LinkedHashSet<>(base.roots());
                all.addAll(extra.keySet());
                return all;
            }
        };
    }
}
