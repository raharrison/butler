package net.ryanh.butler.config;

import java.util.List;
import java.util.function.Function;

/**
 * What {@link ConfigValidator} needs to know about a step or trigger type, keyed by its
 * {@code uses:}.
 *
 * <p>Declared here because {@code config} depends on nothing above it; the registries implement it.
 * Per type rather than a union over all of them, or any step could reference any other's locals and
 * {@code message: ${json.version}} on {@code control.log} would validate.
 */
public interface Vocabulary {

    /**
     * @return what is known about that step type, or null if nothing is registered under the name
     */
    Facts step(String uses);

    /**
     * @return what is known about that trigger type, or null if nothing is registered under the name
     */
    Facts trigger(String uses);

    /**
     * @param conditions parameters read as a bare condition rather than a string template
     * @param locals     names the type injects into its own expressions. A trigger declares none,
     *                   since its facts are whatever its own regex captured
     */
    record Facts(List<String> conditions, List<String> locals) {

        public Facts {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
            locals = locals == null ? List.of() : List.copyOf(locals);
        }
    }

    /**
     * Assembled by the caller, which is the only place both registries are known.
     */
    static Vocabulary of(Function<String, Facts> steps, Function<String, Facts> triggers) {
        return new Vocabulary() {
            @Override
            public Facts step(String uses) {
                return steps.apply(uses);
            }

            @Override
            public Facts trigger(String uses) {
                return triggers.apply(uses);
            }
        };
    }

    /**
     * A vocabulary that knows no type at all, for a config checked without a registry.
     */
    static Vocabulary empty() {
        return of(uses -> null, uses -> null);
    }
}
