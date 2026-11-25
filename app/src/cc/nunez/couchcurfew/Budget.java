package cc.nunez.couchcurfew;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Today's time on the TV. There are three kinds of limit, and each can be off without losing its values: a daily total
 * for every app together, a daily limit per app, and time frames (parts of the day the parent defines) with a total
 * and limits per app of their own. Every limit that applies counts at the same time and the strictest wins.
 *
 * A TV has no profile per kid, so a time frame stands in for "who is watching": each frame keeps its own count, and
 * what one kid watches in the morning doesn't spend what another has in the afternoon. The daily total and the daily
 * limits per app are shared by every frame.
 *
 * Plain Java (no Android) so the rules run in test.sh. `today` is the TV's local date (yyyy-MM-dd) and `minute` the
 * minute of that day; a new date starts a new day.
 */
final class Budget {
    static final long MINUTE = 60_000L;
    /** Passed to setLimit to remove a limit; a limit of 0 blocks the app in kids mode. */
    static final int NO_LIMIT = -1;

    /** Days as bits, Monday first: the same masks as Schedule. */
    static final int ALL_DAYS = 0x7F, WEEKDAYS = 0x1F, WEEKEND = 0x60;
    static final int MAX_FRAMES = 4;

    /** In place of a package name: the daily total, for every app together. */
    static final String TOTAL = "*";

    /** Which limit is the strictest for an app right now. On a tie the one that lasts longer comes first. */
    static final int NONE = 0, APP = 1, DAY_TOTAL = 2, FRAME_APP = 3, FRAME_TOTAL = 4;

    /** A part of the day with its own count: a total for every app together and limits per app, both optional. */
    static final class Frame {
        String name;
        /** Days as bits, and the minutes of the day it runs from and to; it never crosses midnight. */
        int days, start, end;
        /** Minutes for every app together, NO_LIMIT without a total. */
        int total = NO_LIMIT;
        /** Minutes per app inside this frame; 0 blocks the app while the frame lasts. */
        final Map<String, Integer> limits = new HashMap<>();
        /** Today's time inside this frame per app, and the extra minutes granted in it. */
        final Map<String, Long> usedMs = new HashMap<>();
        final Map<String, Integer> extraMin = new HashMap<>();
        int totalExtraMin;

        Frame(String name, int days, int start, int end) {
            this.name = name;
            this.days = days;
            this.start = start;
            this.end = end;
        }

        boolean valid() {
            return days != 0 && start >= 0 && start < end && end <= 24 * 60;
        }

        boolean at(int weekday, int minute) {
            return (days & (1 << weekday)) != 0 && minute >= start && minute < end;
        }

        boolean overlaps(Frame other) {
            return (days & other.days) != 0 && start < other.end && other.start < end;
        }

        long totalUsedMs() {
            return sum(usedMs);
        }

        long totalRemainingMs() {
            return left(total, totalExtraMin, totalUsedMs());
        }
    }

    /** Daily minutes per app for each day of the week (Monday first); NO_LIMIT on a day without one. */
    final Map<String, int[]> limits = new HashMap<>();
    /** The daily limits per app count. Off keeps them, so turning them back on needs no typing. */
    boolean appsOn = true;
    /** The daily total for each day of the week; NO_LIMIT on a day without one. */
    final int[] dayTotal = {NO_LIMIT, NO_LIMIT, NO_LIMIT, NO_LIMIT, NO_LIMIT, NO_LIMIT, NO_LIMIT};
    /** Never overlapping, at most MAX_FRAMES. */
    final List<Frame> frames = new ArrayList<>();
    boolean framesOn = true;
    /** Apps that never count toward anything and that no limit blocks. */
    final Set<String> allowed = new HashSet<>();

    /** The day the usage and extras belong to, here and inside the frames. */
    String day = "";
    /** Today's time per app, for every app that counts. */
    final Map<String, Long> usedMs = new HashMap<>();
    final Map<String, Integer> extraMin = new HashMap<>();
    int totalExtraMin;

    /** A new day clears the usage and the extras; the limits stay. */
    void rollTo(String today) {
        if (today.equals(day)) return;
        day = today;
        usedMs.clear();
        extraMin.clear();
        totalExtraMin = 0;
        for (Frame f : frames) {
            f.usedMs.clear();
            f.extraMin.clear();
            f.totalExtraMin = 0;
        }
    }

