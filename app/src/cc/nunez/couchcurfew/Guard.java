package cc.nunez.couchcurfew;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The rules at work: counts the time of the app in front while kids mode is on, sends the TV home from an app that is
 * out of time or locked, and keeps `pm suspend` in step with what must be blocked. It also writes every app's time, in
 * kids mode or not, to the history the dashboard shows. Budget and screen state live on the
 * main thread; every ADB call runs on one background thread, in order.
 */
final class Guard {
    static final String TAG = "CouchCurfew";
    static final String PKG = "cc.nunez.couchcurfew";
    static final String SERVICE = PKG + "/" + PKG + ".GuardService";
    static final String ACTION_ALARM = PKG + ".ALARM";
    private static final String PLAY_STORE = "com.android.vending";
    /** Package and component names that may go into a shell command; anything else is refused. */
    private static final Pattern SAFE_PACKAGE = Pattern.compile("[A-Za-z0-9_.]+");
    private static final Pattern SAFE_COMPONENT = Pattern.compile("[\\w.]+/[\\w.$]+");

    private static Guard instance;

    static synchronized Guard get(Context c) {
        if (instance == null) instance = new Guard(c.getApplicationContext());
        return instance;
    }

    final Store store;
    final Budget budget;
    final History history;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService adbThread = Executors.newSingleThreadExecutor();
    /** Packages Android refuses to suspend; for those, sending the TV home is the only block. */
    private final Set<String> unsuspendable = java.util.Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> warned = new HashSet<>();
    /** The dialog message each package was last suspended with; empty after a restart, so the first apply sets them all. */
    private final java.util.Map<String, String> suspendedWith = new java.util.concurrent.ConcurrentHashMap<>();

    private AccessibilityService service;
    private String foreground = "";
    private long since = SystemClock.elapsedRealtime();
    private boolean screenOn = true;
    /** The screensaver is on: the app behind it is not being watched, so the history skips it. The limits still count. */
    private boolean dreaming;
    private boolean adbErrorShown;
    /**
     * The time frame that counted at the last tick; when that changes, what is blocked changes with it. The first tick
     * always reconciles: the process may have been restarted across the end of a frame.
     */
    private Budget.Frame frame;
    private boolean ticked;
    /** The apps that count, as of the last look at what is blocked. */
    private Set<String> counted;

    private Guard(Context app) {
        this.app = app;
        store = new Store(app);
        budget = store.loadBudget();
        history = new History(app);
    }

    /** The minute of the day on the TV's clock. */
    static int minute() {
        java.time.LocalTime t = java.time.LocalTime.now(ZoneId.systemDefault());
        return t.getHour() * 60 + t.getMinute();
    }

    static String today() {
        return LocalDate.now(ZoneId.systemDefault()).toString();
    }

    // MARK: - Events from the accessibility service (main thread)

    void attach(AccessibilityService s) {
        service = s;
        since = SystemClock.elapsedRealtime();
    }

    void detach(AccessibilityService s) {
        if (service == s) {
            commit();
            service = null;
        }
    }

    void onForeground(String pkg) {
        if (pkg.equals(foreground)) return;
        commit();
        foreground = pkg;
        Set<String> blocked = blockedNow();
        if (counted.contains(pkg)) Log.i(TAG, "in front: " + pkg + (store.kidsMode() ? ", counting" : ", kids mode off"));
        if (blocked.contains(pkg)) kick(pkg);
    }

    void onScreen(boolean on) {
        commit();
        screenOn = on;
        if (!on) dreaming = false; // in case the screensaver's own end never arrives
    }

    void onDreaming(boolean on) {
        commit();
        dreaming = on;
    }

    /** Called every few seconds by the service, so time is counted (and the limit hit) while an app stays in front. */
    void tick() {
        commit();
        // The alarm for a frame's start or end can be a minute late; an open app is caught here.
        Budget.Frame now = budget.frameAt(today(), minute());
        if (!ticked || now != frame) {
            ticked = true;
            frame = now;
            warned.clear();
            reconcile(null);
        }
    }

    private void commit() {
        long now = SystemClock.elapsedRealtime(), ms = now - since;
        since = now;
        String pkg = foreground, today = today();
        int minute = minute();
        if (!screenOn || service == null || pkg.isEmpty()) return;
        boolean kids = store.kidsMode();
        if (!dreaming) {
            try {
                history.add(pkg, label(pkg), LocalDateTime.now(), ms, kids);
            } catch (android.database.sqlite.SQLiteException e) {
                Log.w(TAG, "history not written: " + e.getMessage()); // the limits must hold even if the history can't be saved
            }
        }
        if (!kids || !counted().contains(pkg)) return;
        budget.addUsage(pkg, ms, today, minute);
        store.saveBudget(budget);
        long left = budget.remainingMs(pkg, today, minute);
        if (left == 0) {
            kick(pkg);
            reconcile(null);
        } else if (left <= Budget.MINUTE) {
            // One warning per limit: a total warns once, whichever app is in front.
            int which = budget.strictest(pkg, today, minute);
            boolean own = which == Budget.APP || which == Budget.FRAME_APP;
            if (warned.add(today + "/" + which + (own ? pkg : ""))) {
                toast(own ? app.getString(R.string.one_minute_left, label(pkg)) : app.getString(R.string.one_minute_left_total));
            }
        }
    }

