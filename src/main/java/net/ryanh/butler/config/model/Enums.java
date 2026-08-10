package net.ryanh.butler.config.model;

/**
 * Closed value sets in the config. Kept as enums so the validator can list what is allowed.
 */
public final class Enums {

    private Enums() {
    }

    public enum LogFormat {
        JSON, TEXT
    }

    public enum ConcurrencyMode {
        QUEUE, SKIP, CANCEL_PREVIOUS
    }

    public enum Backoff {
        FIXED, EXPONENTIAL
    }

    /**
     * Which failure kinds a retry policy applies to.
     */
    public enum RetryOn {
        FAILURE, TIMEOUT, ALWAYS
    }

    /**
     * Run outcomes a notify policy can fire on.
     */
    public enum Outcome {
        SUCCESS, FAILURE
    }
}
