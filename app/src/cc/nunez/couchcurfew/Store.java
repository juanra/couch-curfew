package cc.nunez.couchcurfew;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the app remembers, in its private SharedPreferences. */
final class Store {
    private final SharedPreferences p;

    Store(Context c) {
        p = c.getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    Budget loadBudget() {
        Budget b = new Budget();
        b.day = p.getString("day", "");
        loadLimits(b);
        b.usedMs.putAll(longs(p.getString("used", "{}")));
        for (Map.Entry<String, Long> e : longs(p.getString("extra", "{}")).entrySet()) b.extraMin.put(e.getKey(), e.getValue().intValue());
        try {
            JSONObject part = new JSONObject(p.getString("part", "{}"));
            b.partDays = part.optInt("days", b.partDays);
            b.partEnd = part.optInt("end", b.partEnd);
            b.partMinutes = part.optInt("minutes", 0);
        } catch (JSONException e) {
            android.util.Log.w(Guard.TAG, "stored morning unreadable, starting with it off: " + e.getMessage());
        }
        b.partUsedMs = p.getLong("partUsed", 0);
        b.partExtraMin = p.getInt("partExtra", 0);
        return b;
    }

    /**
     * The limits, seven per app (Monday first) under "limitsByDay". Up to 0.3 there was one number per app under
     * "limits": read once as the same limit every day, and left in place so an older version still finds it.
     */
    private void loadLimits(Budget b) {
        String byDay = p.getString("limitsByDay", null);
        if (byDay == null) {
            for (Map.Entry<String, Long> e : longs(p.getString("limits", "{}")).entrySet()) b.setLimit(e.getKey(), e.getValue().intValue());
            return;
        }
        try {
            JSONObject o = new JSONObject(byDay);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONArray week = o.getJSONArray(pkg);
                for (int d = 0; d < 7; d++) b.setLimit(pkg, 1 << d, week.getInt(d));
            }
        } catch (JSONException e) {
            android.util.Log.w(Guard.TAG, "stored limits unreadable, starting without limits: " + e.getMessage());
        }
    }

    void saveBudget(Budget b) {
        JSONObject limits = new JSONObject(), part = new JSONObject();
        try {
            for (Map.Entry<String, int[]> e : b.limits.entrySet()) limits.put(e.getKey(), new JSONArray(e.getValue()));
            part.put("days", b.partDays).put("end", b.partEnd).put("minutes", b.partMinutes);
        } catch (JSONException e) {
            throw new IllegalStateException(e); // a package name and seven ints always fit in JSON
        }
        p.edit().putString("day", b.day).putString("limitsByDay", limits.toString())
                .putString("used", new JSONObject(b.usedMs).toString()).putString("extra", new JSONObject(b.extraMin).toString())
                .putString("part", part.toString()).putLong("partUsed", b.partUsedMs).putInt("partExtra", b.partExtraMin)
                .apply();
    }

    // MARK: - Kids mode: the schedule, and a parent's change that wins until the schedule's next boundary

    /** Kids mode right now. */
    boolean kidsMode() {
        migrateKids();
        String until = p.getString("manualUntil", "");
        Boolean manual = p.contains("manual") ? p.getBoolean("manual", false) : null;
        return Schedule.kidsMode(windows(), manual, until.isEmpty() ? null : LocalDateTime.parse(until), LocalDateTime.now());
    }

    /** A parent's switch: holds until the next start or end of a window (for good if there are no windows). */
    void kidsMode(boolean on) {
        LocalDateTime until = Schedule.nextBoundary(windows(), LocalDateTime.now());
        p.edit().putBoolean("manual", on).putString("manualUntil", until == null ? "" : until.toString()).commit();
    }

    /** When the manual change stops counting, or null if it already has (or holds for good). */
    LocalDateTime manualUntil() {
        String until = p.getString("manualUntil", "");
        if (!p.contains("manual") || until.isEmpty()) return null;
        LocalDateTime t = LocalDateTime.parse(until);
        return t.isAfter(LocalDateTime.now()) ? t : null;
    }

    List<Schedule.Window> windows() {
        List<Schedule.Window> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("windows", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Schedule.Window(o.getInt("days"), o.getInt("start"), o.getInt("end")));
            }
        } catch (JSONException e) {
            android.util.Log.w(Guard.TAG, "schedule unreadable, starting empty: " + e.getMessage());
        }
        return out;
    }

    /** Saving the schedule drops a manual change, so the new schedule applies right away. */
    void windows(List<Schedule.Window> windows) {
        JSONArray a = new JSONArray();
        try {
            for (Schedule.Window w : windows) a.put(new JSONObject().put("days", w.days).put("start", w.start).put("end", w.end));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        p.edit().putString("windows", a.toString()).remove("manual").remove("manualUntil").commit();
    }

    /** Before schedules, "kids" was the switch: it becomes a manual change that holds for good. */
    private void migrateKids() {
        if (!p.contains("kids")) return;
        p.edit().putBoolean("manual", p.getBoolean("kids", false)).putString("manualUntil", "").remove("kids").commit();
    }

    /** The age band for the daily guideline: one of Usage.BANDS, "off" to show none, "" while the parent hasn't chosen. */
    String guideline() { return p.getString("guidelineBand", ""); }
    void guideline(String band) { p.edit().putString("guidelineBand", band).apply(); }

    /** The packages this app suspended, so it only ever resumes its own. */
    Set<String> suspended() { return new HashSet<>(p.getStringSet("suspended", new HashSet<String>())); }
    void suspended(Set<String> pkgs) { p.edit().putStringSet("suspended", new HashSet<>(pkgs)).commit(); }

    boolean hasPin() { return p.contains("pinHash"); }
    boolean pinMatches(String pin) { return Pin.matches(pin, p.getString("pinSalt", null), p.getString("pinHash", null)); }
    void setPin(String pin) {
        String salt = Pin.newSalt();
        p.edit().putString("pinSalt", salt).putString("pinHash", Pin.hash(pin, salt)).putInt("pinFailures", 0).commit();
    }

    int pinFailures() { return p.getInt("pinFailures", 0); }
    long pinLastFailure() { return p.getLong("pinLastFailure", 0); }
    void pinFailed(long now) { p.edit().putInt("pinFailures", pinFailures() + 1).putLong("pinLastFailure", now).commit(); }
    void pinPassed() { p.edit().putInt("pinFailures", 0).apply(); }

    private static Map<String, Long> longs(String json) {
        Map<String, Long> out = new java.util.HashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, o.getLong(k));
            }
        } catch (JSONException e) {
            android.util.Log.w(Guard.TAG, "stored state unreadable, starting empty: " + e.getMessage());
        }
        return out;
    }
}
