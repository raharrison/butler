package net.ryanh.butler.trigger.schedule;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;

/**
 * A five-field cron expression: minute, hour, day of month, month, day of week.
 *
 * <p>Each field is a comma-separated list of {@code *}, {@code a} or {@code a-b}, any of them with
 * a trailing {@code /step}. Months and days may be named. Day 0 and day 7 both mean Sunday.
 */
public final class Cron {

    private static final String[] MONTHS =
            {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final String[] DAYS = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};

    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean anyDayOfMonth;
    private final boolean anyDayOfWeek;
    private final String source;

    private Cron(BitSet minutes, BitSet hours, BitSet daysOfMonth, BitSet months,
                 BitSet daysOfWeek, boolean anyDayOfMonth, boolean anyDayOfWeek, String source) {
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.anyDayOfMonth = anyDayOfMonth;
        this.anyDayOfWeek = anyDayOfWeek;
        this.source = source;
    }

    /**
     * @throws IllegalArgumentException with a message naming the field that is wrong
     */
    public static Cron parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("empty cron expression");
        }
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("a cron expression has five fields "
                    + "(minute hour day-of-month month day-of-week), found " + fields.length
                    + " in \"" + expression + "\"");
        }
        return new Cron(
                field(fields[0], 0, 59, "minute", null),
                field(fields[1], 0, 23, "hour", null),
                field(fields[2], 1, 31, "day-of-month", null),
                field(fields[3], 1, 12, "month", MONTHS),
                field(fields[4], 0, 7, "day-of-week", DAYS),
                fields[2].equals("*"), fields[4].equals("*"), expression.trim());
    }

    /**
     * @param names spellings accepted beside the numbers, or null where the field has none
     */
    private static BitSet field(String text, int min, int max, String name, String[] names) {
        BitSet set = new BitSet(max + 1);
        for (String part : text.split(",")) {
            range(set, part.trim(), min, max, name, names);
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException(name + " field \"" + text + "\" matches nothing");
        }
        // Sunday is both 0 and 7, so naming either matches both.
        if (name.equals("day-of-week") && (set.get(0) || set.get(7))) {
            set.set(0);
            set.set(7);
        }
        return set;
    }

    private static void range(BitSet set, String part, int min, int max, String name,
                              String[] names) {
        int step = 1;
        String spec = part;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            spec = part.substring(0, slash);
            step = number(part.substring(slash + 1), name, part, null);
            if (step < 1) {
                throw new IllegalArgumentException(name + " step must be at least 1, found \""
                        + part + "\"");
            }
        }

        int from;
        int to;
        if (spec.equals("*")) {
            from = min;
            to = max;
        } else {
            int dash = spec.indexOf('-');
            if (dash > 0) {
                from = number(spec.substring(0, dash), name, part, names);
                to = number(spec.substring(dash + 1), name, part, names);
            } else {
                from = number(spec, name, part, names);
                to = slash >= 0 ? max : from;
            }
        }
        if (from < min || to > max || from > to) {
            throw new IllegalArgumentException(name + " \"" + part + "\" is outside " + min + "-"
                    + max);
        }
        for (int i = from; i <= to; i += step) {
            set.set(i);
        }
    }

    private static int number(String text, String field, String part, String[] names) {
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equalsIgnoreCase(text)) {
                    return field.equals("month") ? i + 1 : i;
                }
            }
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " \"" + part + "\" is not a number");
        }
    }

    /**
     * The first firing strictly after {@code from}.
     *
     * <p>Whole fields are skipped rather than minutes counted, so an expression that fires once a
     * year costs a handful of comparisons rather than half a million.
     *
     * <p>Two daylight-saving cases. A local time the clock skips is moved forward by the length of
     * the gap, which is {@link ZonedDateTime#of}'s behaviour and keeps a nightly job nightly. A
     * local time the clock repeats resolves to the first pass, which can be behind a caller in the
     * second, so the instant is checked as well: returning it would have a watcher sleep a
     * negative duration, fire, and be given the same answer again.
     */
    public ZonedDateTime next(ZonedDateTime from) {
        LocalDateTime at = from.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDateTime limit = at.plusYears(5);

        while (at.isBefore(limit)) {
            if (!months.get(at.getMonthValue())) {
                at = at.plusMonths(1).withDayOfMonth(1).toLocalDate().atStartOfDay();
                continue;
            }
            if (!matchesDay(at)) {
                at = at.plusDays(1).toLocalDate().atStartOfDay();
                continue;
            }
            if (!hours.get(at.getHour())) {
                at = at.plusHours(1).withMinute(0);
                continue;
            }
            if (!minutes.get(at.getMinute())) {
                at = at.plusMinutes(1);
                continue;
            }
            ZonedDateTime firing = ZonedDateTime.of(at, from.getZone());
            if (firing.toInstant().isAfter(from.toInstant())) {
                return firing;
            }
            at = at.plusMinutes(1);
        }
        throw new IllegalStateException("\"" + source + "\" does not fire within five years");
    }

    /**
     * With both day fields restricted, either matches: {@code 0 0 1 * mon} fires on the first of
     * the month and on every Monday.
     */
    private boolean matchesDay(LocalDateTime at) {
        boolean byMonth = daysOfMonth.get(at.getDayOfMonth());
        boolean byWeek = daysOfWeek.get(dayOfWeek(at.getDayOfWeek()));
        if (anyDayOfMonth && anyDayOfWeek) {
            return true;
        }
        if (anyDayOfWeek) {
            return byMonth;
        }
        if (anyDayOfMonth) {
            return byWeek;
        }
        return byMonth || byWeek;
    }

    private static int dayOfWeek(DayOfWeek day) {
        return day == DayOfWeek.SUNDAY ? 0 : day.getValue();
    }

    public static ZoneId zone(String timezone) {
        return timezone == null || timezone.isBlank() ? ZoneId.systemDefault()
                : ZoneId.of(timezone);
    }

    @Override
    public String toString() {
        return source;
    }
}