    /** 0 for Monday to 6 for Sunday. */
    static int weekday(String day) {
        return java.time.LocalDate.parse(day).getDayOfWeek().getValue() - 1;
    }

    // MARK: - Always allowed

    /** An app that becomes always allowed gives back what it used today: it never counted. */
    void setAllowed(String pkg, boolean on) {
        if (!on) {
            allowed.remove(pkg);
            return;
        }
        allowed.add(pkg);
        usedMs.remove(pkg);
        for (Frame f : frames) f.usedMs.remove(pkg);
    }

    // MARK: - Time frames

    /** The frame that counts right now, null outside every frame or with the frames off. */
    Frame frameAt(String today, int minute) {
        rollTo(today);
        if (!framesOn) return null;
        int weekday = weekday(today);
        for (Frame f : frames) if (f.at(weekday, minute)) return f;
        return null;
    }

    /** The frame `candidate` would overlap with, null if it fits; `except` is the frame being edited. */
    Frame overlapping(Frame candidate, Frame except) {
        for (Frame f : frames) if (f != except && f.overlaps(candidate)) return f;
        return null;
    }

    /** The next minute of today at which a frame starts or ends, -1 if none is left. */
    int nextFrameChange(String today, int minute) {
        if (!framesOn) return -1;
        int weekday = weekday(today), next = -1;
        for (Frame f : frames) {
            if ((f.days & (1 << weekday)) == 0) continue;
            for (int at : new int[]{f.start, f.end}) if (at > minute && (next < 0 || at < next)) next = at;
        }
        return next;
    }

    /**
     * What 0.5.0 called the morning: one pool from midnight to `end` on `days`. It becomes a frame with that total.
     * Two things change with it and the release notes say so: apps without a limit now count toward that total, and
     * morning time now also spends an app's daily limit.
     */
    void adoptMorning(String name, int days, int end, int minutes) {
        Frame f = new Frame(name, days, 0, end);
        if (minutes <= 0 || !f.valid() || overlapping(f, null) != null || frames.size() >= MAX_FRAMES) return;
        f.total = minutes;
        frames.add(f);
    }

    // MARK: - The daily limits: one per app, and the total under TOTAL

    private int[] week(String subject) {
        return TOTAL.equals(subject) ? dayTotal : limits.get(subject);
    }

    /** The same limit every day. NO_LIMIT (any negative) removes it; 0 blocks the app. Today's usage is kept. */
    void setLimit(String subject, int minutes) {
        setLimit(subject, ALL_DAYS, minutes);
    }

    /** The limit for the days in `days` (a mask); the other days keep theirs. */
    void setLimit(String subject, int days, int minutes) {
        int[] week = week(subject);
        if (week == null) {
            week = new int[7];
            java.util.Arrays.fill(week, NO_LIMIT);
        }
        boolean any = false;
        for (int d = 0; d < 7; d++) {
            if ((days & (1 << d)) != 0) week[d] = minutes < 0 ? NO_LIMIT : minutes;
            any |= week[d] >= 0;
        }
        if (TOTAL.equals(subject)) return;
        if (any) limits.put(subject, week);
        else limits.remove(subject);
    }

    /** The limit on a day of the week (0 is Monday), NO_LIMIT without one. Says what is set, on or off. */
    int limitOn(String subject, int weekday) {
        int[] week = week(subject);
        return week == null ? NO_LIMIT : week[weekday];
    }

    /** Today's limit in minutes, null without one. */
    Integer limit(String subject, String today) {
        int minutes = limitOn(subject, weekday(today));
        return minutes < 0 ? null : minutes;
    }

    /** Weekdays and weekend have different limits. */
    boolean split(String subject) {
        return limitOn(subject, 0) != limitOn(subject, 5);
    }

    // MARK: - Counting

    /** Time in front, at `minute`: it counts for the day and for the frame that runs then. */
    void addUsage(String pkg, long ms, String today, int minute) {
        Frame f = frameAt(today, minute);
        if (ms <= 0 || allowed.contains(pkg)) return;
        add(usedMs, pkg, ms);
        if (f != null) add(f.usedMs, pkg, ms);
    }

    long usedMs(String pkg, String today) {
        rollTo(today);
        Long used = usedMs.get(pkg);
        return used == null ? 0 : used;
    }

    long totalUsedMs(String today) {
        rollTo(today);
        return sum(usedMs);
    }

