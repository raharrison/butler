package net.ryanh.butler.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one duration syntax in Butler, shared by the config reader and the expression lexer.
 */
class DurationsTest {

    @ParameterizedTest
    @CsvSource({
            "500ms, PT0.5S",
            "1s,    PT1S",
            "90s,   PT1M30S",
            "5m,    PT5M",
            "2h,    PT2H",
            "3d,    PT72H",
            "0s,    PT0S",
    })
    void parses(String text, String iso) {
        assertEquals(Duration.parse(iso), Durations.parse(text));
    }

    @Test
    void toleratesSurroundingSpace() {
        assertEquals(Duration.ofSeconds(30), Durations.parse("  30s "));
    }

    @Test
    @DisplayName("a bare number names the units it could have meant")
    void bareNumberIsRejectedHelpfully() {
        var e = assertThrows(IllegalArgumentException.class, () -> Durations.parse("30"));
        assertTrue(e.getMessage().contains("needs a unit"), e.getMessage());
        assertTrue(e.getMessage().contains("30s"), e.getMessage());
        assertTrue(e.getMessage().contains("30ms"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "s", "30 s", "30x", "-5s", "1.5s", "thirty"})
    void rejectsNonsense(String text) {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(text));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(null));
    }

    @Test
    @DisplayName("overflow is an IllegalArgumentException, so diagnostic collection survives it")
    void overflow() {
        // Duration.multipliedBy raises ArithmeticException, which callers catching
        // IllegalArgumentException would miss, taking every collected diagnostic with it.
        var e = assertThrows(IllegalArgumentException.class,
                () -> Durations.parse("99999999999999999d"));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> Durations.parse("99999999999999999999999s"));
    }

    @ParameterizedTest
    @CsvSource({
            "PT0S,      0s",
            "PT0.5S,    500ms",
            "PT1S,      1s",
            "PT1M30S,   90s",
            "PT5M,      5m",
            "PT2H,      2h",
            "PT72H,     3d",
    })
    @DisplayName("format picks the largest exact unit")
    void formats(String iso, String expected) {
        assertEquals(expected, Durations.format(Duration.parse(iso)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"500ms", "1s", "90s", "5m", "2h", "3d"})
    void formatRoundTripsThroughParse(String text) {
        assertEquals(text, Durations.format(Durations.parse(text)));
    }
}
