package net.ryanh.butler.spi;

/**
 * The one way a step sends a message through a channel declared under {@code notifiers:}.
 *
 * <p>On the SPI beside {@link ProcessRunner}, because a step may not depend on the runtime, and
 * looking a channel up by name and binding its parameters is the runtime's work.
 */
@FunctionalInterface
public interface Notifications {

    /**
     * @param to      the name of a channel in the config's {@code notifiers:} block
     * @param message the rendered message
     * @throws Exception if there is no such channel, or delivery failed
     */
    void send(String to, String message) throws Exception;
}
