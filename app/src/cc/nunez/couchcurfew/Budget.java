package cc.nunez.couchcurfew;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Today's time per app: the limits for each day of the week, what each limited app has used today and any extra minutes
 * granted. Plain Java (no Android) so the rules run in test.sh. `today` is the TV's local date (yyyy-MM-dd); a new date
 * starts a new day, with that weekday's limit.
 *
 * A part of the day (the morning, in the settings) can have one pool of minutes shared by every app with a limit:
 * time watched in it counts against the pool and leaves the apps' own limits untouched for the rest of the day.
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

    /**
     * The part of the day with a shared pool: on the days in `partDays`, from midnight until `partEnd` (minutes of the
     * day). `partMinutes` is the pool; 0 turns the part off. Stored as a general day part so it can grow into more
     * windows without a migration; the settings only offer weekday mornings.
     */
    int partDays = WEEKDAYS, partEnd = 13 * 60, partMinutes = 0;
    /** Today's time against the pool, and the extra minutes granted to it. */
    long partUsedMs;
    int partExtraMin;
    /** A minute of the day that is never inside the part: the methods without a time answer for the app's own limit. */
    private static final int OUTSIDE = 24 * 60;

    /** A new day clears the usage and the extras; the limits stay. */
    void rollTo(String today) {
        if (today.equals(day)) return;
        day = today;
        usedMs.clear();
        extraMin.clear();
        partUsedMs = 0;
        partExtraMin = 0;
    }

    // MARK: - The part of the day with a shared pool

    /** The pool is what counts at `minute` of `today`. */
    boolean inPart(String today, int minute) {
        return partMinutes > 0 && (partDays & (1 << weekday(today))) != 0 && minute < partEnd;
    }

    /** The app draws from the pool now: an app with time of its own. One without a limit stays free, one set to 0 stays blocked. */
    boolean pooled(String pkg, String today, int minute) {
        return inPart(today, minute) && limitOn(pkg, weekday(today)) > 0;
    }

    /**
     * Sets the pool's minutes; 0 turns it off. Turned on in the middle of the part, what the apps used today moves
     * into the pool: all of it was watched in this part, and it would otherwise be charged to the rest of the day.
     */
    void setPart(int minutes, String today, int minute) {
        rollTo(today);
        boolean wasOff = partMinutes <= 0;
        partMinutes = Math.max(0, minutes);
        if (!wasOff || !inPart(today, minute)) return;
        for (java.util.Iterator<Map.Entry<String, Long>> it = usedMs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            if (limitOn(e.getKey(), weekday(today)) <= 0) continue;
            partUsedMs += e.getValue();
            it.remove();
        }
    }

    void grantPartExtra(int minutes, String today) {
        rollTo(today);
        if (minutes > 0) partExtraMin += minutes;
    }

    long partUsedMs(String today) {
        rollTo(today);
        return partUsedMs;
    }

    long partRemainingMs(String today) {
        rollTo(today);
        return Math.max(0, (partMinutes + partExtraMin) * MINUTE - partUsedMs);
    }

    void addUsage(String pkg, long ms, String today, int minute) {
        rollTo(today);
        if (ms > 0 && pooled(pkg, today, minute)) partUsedMs += ms;
        else addUsage(pkg, ms, today);
    }

    /** What the app has left at `minute`: the pool's during the part, its own otherwise. */
    long remainingMs(String pkg, String today, int minute) {
        return pooled(pkg, today, minute) ? partRemainingMs(today) : remainingMs(pkg, today);
    }

    boolean exhausted(String pkg, String today, int minute) {
        return limit(pkg, today) != null && remainingMs(pkg, today, minute) == 0;
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

    /** What must be blocked outside the part of the day with a pool. */
    Set<String> blocked(boolean kidsMode, Set<String> locked, String today) {
        return blocked(kidsMode, locked, today, OUTSIDE);
    }

    /** What must be blocked right now: nothing outside kids mode; in it, the locked apps plus every app out of time. */
    Set<String> blocked(boolean kidsMode, Set<String> locked, String today, int minute) {
        Set<String> out = new HashSet<>();
        if (!kidsMode) return out;
        out.addAll(locked);
        for (String pkg : limits.keySet()) if (exhausted(pkg, today, minute)) out.add(pkg);
        return out;
    }
}
