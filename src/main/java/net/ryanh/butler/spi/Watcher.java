package net.ryanh.butler.spi;

/**
 * A running trigger. Owns one virtual thread and stops when asked, so shutdown is a loop over
 * every watcher rather than an executor pool to drain.
 */
@FunctionalInterface
public interface Watcher {
    void stop();
}
