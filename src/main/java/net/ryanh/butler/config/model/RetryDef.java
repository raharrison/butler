package net.ryanh.butler.config.model;

import java.time.Duration;

/**
 * A step's retry policy.
 */
public record RetryDef(int attempts, Duration delay, Enums.Backoff backoff, Enums.RetryOn on) {
}
