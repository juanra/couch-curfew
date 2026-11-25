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
    /** The name the morning of 0.5.0 gets as a time frame, in the TV's language. */
    private final String morningName;

    Store(Context c) {
        p = c.getSharedPreferences("state", Context.MODE_PRIVATE);
        morningName = c.getString(R.string.frame_morning);
    }

    /**
     * The rules and today's count. Since 0.6.0 they live under "rules" and "today", with "schema" 2. The keys of 0.5.0
     * ("limitsByDay", "used", "extra", "part"...) are read once and never written again, so an older version installed
     * over this one still finds its own data: that is the rollback.
     */
    Budget loadBudget() {
        if (p.getInt("schema", 1) < 2) {
            Budget b = migrate();
            saveBudget(b);
            return b;
        }
        Budget b = new Budget();
        try {
            JSONObject rules = new JSONObject(p.getString("rules", "{}")), today = new JSONObject(p.getString("today", "{}"));
            JSONObject apps = rules.optJSONObject("apps");
            if (apps != null) for (Iterator<String> it = apps.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONArray week = apps.getJSONArray(pkg);
                for (int d = 0; d < 7; d++) b.setLimit(pkg, 1 << d, week.getInt(d));
            }
            b.appsOn = rules.optBoolean("appsOn", true);
            JSONArray total = rules.optJSONArray("dayTotal");
            if (total != null) for (int d = 0; d < 7; d++) b.dayTotal[d] = total.getInt(d);
            b.framesOn = rules.optBoolean("framesOn", true);
            JSONArray allowed = rules.optJSONArray("allowed");
            if (allowed != null) for (int i = 0; i < allowed.length(); i++) b.allowed.add(allowed.getString(i));
            JSONArray frames = rules.optJSONArray("frames"), counts = today.optJSONArray("frames");
            if (frames != null) for (int i = 0; i < frames.length(); i++) {
                JSONObject o = frames.getJSONObject(i);
                Budget.Frame f = new Budget.Frame(o.getString("name"), o.getInt("days"), o.getInt("start"), o.getInt("end"));
                f.total = o.optInt("total", Budget.NO_LIMIT);
                for (Map.Entry<String, Long> e : longs(o.optString("limits", "{}")).entrySet()) f.limits.put(e.getKey(), e.getValue().intValue());
                JSONObject count = counts == null ? null : counts.optJSONObject(i);
                if (count != null) {
                    f.usedMs.putAll(longs(count.optString("used", "{}")));
                    for (Map.Entry<String, Long> e : longs(count.optString("extra", "{}")).entrySet()) f.extraMin.put(e.getKey(), e.getValue().intValue());
                    f.totalExtraMin = count.optInt("totalExtra", 0);
                }
                b.frames.add(f);
            }
            b.day = today.optString("day", "");
            b.usedMs.putAll(longs(today.optString("used", "{}")));
            for (Map.Entry<String, Long> e : longs(today.optString("extra", "{}")).entrySet()) b.extraMin.put(e.getKey(), e.getValue().intValue());
            b.totalExtraMin = today.optInt("totalExtra", 0);
        } catch (JSONException e) {
            android.util.Log.w(Guard.TAG, "stored rules unreadable, some may be missing: " + e.getMessage());
        }
        return b;
    }

    /**
     * From 0.5.0: the limits per app stay as they are, and the morning pool, if it was on, becomes a time frame. What
     * the pool had used today is not carried over (it was one number, not per app): the frame starts the day whole.
     */
    private Budget migrate() {
        Budget b = new Budget();
        b.day = p.getString("day", "");
        loadLimits(b);
        b.usedMs.putAll(longs(p.getString("used", "{}")));
        for (Map.Entry<String, Long> e : longs(p.getString("extra", "{}")).entrySet()) b.extraMin.put(e.getKey(), e.getValue().intValue());
        try {
            JSONObject part = new JSONObject(p.getString("part", "{}"));
            b.adoptMorning(morningName, part.optInt("days", Budget.WEEKDAYS), part.optInt("end", 13 * 60), part.optInt("minutes", 0));
        } catch (JSONException e) {
            android.util.Log.w(Guard.TAG, "stored morning unreadable, not carried over: " + e.getMessage());
        }
        return b;
    }

    /**
     * The limits of 0.5.0, seven per app (Monday first) under "limitsByDay". Up to 0.3 there was one number per app
     * under "limits": read as the same limit every day.
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
        JSONObject rules = new JSONObject(), today = new JSONObject(), apps = new JSONObject();
        JSONArray frames = new JSONArray(), counts = new JSONArray();
        try {
            for (Map.Entry<String, int[]> e : b.limits.entrySet()) apps.put(e.getKey(), new JSONArray(e.getValue()));
            for (Budget.Frame f : b.frames) {
                frames.put(new JSONObject().put("name", f.name).put("days", f.days).put("start", f.start).put("end", f.end)
                        .put("total", f.total).put("limits", new JSONObject(f.limits)));
                counts.put(new JSONObject().put("used", new JSONObject(f.usedMs)).put("extra", new JSONObject(f.extraMin)).put("totalExtra", f.totalExtraMin));
            }
            rules.put("apps", apps).put("appsOn", b.appsOn).put("dayTotal", new JSONArray(b.dayTotal)).put("framesOn", b.framesOn)
                    .put("allowed", new JSONArray(b.allowed)).put("frames", frames);
            today.put("day", b.day).put("used", new JSONObject(b.usedMs)).put("extra", new JSONObject(b.extraMin))
                    .put("totalExtra", b.totalExtraMin).put("frames", counts);
        } catch (JSONException e) {
            throw new IllegalStateException(e); // names and numbers always fit in JSON
        }
        p.edit().putInt("schema", 2).putString("rules", rules.toString()).putString("today", today.toString()).apply();
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
