package net.ryanh.butler.trigger.schedule;

import net.ryanh.butler.runtime.Triggering;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.TriggerContext;
import net.ryanh.butler.spi.Watcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cron parser, table-driven against known next-fire times, and the schedule watchers.
 */
class ScheduleTriggerTest {

    private static ZonedDateTime next(String expression, String from, String zone) {
        return Cron.parse(expression).next(ZonedDateTime.parse(from).withZoneSameInstant(
                ZoneId.of(zone)));
    }

    @Nested
    @DisplayName("the cron parser")
    class Parsing {

        @ParameterizedTest(name = "{0} after {1} fires at {2}")
        @CsvSource({
                // every minute
                "'* * * * *',       2026-03-01T10:15:30Z, 2026-03-01T10:16:00Z",
                // a fixed time of day
                "'0 3 * * *',       2026-03-01T10:15:00Z, 2026-03-02T03:00:00Z",
                "'0 3 * * *',       2026-03-01T02:59:00Z, 2026-03-01T03:00:00Z",
                // a step
                "'*/15 * * * *',    2026-03-01T10:16:00Z, 2026-03-01T10:30:00Z",
                "'0 */6 * * *',     2026-03-01T07:00:00Z, 2026-03-01T12:00:00Z",
                // a list
                "'0 0,12 * * *',    2026-03-01T06:00:00Z, 2026-03-01T12:00:00Z",
                // a range
                "'0 9-17 * * *',    2026-03-01T20:00:00Z, 2026-03-02T09:00:00Z",
                // a day of the month
                "'0 0 1 * *',       2026-03-02T00:00:00Z, 2026-04-01T00:00:00Z",
                // a month, which means waiting the best part of a year
                "'0 0 1 1 *',       2026-03-02T00:00:00Z, 2027-01-01T00:00:00Z",
                // a day of the week: 2026-03-01 is a Sunday, so the next Monday is the 2nd
                "'30 4 * * 1',      2026-03-01T00:00:00Z, 2026-03-02T04:30:00Z",
                // Sunday is 0 and 7 alike
                "'0 0 * * 0',       2026-03-02T00:00:00Z, 2026-03-08T00:00:00Z",
                "'0 0 * * 7',       2026-03-02T00:00:00Z, 2026-03-08T00:00:00Z",
                // names, for the fields that have them
                "'0 0 1 jan *',     2026-03-02T00:00:00Z, 2027-01-01T00:00:00Z",
                "'0 0 * * mon',     2026-03-01T00:00:00Z, 2026-03-02T00:00:00Z",
        })
        void nextFiring(String expression, String from, String expected) {
            assertEquals(Instant.parse(expected),
                    next(expression, from, "UTC").toInstant());
        }

        @Test
        @DisplayName("with both day fields restricted, cron matches either, as every crontab does")
        void bothDayFieldsMatchEither() {
            // The 1st of April 2026 is a Wednesday; the next Monday is the 6th.
            assertEquals(Instant.parse("2026-04-01T00:00:00Z"),
                    next("0 0 1 * mon", "2026-03-31T00:00:00Z", "UTC").toInstant());
            assertEquals(Instant.parse("2026-04-06T00:00:00Z"),
                    next("0 0 1 * mon", "2026-04-01T00:01:00Z", "UTC").toInstant());
        }

        @Test
        @DisplayName("the next firing is strictly after the moment asked about")
        void neverFiresForTheInstantGiven() {
            assertEquals(Instant.parse("2026-03-02T03:00:00Z"),
                    next("0 3 * * *", "2026-03-01T03:00:00Z", "UTC").toInstant());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "* * * *",
                "* * * * * *",
                "60 * * * *",
                "* 24 * * *",
                "0 0 0 * *",
                "0 0 * 13 *",
                "0 0 * * 8",
                "*/0 * * * *",
                "5-1 * * * *",
                "x * * * *",
                "",
        })
        void badExpressionsAreRefused(String expression) {
            assertThrows(IllegalArgumentException.class, () -> Cron.parse(expression));
        }