    // MARK: - Blocking

    Set<String> locked() {
        Set<String> out = new HashSet<>();
        out.add(PLAY_STORE);
        ResolveInfo settings = app.getPackageManager().resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
        if (settings != null && settings.activityInfo != null) out.add(settings.activityInfo.packageName);
        out.remove(PKG);
        return out;
    }

    /**
     * The apps on the TV's launcher, as the settings list them: not this app, the home screen (limiting it would send
     * home to home) nor the ones kids mode locks anyway.
     */
    List<ResolveInfo> tvApps() {
        PackageManager pm = app.getPackageManager();
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        Set<String> skip = locked();
        skip.add(PKG);
        if (home != null && home.activityInfo != null) skip.add(home.activityInfo.packageName);
        List<ResolveInfo> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) if (skip.add(r.activityInfo.packageName)) apps.add(r);
        return apps;
    }

    /** The apps whose time counts: with a total, an app needs no limit of its own to count or to run out. */
    private Set<String> counted() {
        if (counted == null) {
            counted = new HashSet<>();
            for (ResolveInfo r : tvApps()) counted.add(r.activityInfo.packageName);
        }
        return counted;
    }

    Set<String> blockedNow() {
        counted = null; // an app may have been installed or removed
        Set<String> blocked = budget.blocked(store.kidsMode(), locked(), counted(), today(), minute());
        store.saveBudget(budget); // a new day may have cleared the usage
        return blocked;
    }

    /** Back to the home screen, and the app closed if it is out of time. */
    private void kick(final String pkg) {
        Log.i(TAG, "blocked: " + pkg);
        if (service != null) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        toast(message(pkg));
        boolean outOfTime = !locked().contains(pkg);
        if (outOfTime && SAFE_PACKAGE.matcher(pkg).matches()) {
            adbThread.execute(() -> {
                try (Adb adb = Adb.connect(app, 15_000, null)) {
                    adb.shell("am force-stop " + pkg);
                } catch (IOException e) {
                    adbFailed(e);
                }
            });
        }
    }

    /** What a kid sees on a blocked app: in the toast, and in Android's dialog when they open a suspended app. */
    private String message(String pkg) {
        if (locked().contains(pkg)) return app.getString(R.string.msg_parents_only);
        String today = today();
        int minute = minute();
        Budget.Frame now = budget.frameAt(today, minute);
        switch (budget.strictest(pkg, today, minute)) {
            case Budget.DAY_TOTAL: return app.getString(R.string.msg_total_up);
            case Budget.FRAME_TOTAL: return app.getString(R.string.msg_frame_up, MainActivity.clock(now.end));
            case Budget.FRAME_APP: return app.getString(R.string.msg_frame_app_up, MainActivity.clock(now.end));
            default: return app.getString(budget.limitOn(pkg, Budget.weekday(today)) == 0 ? R.string.msg_not_allowed : R.string.msg_time_up);
        }
    }

    /** Suspends what must be blocked and resumes what this app suspended and no longer must be. `done` runs after, on the ADB thread. */
    void reconcile(final Runnable done) {
        // Settings and the Play Store are only ever sent home, never suspended: during boot Android shows a placeholder
        // home that lives in Settings, and a suspended Settings leaves the TV stuck on Android's "app not available".
        final java.util.Map<String, String> desired = new java.util.HashMap<>();
        Set<String> locked = locked();
        for (String p : blockedNow()) if (!locked.contains(p)) desired.put(p, message(p));
        adbThread.execute(() -> {
            try {
                apply(desired);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    private void apply(java.util.Map<String, String> desired) {
        Set<String> suspended = store.suspended();
        List<String> suspend = new ArrayList<>(), resume = new ArrayList<>();
        // Suspending again an app that is already suspended just replaces its message.
        for (java.util.Map.Entry<String, String> e : desired.entrySet()) {
            String p = e.getKey();
            boolean current = suspended.contains(p) && e.getValue().equals(suspendedWith.get(p));
            if (!current && !unsuspendable.contains(p) && SAFE_PACKAGE.matcher(p).matches()) suspend.add(p);
        }
        for (String p : suspended) if (!desired.containsKey(p) && SAFE_PACKAGE.matcher(p).matches()) resume.add(p);
        if (suspend.isEmpty() && resume.isEmpty()) return;
        try (Adb adb = Adb.connect(app, 15_000, null)) {
            for (String p : suspend) {
                String out = adb.shell("pm suspend --user 0 --dialogMessage " + shellQuote(desired.get(p)) + " " + p);
                if (out.contains("suspended state: true")) {
                    suspended.add(p);
                    suspendedWith.put(p, desired.get(p));
                } else {
                    unsuspendable.add(p);
                    Log.w(TAG, "Android won't suspend " + p + ": " + out.trim());
                }
            }
            for (String p : resume) {
                adb.shell("pm unsuspend --user 0 " + p);
                suspended.remove(p);
                suspendedWith.remove(p);
            }
            store.suspended(suspended);
            adbErrorShown = false;
        } catch (IOException e) {
            store.suspended(suspended); // keep what did go through
            adbFailed(e);
        }
    }

    private void adbFailed(IOException e) {
        Log.w(TAG, "ADB: " + e.getMessage());
        if (adbErrorShown) return;
        adbErrorShown = true;
        toast(app.getString(R.string.adb_unreachable, e.getMessage()));
    }

    // MARK: - Setup (the TV's own ADB, as Free My TV does)

    boolean serviceEnabled() {
        String list = Settings.Secure.getString(app.getContentResolver(), "enabled_accessibility_services");
        return list != null && list.contains(SERVICE);
    }

    interface Result { void done(String error); }

    /** Connects to the TV's ADB (asking for permission the first time) and turns the accessibility service on. */
    void setup(final Adb.Approval approval, final Result result) {
        adbThread.execute(() -> {
            String error = null;
            try (Adb adb = Adb.connect(app, 60_000, approval)) {
                enableService(adb);
                // TCL kills background starts of apps that lack this (the boot receiver, the midnight alarm).
                adb.shell("appops set " + PKG + " APP_AUTO_START allow");
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String message = error;
            main.post(() -> {
                result.done(message);
                if (message == null) reconcile(null);
            });
        });
    }

    /** After an update Android turns the service off; puts it back without asking (the TV already knows our key). */
    void repairService(Runnable done) {
        adbThread.execute(() -> {
            if (!serviceEnabled()) {
                try (Adb adb = Adb.connect(app, 15_000, null)) {
                    enableService(adb);
                } catch (IOException e) {
                    Log.w(TAG, "service not restored: " + e.getMessage());
                }
            }
            main.post(() -> reconcile(done));
        });
    }

    /** Adds ours to the enabled accessibility services, keeping the others (Free My TV's remote buttons, TalkBack). */
    private void enableService(Adb adb) throws IOException {
        String current = adb.shell("settings get secure enabled_accessibility_services").trim();
        if (current.equals("null")) current = "";
        List<String> list = new ArrayList<>();
        for (String one : current.split(":")) {
            if (one.isEmpty() || one.equals(SERVICE)) continue;
            if (!SAFE_COMPONENT.matcher(one).matches()) throw new IOException("unexpected entry in the accessibility services");
            list.add(one);
        }
        list.add(SERVICE);
        adb.shell("settings put secure enabled_accessibility_services '" + String.join(":", list) + "'");
        adb.shell("settings put secure accessibility_enabled 1");
    }

    // MARK: - Alarm: midnight (a new day), the schedule's next start or end, or a time frame's, whichever comes first

    void scheduleAlarm() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().plusDays(1).atStartOfDay();
        LocalDateTime boundary = Schedule.nextBoundary(store.windows(), now);
        if (boundary != null && boundary.isBefore(next)) next = boundary;
        // A time frame starting or ending changes what is blocked, so it is a boundary too.
        int change = budget.nextFrameChange(today(), minute());
        if (change >= 0 && change < 24 * 60) {
            LocalDateTime at = now.toLocalDate().atStartOfDay().plusMinutes(change);
            if (at.isBefore(next)) next = at;
        }
        long at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Intent i = new Intent(ACTION_ALARM).setClass(app, Receiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(app, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        // Up to a minute late is fine; exact alarms need a permission the TV has no screen for.
        am.setWindow(AlarmManager.RTC_WAKEUP, at + 1_000, 60_000, pi);
    }

    /** At the alarm: kids mode may have just turned on or off; apply it to what's in front and to the suspended apps. */
    void onAlarm(Runnable done) {
        commit();
        pruneHistory();
        Log.i(TAG, "alarm: kids mode " + (store.kidsMode() ? "on" : "off"));
        if (!foreground.isEmpty() && blockedNow().contains(foreground)) kick(foreground);
        reconcile(done);
        scheduleAlarm();
    }

    /** Called at midnight and when the service starts. */
    void pruneHistory() {
        try {
            history.prune(LocalDate.now());
        } catch (android.database.sqlite.SQLiteException e) {
            Log.w(TAG, "history not pruned: " + e.getMessage());
        }
    }

    // MARK: - Helpers

    String label(String pkg) {
        PackageManager pm = app.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** One shell word: single quotes, with any single quote inside closed, escaped and reopened. */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void toast(final String text) {
        main.post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }
}
