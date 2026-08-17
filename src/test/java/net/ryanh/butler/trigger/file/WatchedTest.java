package net.ryanh.butler.trigger.file;

import net.ryanh.butler.runtime.Triggering;
import net.ryanh.butler.spi.TriggerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code poll_interval:} resolution: a trigger's own value wins when it is usable, the daemon
 * default otherwise.
 */
class WatchedTest {

    private static final TriggerContext CTX = new Triggering("test", Duration.ofSeconds(5), false);

    @Test
    @DisplayName("no override falls back to the daemon default")
    void noOverride() {
        assertEquals(Duration.ofSeconds(5), Watched.pollInterval(null, CTX));
    }

    @Test
    @DisplayName("a positive override wins over the daemon default")
    void positiveOverrideWins() {
        assertEquals(Duration.ofMillis(50), Watched.pollInterval(Duration.ofMillis(50), CTX));
    }

    @Test
    @DisplayName("a zero override is treated as unset rather than spinning")
    void zeroOverrideFallsBack() {
        assertEquals(Duration.ofSeconds(5), Watched.pollInterval(Duration.ZERO, CTX));
    }

    @Test
    @DisplayName("a negative override is treated as unset rather than failing")
    void negativeOverrideFallsBack() {
        assertEquals(Duration.ofSeconds(5), Watched.pollInterval(Duration.ofSeconds(-1), CTX));
    }
}
