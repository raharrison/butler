package net.ryanh.butler.config.model;

import java.time.Duration;

public record RetryDef(int attempts, Duration delay, Enums.Backoff backoff, Enums.RetryOn on) {
}
