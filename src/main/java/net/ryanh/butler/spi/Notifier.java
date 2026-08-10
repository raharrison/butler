package net.ryanh.butler.spi;

/**
 * A named notification channel, configured once under {@code notifiers:} and referenced by name
 * from a job's {@code notify:} policy.
 *
 * @param <C> the notifier's own parameter record
 */
public interface Notifier<C> {

    /**
     * The namespaced type name, e.g. {@code notify.slack}.
     */
    String name();

    /**
     * The parameter record. Must be a record; the registry rejects anything else.
     */
    Class<C> configType();

    void send(C config, String message) throws Exception;
}
