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
 * out of time or locked, and keeps `pm suspend` in step with what must be blocked. Budget and screen state live on the
 * main thread; every ADB call runs on one background thread, in order.
 */
final class Guard {
    static final String TAG = "CouchCurfew";
    static final String PKG = "cc.nunez.couchcurfew";
    static final String SERVICE = PKG + "/" + PKG + ".GuardService";
    static final String ACTION_MIDNIGHT = PKG + ".MIDNIGHT";
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
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService adbThread = Executors.newSingleThreadExecutor();
    /** Packages Android refuses to suspend (the Play Store); for those, sending the TV home is the only block. */
    private final Set<String> unsuspendable = java.util.Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> warned = new HashSet<>();

    private AccessibilityService service;
    private String foreground = "";
    private long since = SystemClock.elapsedRealtime();
    private boolean screenOn = true;
    private boolean adbErrorShown;

    private Guard(Context app) {
        this.app = app;
        store = new Store(app);
        budget = store.loadBudget();
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
        if (budget.limits.containsKey(pkg)) Log.i(TAG, "in front: " + pkg + (store.kidsMode() ? ", counting" : ", kids mode off"));
        if (blockedNow().contains(pkg)) kick(pkg);
    }

    void onScreen(boolean on) {
        commit();
        screenOn = on;
    }

    /** Called every few seconds by the service, so time is counted (and the limit hit) while an app stays in front. */
    void tick() {
        commit();
    }

    private void commit() {
        long now = SystemClock.elapsedRealtime(), ms = now - since;
        since = now;
        String pkg = foreground, today = today();
        if (!store.kidsMode() || !screenOn || service == null || !budget.limits.containsKey(pkg)) return;
        budget.addUsage(pkg, ms, today);
        store.saveBudget(budget);
        long left = budget.remainingMs(pkg, today);
        if (left == 0) {
            kick(pkg);
            reconcile(null);
        } else if (left <= Budget.MINUTE && warned.add(today + "/" + pkg)) {
            toast(app.getString(R.string.one_minute_left, label(pkg)));
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

    Set<String> blockedNow() {
        Set<String> blocked = budget.blocked(store.kidsMode(), locked(), today());
        store.saveBudget(budget); // a new day may have cleared the usage
        return blocked;
    }

    /** Back to the home screen, and the app closed if it is out of time. */
    private void kick(final String pkg) {
        Log.i(TAG, "blocked: " + pkg);
        if (service != null) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        boolean outOfTime = budget.limits.containsKey(pkg);
        toast(outOfTime ? app.getString(R.string.time_up, label(pkg)) : app.getString(R.string.blocked_kids));
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

    /** Suspends what must be blocked and resumes what this app suspended and no longer must be. `done` runs after, on the ADB thread. */
    void reconcile(final Runnable done) {
        final Set<String> desired = blockedNow();
        adbThread.execute(() -> {
            try {
                apply(desired);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    private void apply(Set<String> desired) {
        Set<String> suspended = store.suspended();
        List<String> suspend = new ArrayList<>(), resume = new ArrayList<>();
        for (String p : desired) if (!suspended.contains(p) && !unsuspendable.contains(p) && SAFE_PACKAGE.matcher(p).matches()) suspend.add(p);
        for (String p : suspended) if (!desired.contains(p) && SAFE_PACKAGE.matcher(p).matches()) resume.add(p);
        if (suspend.isEmpty() && resume.isEmpty()) return;
        try (Adb adb = Adb.connect(app, 15_000, null)) {
            for (String p : suspend) {
                String out = adb.shell("pm suspend --user 0 " + p);
                if (out.contains("suspended state: true")) suspended.add(p);
                else {
                    unsuspendable.add(p);
                    Log.w(TAG, "Android won't suspend " + p + ": " + out.trim());
                }
            }
            for (String p : resume) {
                adb.shell("pm unsuspend --user 0 " + p);
                suspended.remove(p);
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

    // MARK: - Midnight

    void scheduleMidnight() {
        long midnight = LocalDate.now(ZoneId.systemDefault()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Intent i = new Intent(ACTION_MIDNIGHT).setClass(app, Receiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(app, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        // A few minutes late is fine: any event after midnight starts the new day on its own.
        am.setWindow(AlarmManager.RTC_WAKEUP, midnight + 5_000, 5 * 60_000, pi);
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

    private void toast(final String text) {
        main.post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }
}
