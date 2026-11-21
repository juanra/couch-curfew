package cc.nunez.couchcurfew;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * A year of what was in front of the TV, in the app's private database: one row per day, hour and app. `ms` is all the
 * time with the screen on; `kids_ms` is the part of it spent in kids mode. `app` keeps each app's name, so the history
 * still reads "Netflix" after the app is removed. Nothing leaves the TV.
 */
final class History extends SQLiteOpenHelper {
    History(Context c) {
        super(c, "history.db", null, 1);
    }

    /** Apps whose name was already saved since the process started; one write per app is enough. */
    private final Set<String> named = new HashSet<>();

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE usage (day TEXT NOT NULL, hour INTEGER NOT NULL, pkg TEXT NOT NULL, ms INTEGER NOT NULL,"
                + " kids_ms INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (day, hour, pkg)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE app (pkg TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL) WITHOUT ROWID");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {}

    /** Adds the `ms` that `pkg`, shown as `label`, was in front until `end`, spread over the hours they fell in. */
    void add(final String pkg, String label, LocalDateTime end, long ms, final boolean kids) {
        final SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (named.add(pkg)) db.execSQL("INSERT OR REPLACE INTO app (pkg, label) VALUES (?, ?)", new Object[]{pkg, label});
            Usage.split(end, ms, (day, hour, part) -> {
                Object[] row = {day, hour, pkg};
                // Two statements: the upsert syntax needs a newer SQLite than Android 8 has.
                db.execSQL("INSERT OR IGNORE INTO usage (day, hour, pkg, ms) VALUES (?, ?, ?, 0)", row);
                db.execSQL("UPDATE usage SET ms = ms + " + part + ", kids_ms = kids_ms + " + (kids ? part : 0)
                        + " WHERE day = ? AND hour = ? AND pkg = ?", row);
            });
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Drops what is older than a year, and the names of apps with no time left. */
    void prune(LocalDate today) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM usage WHERE day < ?", new Object[]{today.minusDays(Usage.KEEP_DAYS).toString()});
        db.execSQL("DELETE FROM app WHERE pkg NOT IN (SELECT DISTINCT pkg FROM usage)");
        named.clear();
    }

    /** The name saved for each app when its time was recorded. */
    Map<String, String> labels() {
        Map<String, String> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pkg, label FROM app", null)) {
            while (c.moveToNext()) out.put(c.getString(0), c.getString(1));
        }
        return out;
    }

    /** Time per day, for the days that have any. */
    Map<String, Long> byDay(LocalDate from, LocalDate to) {
        return totals("day", "ms", "day", from, to);
    }

    /** Time in kids mode per day, for the days that have any row. */
    Map<String, Long> kidsByDay(LocalDate from, LocalDate to) {
        return totals("day", "kids_ms", "day", from, to);
    }

    /** Time per app, the most used first. */
    Map<String, Long> byApp(LocalDate from, LocalDate to) {
        return totals("pkg", "ms", "SUM(ms) DESC", from, to);
    }

    /** Time per hour of the day, 0 to 23. */
    long[] byHour(LocalDate from, LocalDate to) {
        long[] out = new long[24];
        for (Map.Entry<String, Long> e : totals("hour", "ms", "hour", from, to).entrySet()) out[Integer.parseInt(e.getKey())] = e.getValue();
        return out;
    }

    private Map<String, Long> totals(String column, String sum, String order, LocalDate from, LocalDate to) {
        Map<String, Long> out = column.equals("pkg") ? new LinkedHashMap<String, Long>() : new HashMap<String, Long>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + column + ", SUM(" + sum + ") FROM usage WHERE day BETWEEN ? AND ? GROUP BY "
                + column + " ORDER BY " + order, new String[]{from.toString(), to.toString()})) {
            while (c.moveToNext()) out.put(c.getString(0), c.getLong(1));
        }
        return out;
    }
}
