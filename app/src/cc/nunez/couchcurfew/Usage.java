package cc.nunez.couchcurfew;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * The arithmetic of the usage history: which day and hour a stretch of time belongs to, and how a range of days is cut
 * into the bars of the dashboard. Plain Java (no Android) so it runs in test.sh. Days are the TV's local dates.
 */
final class Usage {
    /** The ranges the dashboard offers, in days ending today. */
    static final int[] RANGES = {1, 7, 30, 90, 365};
    /** History older than this many days is dropped. */
    static final int KEEP_DAYS = 366;

    interface Sink { void add(String day, int hour, long ms); }

    /**
     * Hands out the `ms` that ended at `end` to the hours they fell in, newest first.
     * shortcut: wall-clock hours, so the hour repeated or skipped by a clock change is off by up to an hour; use
     * zoned time if that ever matters.
     */
    static void split(LocalDateTime end, long ms, Sink sink) {
        while (ms > 0) {
            LocalDateTime start = end.truncatedTo(ChronoUnit.HOURS);
            if (start.equals(end)) start = end.minusHours(1);
            long part = Math.min(ms, Duration.between(start, end).toMillis());
            sink.add(start.toLocalDate().toString(), start.getHour(), part);
            ms -= part;
            end = start;
        }
    }

    /** The first day of a range of `days` ending on `today`. */
    static LocalDate from(LocalDate today, int days) {
        return today.minusDays(days - 1);
    }

    /** One total per stretch of `step` days; the stretches end on `to`, so the first one may be shorter. */
    static long[] bySteps(Map<String, Long> byDay, LocalDate from, LocalDate to, int step) {
        int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
        int n = (days + step - 1) / step;
        long[] out = new long[n];
        for (int i = 0; i < days; i++) {
            Long ms = byDay.get(to.minusDays(i).toString());
            if (ms != null) out[n - 1 - i / step] += ms;
        }
        return out;
    }

    /** One total per calendar month from `from`'s month to `to`'s. */
    static long[] byMonths(Map<String, Long> byDay, LocalDate from, LocalDate to) {
        YearMonth first = YearMonth.from(from);
        long[] out = new long[(int) ChronoUnit.MONTHS.between(first, YearMonth.from(to)) + 1];
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Long ms = byDay.get(d.toString());
            if (ms != null) out[(int) ChronoUnit.MONTHS.between(first, YearMonth.from(d))] += ms;
        }
        return out;
    }

    // MARK: - The daily guideline for kids' screen time

    /**
     * The age bands a parent picks from, as stored. The minutes are derived, never stored, so the figures can follow
     * the guidelines without a migration.
     */
    static final String[] BANDS = {"under2", "2to4", "5to17"};

    /**
     * Minutes of screen time a day for the band, from the Canadian 24-Hour Movement Guidelines: 1 h for ages 2 to 4,
     * 2 h of recreational screen time for 5 to 17. 0 when there is no number to compare with: under 2 (not
     * recommended at all), not set, or turned off.
     */
    static int guidelineMinutes(String band) {
        return "2to4".equals(band) ? 60 : "5to17".equals(band) ? 120 : 0;
    }

    static final int UNDER = 0, CLOSE = 1, OVER = 2;

    /** Where a day stands: over the guideline, close to it (from 80%) or under. Whole minutes, as the screen shows them. */
    static int level(long ms, int guidelineMinutes) {
        long minutes = ms / 60_000;
        return minutes > guidelineMinutes ? OVER : minutes * 5 >= guidelineMinutes * 4L ? CLOSE : UNDER;
    }

    /** How many days from `from` to `to` are UNDER, CLOSE and OVER; a day without time is under. */
    static int[] levels(Map<String, Long> byDay, LocalDate from, LocalDate to, int guidelineMinutes) {
        int[] out = new int[3];
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Long ms = byDay.get(d.toString());
            out[level(ms == null ? 0 : ms, guidelineMinutes)]++;
        }
        return out;
    }

    /** The hour (0 to 23) with the most time, or -1 when there is none. */
    static int peak(long[] byHour) {
        int best = -1;
        for (int h = 0; h < byHour.length; h++) if (byHour[h] > 0 && (best < 0 || byHour[h] > byHour[best])) best = h;
        return best;
    }
}