        @Test
        void aBadFieldIsNamed() {
            var e = assertThrows(IllegalArgumentException.class, () -> Cron.parse("0 25 * * *"));
            assertTrue(e.getMessage().contains("hour"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("daylight saving")
    class DaylightSaving {

        private static final String LONDON = "Europe/London";

        @Test
        @DisplayName("an ordinary job still fires once a day across the spring-forward night")
        void springForward() {
            // London moves 01:00 to 02:00 on 2026-03-29, so 00:30 that day is the last firing
            // before the jump and 00:30 on the 30th is the next.
            ZonedDateTime after = next("30 0 * * *", "2026-03-28T12:00:00Z", LONDON);
            assertEquals("2026-03-29T00:30", after.toLocalDateTime().toString());
            assertEquals("2026-03-30T00:30",
                    Cron.parse("30 0 * * *").next(after).toLocalDateTime().toString());
        }

        @Test
        @DisplayName("a local time the clock skips still fires that day, shifted past the gap")
        void aSkippedLocalTimeStillFires() {
            // 01:30 does not exist on 2026-03-29 in London: the clock goes 00:59:59 -> 02:00:00.
            // The firing moves forward by the length of the gap rather than being dropped, so a
            // nightly job still runs once on the night the clocks go forward.
            ZonedDateTime fired = next("30 1 * * *", "2026-03-28T12:00:00Z", LONDON);
            assertEquals("2026-03-29T02:30", fired.toLocalDateTime().toString());
            assertEquals(Instant.parse("2026-03-29T01:30:00Z"), fired.toInstant());
            assertEquals("2026-03-30T01:30",
                    Cron.parse("30 1 * * *").next(fired).toLocalDateTime().toString());
        }

        @Test
        @DisplayName("a local time the clock repeats fires on the first pass through it")
        void aRepeatedLocalTimeFiresOnce() {
            // London moves 02:00 back to 01:00 on 2026-10-25, so 01:30 happens twice.
            ZonedDateTime fired = next("30 1 * * *", "2026-10-24T12:00:00Z", LONDON);
            assertEquals(Instant.parse("2026-10-25T00:30:00Z"), fired.toInstant());
            assertEquals("2026-10-26T01:30",
                    Cron.parse("30 1 * * *").next(fired).toLocalDateTime().toString());
        }

        @Test
        @DisplayName("asked from inside the repeated hour, the next firing is still in the future")
        void neverAnswersWithAPastInstant() {
            // 01:15 GMT on 2026-10-25 is the second pass through 01:15 local. Resolving 01:30
            // local there gives the first pass, 00:30Z, which is behind the caller: a watcher told
            // that would sleep a negative duration, fire, and be told the same thing again.
            ZonedDateTime inside = ZonedDateTime.parse("2026-10-25T01:15:00Z")
                    .withZoneSameInstant(ZoneId.of(LONDON));
            ZonedDateTime fired = Cron.parse("30 1 * * *").next(inside);

            assertTrue(fired.toInstant().isAfter(inside.toInstant()),
                    "fired at " + fired + ", asked from " + inside);
            assertEquals(Instant.parse("2026-10-26T01:30:00Z"), fired.toInstant());
        }

        @Test
        @DisplayName("every firing of a per-minute job through the repeated hour moves forward, "
                + "and the hour is traversed once")
        void everyMinuteKeepsMovingForward() {
            Cron cron = Cron.parse("* * * * *");
            ZonedDateTime at = ZonedDateTime.parse("2026-10-25T00:50:00Z")
                    .withZoneSameInstant(ZoneId.of(LONDON));
            for (int i = 0; i < 90; i++) {
                ZonedDateTime next = cron.next(at);
                assertTrue(next.toInstant().isAfter(at.toInstant()),
                        "went backwards at " + at + " -> " + next);
                at = next;
            }
            // A cron expression names local times, so the hour the clock repeats is walked once:
            // 01:59 local is followed by 02:00 local, an hour of real time later. Ninety firings
            // therefore cover a hundred and fifty minutes.
            assertEquals(Instant.parse("2026-10-25T03:20:00Z"), at.toInstant());
        }
    }

    @Nested
    @DisplayName("the watchers")
    class Watchers {

        private final List<Event> fired = new CopyOnWriteArrayList<>();
        private final TriggerContext ctx = new Triggering("test", Duration.ofMillis(20), false);

        @Test
        @DisplayName("schedule.every fires on its interval, and not before the first one elapses")
        void everyFiresOnTheInterval() throws Exception {
            Watcher watcher = new EveryTrigger().start(
                    new EveryTrigger.Config(Duration.ofMillis(100)), fired::add, ctx);
            try {
                Thread.sleep(50);
                assertTrue(fired.isEmpty(),
                        "a daemon that ran every job the moment it started would turn a restart "
                                + "into a deployment");
                Thread.sleep(300);
                assertTrue(fired.size() >= 2, "fired " + fired.size() + " times");
                assertNotNull(fired.getFirst().facts().get("fired_at"));
                assertNull(fired.getFirst().dedupeKey(), "every firing is new work");
            } finally {
                watcher.stop();
            }
        }

        @Test
        @DisplayName("schedule.cron fires when the expression comes round")
        void cronFires() throws Exception {
            Watcher watcher = new CronTrigger().start(
                    new CronTrigger.Config("* * * * *", "UTC"), fired::add, ctx);
            try {
                // Not waiting a minute for it: what matters here is that it starts, computes a
                // next firing and stops cleanly.
                Thread.sleep(100);
                assertTrue(fired.isEmpty());
            } finally {
                watcher.stop();
            }
        }

        @Test
        void aBadCronExpressionIsRefusedWhenTheWatcherStarts() {
            assertThrows(IllegalArgumentException.class, () -> new CronTrigger().start(
                    new CronTrigger.Config("not a cron", null), fired::add, ctx));
        }

        @Test
        @DisplayName("a schedule has nothing to observe, so it offers no candidate")
        void schedulesHaveNoCurrentCandidates() {
            assertEquals(List.of(), new EveryTrigger().current(
                    new EveryTrigger.Config(Duration.ofMinutes(1)), ctx));
            assertEquals(List.of(), new CronTrigger().current(
                    new CronTrigger.Config("* * * * *", null), ctx));
        }
    }
}
