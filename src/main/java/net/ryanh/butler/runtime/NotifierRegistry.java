package net.ryanh.butler.runtime;

import net.ryanh.butler.spi.Notifier;

import java.util.*;

/**
 * Every notification channel type the daemon can send through, keyed by {@link Notifier#name()}.
 */
public final class NotifierRegistry {

    private final Map<String, Notifier<?>> byName;

    private NotifierRegistry(Map<String, Notifier<?>> byName) {
        this.byName = Collections.unmodifiableMap(byName);
    }

    /**
     * Loads every registered notifier type from the classpath.
     */
    public static NotifierRegistry discover() {
        return discover(NotifierRegistry.class.getClassLoader());
    }

    /**
     * Loads every registered notifier type visible to one loader, which is how a plugin jar
     * joins the vocabulary (see {@link Plugins}).
     */
    public static NotifierRegistry discover(ClassLoader loader) {
        List<Notifier<?>> found = new ArrayList<>();
        for (Notifier<?> type : ServiceLoader.load(Notifier.class, loader)) {
            found.add(type);
        }
        return of(found);
    }

    public static NotifierRegistry of(Notifier<?>... types) {
        return of(List.of(types));
    }

    public static NotifierRegistry of(Collection<Notifier<?>> types) {
        Map<String, Notifier<?>> byName = new LinkedHashMap<>();
        for (Notifier<?> type : types) {
            Notifier<?> existing = byName.put(type.name(), type);
            if (existing != null) {
                throw new IllegalStateException("two notifier types are both named \""
                        + type.name() + "\": " + existing.getClass().getName() + " and "
                        + type.getClass().getName());
            }
            if (!type.configType().isRecord()) {
                throw new IllegalStateException("notifier type \"" + type.name()
                        + "\" must use a record for its parameters, found "
                        + type.configType().getName());
            }
        }
        return new NotifierRegistry(byName);
    }

    /**
     * @return the notifier type, or null if nothing is registered under that name
     */
    public Notifier<?> find(String name) {
        return byName.get(name);
    }

    public Set<String> names() {
        return byName.keySet();
    }
}
