package cc.nunez.couchcurfew;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
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
        for (Map.Entry<String, Long> e : longs(p.getString("limits", "{}")).entrySet()) b.limits.put(e.getKey(), e.getValue().intValue());
        b.usedMs.putAll(longs(p.getString("used", "{}")));
        for (Map.Entry<String, Long> e : longs(p.getString("extra", "{}")).entrySet()) b.extraMin.put(e.getKey(), e.getValue().intValue());
        return b;
    }

    void saveBudget(Budget b) {
        p.edit().putString("day", b.day).putString("limits", new JSONObject(b.limits).toString())
                .putString("used", new JSONObject(b.usedMs).toString()).putString("extra", new JSONObject(b.extraMin).toString())
                .apply();
    }

    boolean kidsMode() { return p.getBoolean("kids", false); }
    void kidsMode(boolean on) { p.edit().putBoolean("kids", on).apply(); }

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
