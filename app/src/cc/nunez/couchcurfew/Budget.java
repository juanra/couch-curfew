package cc.nunez.couchcurfew;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Today's time per app: the daily limits, what each limited app has used and any extra minutes granted. Plain Java (no
 * Android) so the rules run in test.sh. `today` is the TV's local date (yyyy-MM-dd); a new date starts a new day.
 */
final class Budget {
    static final long MINUTE = 60_000L;

    /** Minutes per day for each limited app; an app that isn't here has no limit. */
    final Map<String, Integer> limits = new HashMap<>();
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

    /** Zero or less removes the limit. Usage already counted today is kept. */
    void setLimit(String pkg, int minutes) {
        if (minutes <= 0) limits.remove(pkg);
        else limits.put(pkg, minutes);
    }

    void addUsage(String pkg, long ms, String today) {
        rollTo(today);
        if (ms <= 0 || !limits.containsKey(pkg)) return;
        Long used = usedMs.get(pkg);
        usedMs.put(pkg, (used == null ? 0 : used) + ms);
    }

    void grantExtra(String pkg, int minutes, String today) {
        rollTo(today);
        if (minutes <= 0 || !limits.containsKey(pkg)) return;
        Integer extra = extraMin.get(pkg);
        extraMin.put(pkg, (extra == null ? 0 : extra) + minutes);
    }

    long usedMs(String pkg, String today) {
        rollTo(today);
        Long used = usedMs.get(pkg);
        return used == null ? 0 : used;
    }

    /** Long.MAX_VALUE for an app without a limit. */
    long remainingMs(String pkg, String today) {
        rollTo(today);
        Integer limit = limits.get(pkg);
        if (limit == null) return Long.MAX_VALUE;
        Integer extra = extraMin.get(pkg);
        long allowance = (limit + (extra == null ? 0 : extra)) * MINUTE;
        return Math.max(0, allowance - usedMs(pkg, today));
    }

    boolean exhausted(String pkg, String today) {
        return limits.containsKey(pkg) && remainingMs(pkg, today) == 0;
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
