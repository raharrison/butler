package net.ryanh.butler.runtime;

import net.ryanh.butler.spi.StepType;

import java.util.*;

/**
 * Every step type the daemon can run, keyed by {@link StepType#name()}.
 *
 * <p>Discovery is {@link ServiceLoader}, which is the seam that keeps the runtime ignorant of what
 * a step actually does. Nothing here imports a concrete step.
 */
public final class StepRegistry {

    private final Map<String, StepType<?>> byName;

    private StepRegistry(Map<String, StepType<?>> byName) {
        this.byName = Collections.unmodifiableMap(byName);
    }

    /**
     * Loads every registered step type from the classpath.
     */
    public static StepRegistry discover() {
        List<StepType<?>> found = new ArrayList<>();
        for (StepType<?> type : ServiceLoader.load(StepType.class)) {
            found.add(type);
        }
        return of(found);
    }

    public static StepRegistry of(StepType<?>... types) {
        return of(List.of(types));
    }

    public static StepRegistry of(Collection<StepType<?>> types) {
        Map<String, StepType<?>> byName = new LinkedHashMap<>();
        for (StepType<?> type : types) {
            StepType<?> existing = byName.put(type.name(), type);
            if (existing != null) {
                throw new IllegalStateException("two step types are both named \"" + type.name()
                        + "\": " + existing.getClass().getName() + " and "
                        + type.getClass().getName());
            }
            if (!type.configType().isRecord()) {
                throw new IllegalStateException("step type \"" + type.name()
                        + "\" must use a record for its parameters, found "
                        + type.configType().getName());
            }
        }
        return new StepRegistry(byName);
    }

    /**
     * @return the step type, or null if nothing is registered under that name
     */
    public StepType<?> find(String name) {
        return byName.get(name);
    }

    public Set<String> names() {
        return byName.keySet();
    }

    public Collection<StepType<?>> all() {
        return byName.values();
    }
}
