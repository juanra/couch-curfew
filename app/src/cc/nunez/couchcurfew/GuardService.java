package cc.nunez.couchcurfew;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Tells the Guard which app is in front. Reads only window changes (package and class names), never what's on screen.
 * Dialogs, toasts and overlays (volume, Free My TV's sleep pill) change windows too, so only real activities count.
 */
public class GuardService extends AccessibilityService {
    private static final long TICK_MS = 15_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Boolean> activities = new HashMap<>();
    private Guard guard;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            guard.tick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            guard.onScreen(Intent.ACTION_SCREEN_ON.equals(i.getAction()));
        }
    };

    @Override protected void onServiceConnected() {
        guard = Guard.get(this);
        guard.attach(this);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        handler.postDelayed(tick, TICK_MS);
        guard.reconcile(null);
        guard.scheduleMidnight();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (guard == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (e.getPackageName() == null || e.getClassName() == null) return;
        String pkg = e.getPackageName().toString(), cls = e.getClassName().toString();
        if (isActivity(pkg, cls)) guard.onForeground(pkg);
    }

    private boolean isActivity(String pkg, String cls) {
        String key = pkg + "/" + cls;
        Boolean known = activities.get(key);
        if (known == null) {
            try {
                getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
                known = true;
            } catch (PackageManager.NameNotFoundException ex) {
                known = false;
            }
            activities.put(key, known);
        }
        return known;
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (guard != null) {
            try { unregisterReceiver(screen); } catch (IllegalArgumentException ignored) {}
            guard.detach(this);
        }
        super.onDestroy();
    }
}