    /** What is left of the daily limit alone (an app's, or the total's under TOTAL); Long.MAX_VALUE without one. */
    long ownRemainingMs(String subject, String today) {
        rollTo(today);
        int limit = limitOn(subject, weekday(today));
        if (TOTAL.equals(subject)) return left(limit, totalExtraMin, sum(usedMs));
        return left(limit, get(extraMin, subject), usedMs(subject, today));
    }

    /** What each limit leaves the app right now, in the order of the constants from APP on. */
    private long[] lefts(String pkg, String today, int minute) {
        Frame f = frameAt(today, minute);
        return new long[]{
                appsOn ? ownRemainingMs(pkg, today) : Long.MAX_VALUE,
                ownRemainingMs(TOTAL, today),
                f == null || !f.limits.containsKey(pkg) ? Long.MAX_VALUE : left(f.limits.get(pkg), get(f.extraMin, pkg), get(f.usedMs, pkg)),
                f == null ? Long.MAX_VALUE : f.totalRemainingMs()};
    }

    /** What the app has left right now: the least of every limit that applies. Long.MAX_VALUE without any. */
    long remainingMs(String pkg, String today, int minute) {
        if (allowed.contains(pkg)) return Long.MAX_VALUE;
        long least = Long.MAX_VALUE;
        for (long left : lefts(pkg, today, minute)) least = Math.min(least, left);
        return least;
    }

    /** The limit that leaves the app the least right now: APP, DAY_TOTAL, FRAME_APP or FRAME_TOTAL; NONE without any. */
    int strictest(String pkg, String today, int minute) {
        if (allowed.contains(pkg)) return NONE;
        long[] lefts = lefts(pkg, today, minute);
        int which = NONE;
        long least = Long.MAX_VALUE;
        for (int i = 0; i < lefts.length; i++) {
            if (lefts[i] < least) {
                least = lefts[i];
                which = i + 1;
            }
        }
        return which;
    }

    /**
     * More time for today, behind the PIN. It goes to the limits that have run out for the app, so one press opens it
     * whatever was holding it back; with none run out, to every limit that applies. Extra for a total is for every app.
     */
    void grantExtra(String pkg, int minutes, String today, int minute) {
        if (minutes <= 0 || allowed.contains(pkg)) return;
        long[] lefts = lefts(pkg, today, minute);
        boolean out = remainingMs(pkg, today, minute) == 0;
        Frame f = frameAt(today, minute);
        for (int i = 0; i < lefts.length; i++) {
            if (lefts[i] == Long.MAX_VALUE || (out && lefts[i] > 0)) continue;
            if (i + 1 == APP) addMinutes(extraMin, pkg, minutes);
            else if (i + 1 == DAY_TOTAL) totalExtraMin += minutes;
            else if (i + 1 == FRAME_APP) addMinutes(f.extraMin, pkg, minutes);
            else f.totalExtraMin += minutes;
        }
    }

    /**
     * What must be blocked right now: nothing outside kids mode; in it, the locked apps plus every app out of time.
     * `apps` are the apps that count: with a total, an app needs no limit of its own to run out.
     */
    Set<String> blocked(boolean kidsMode, Set<String> locked, Collection<String> apps, String today, int minute) {
        Set<String> out = new HashSet<>();
        if (!kidsMode) return out;
        out.addAll(locked);
        for (String pkg : apps) if (remainingMs(pkg, today, minute) == 0) out.add(pkg);
        return out;
    }

    // MARK: - Helpers

    /** What a limit of `minutes` leaves after `usedMs`; Long.MAX_VALUE without a limit, never negative. */
    private static long left(int minutes, long extra, long usedMs) {
        return minutes < 0 ? Long.MAX_VALUE : Math.max(0, (minutes + extra) * MINUTE - usedMs);
    }

    private static long sum(Map<String, Long> map) {
        long total = 0;
        for (long ms : map.values()) total += ms;
        return total;
    }

    private static <N extends Number> long get(Map<String, N> map, String key) {
        N value = map.get(key);
        return value == null ? 0 : value.longValue();
    }

    private static void add(Map<String, Long> map, String key, long more) {
        map.put(key, get(map, key) + more);
    }

    private static void addMinutes(Map<String, Integer> map, String key, int more) {
        map.put(key, (int) get(map, key) + more);
    }
}
