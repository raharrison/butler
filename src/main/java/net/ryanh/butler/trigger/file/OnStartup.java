package net.ryanh.butler.trigger.file;

/**
 * What a file trigger does about what is already there when the daemon starts.
 *
 * <p>{@link #LATEST} by default, so a host rebuilt or a daemon down while an artifact landed
 * converges rather than waiting for the next one. On an ordinary restart that costs nothing: the
 * dedupe key is already recorded, so the event is dropped before a run begins.
 */
public enum OnStartup {

    /**
     * Fire for the greatest candidate present.
     */
    LATEST,

    /**
     * Fire for nothing; treat everything present as already seen.
     */
    NONE,

    /**
     * Fire for every candidate present, oldest first.
     */
    ALL
}
