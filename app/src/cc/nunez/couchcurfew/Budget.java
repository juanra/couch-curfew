package cc.nunez.couchcurfew;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Today's time per app: the limits for each day of the week, what each limited app has used today and any extra minutes
 * granted. Plain Java (no Android) so the rules run in test.sh. `today` is the TV's local date (yyyy-MM-dd); a new date
 * starts a new day, with that weekday's limit.
 */
final class Budget {
    static final long MINUTE = 60_000L;
    /** Passed to setLimit to remove an app's limit; a limit of 0 blocks the app in kids mode. */
    static final int NO_LIMIT = -1;

    /** Days as bits, Monday first: the same masks as Schedule. The settings offer these two groups. */
    static final int ALL_DAYS = 0x7F, WEEKDAYS = 0x1F, WEEKEND = 0x60;

    /**
     * Minutes for each day of the week (Monday first) for each limited app; NO_LIMIT on a day without one. An app that
     * isn't here has no limit on any day.
     */
    final Map<String, int[]> limits = new HashMap<>();
    /** The day the usage and extras below belong to. */
    String day = "";
    final Map<String, Long> usedMs = new HashMap<>();
    final Map<String, Integer> extraMin = new HashMap<>();

    /** A new day clears the usage and the extras; the limits stay. */
    void rollTo(String today) {
        if (today.equals(day)) return;
        day = today;
        usedMs.clear();
        extraMin.clear();
    }

    /** The same limit every day. NO_LIMIT (any negative) removes it; 0 blocks the app. Usage already counted today is kept. */
    void setLimit(String pkg, int minutes) {
        setLimit(pkg, ALL_DAYS, minutes);
    }

    /** The limit for the days in `days` (a mask); the other days keep theirs. */
    void setLimit(String pkg, int days, int minutes) {
        int[] week = limits.get(pkg);
        if (week == null) {
            week = new int[7];
            java.util.Arrays.fill(week, NO_LIMIT);
        }
        boolean any = false;
        for (int d = 0; d < 7; d++) {
            if ((days & (1 << d)) != 0) week[d] = minutes < 0 ? NO_LIMIT : minutes;
            any |= week[d] >= 0;
        }
        if (any) limits.put(pkg, week);
        else limits.remove(pkg);
    }

    /** The limit on a day of the week (0 is Monday), NO_LIMIT without one. */
    int limitOn(String pkg, int weekday) {
        int[] week = limits.get(pkg);
        return week == null ? NO_LIMIT : week[weekday];
    }

    /** Today's limit in minutes, null without one. */
    Integer limit(String pkg, String today) {
        int minutes = limitOn(pkg, weekday(today));
        return minutes < 0 ? null : minutes;
    }

    /** Weekdays and weekend have different limits. */
    boolean split(String pkg) {
        return limitOn(pkg, 0) != limitOn(pkg, 5);
    }

    /** 0 for Monday to 6 for Sunday. */
    static int weekday(String day) {
        return java.time.LocalDate.parse(day).getDayOfWeek().getValue() - 1;
    }

    void addUsage(String pkg, long ms, String today) {
        rollTo(today);
        if (ms <= 0 || limit(pkg, today) == null) return;
        Long used = usedMs.get(pkg);
        usedMs.put(pkg, (used == null ? 0 : used) + ms);
    }

    void grantExtra(String pkg, int minutes, String today) {
        rollTo(today);
        if (minutes <= 0 || limit(pkg, today) == null) return;
        Integer extra = extraMin.get(pkg);
        extraMin.put(pkg, (extra == null ? 0 : extra) + minutes);
    }

    long usedMs(String pkg, String today) {
        rollTo(today);
        Long used = usedMs.get(pkg);
        return used == null ? 0 : used;
    }

    /** Long.MAX_VALUE for an app without a limit today. */
    long remainingMs(String pkg, String today) {
        rollTo(today);
        Integer limit = limit(pkg, today);
        if (limit == null) return Long.MAX_VALUE;
        Integer extra = extraMin.get(pkg);
        long allowance = (limit + (extra == null ? 0 : extra)) * MINUTE;
        return Math.max(0, allowance - usedMs(pkg, today));
    }

    boolean exhausted(String pkg, String today) {
        return limit(pkg, today) != null && remainingMs(pkg, today) == 0;
    }

    /** What must be blocked right now: nothing outside kids mode; in it, the locked apps plus every app out of time. */
    Set<String> blocked(boolean kidsMode, Set<String> locked, String today) {
        Set<String> out = new HashSet<>();
        if (!kidsMode) return out;
        out.addAll(locked);
        for (String pkg : limits.keySet()) if (exhausted(pkg, today)) out.add(pkg);
        return out;
    }
}
