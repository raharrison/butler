package net.ryanh.butler.runtime;

import net.ryanh.butler.spi.TriggerType;

import java.util.*;

/**
 * Every trigger type the daemon can watch with, keyed by {@link TriggerType#name()}.
 */
public final class TriggerRegistry {

    private final Map<String, TriggerType<?>> byName;

    private TriggerRegistry(Map<String, TriggerType<?>> byName) {
        this.byName = Collections.unmodifiableMap(byName);
    }

    /**
     * Loads every registered trigger type from the classpath.
     */
    public static TriggerRegistry discover() {
        List<TriggerType<?>> found = new ArrayList<>();
        for (TriggerType<?> type : ServiceLoader.load(TriggerType.class)) {
            found.add(type);
        }
        return of(found);
    }

    public static TriggerRegistry of(TriggerType<?>... types) {
        return of(List.of(types));
    }

    public static TriggerRegistry of(Collection<TriggerType<?>> types) {
        Map<String, TriggerType<?>> byName = new LinkedHashMap<>();
        for (TriggerType<?> type : types) {
            TriggerType<?> existing = byName.put(type.name(), type);
            if (existing != null) {
                throw new IllegalStateException("two trigger types are both named \""
                        + type.name() + "\": " + existing.getClass().getName() + " and "
                        + type.getClass().getName());
            }
            if (!type.configType().isRecord()) {
                throw new IllegalStateException("trigger type \"" + type.name()
                        + "\" must use a record for its parameters, found "
                        + type.configType().getName());
            }
        }
        return new TriggerRegistry(byName);
    }

    /**
     * @return the trigger type, or null if nothing is registered under that name
     */
    public TriggerType<?> find(String name) {
        return byName.get(name);
    }

    public Set<String> names() {
        return byName.keySet();
    }
}
