package cc.nunez.couchcurfew;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The parent's screen, behind the PIN: a kids mode switch on top and a card per TV app with its limit and today's time.
 * Built in code (no layouts, no AndroidX) and made for the remote: everything is focusable and grows when focused.
 */
public class MainActivity extends Activity {
    private static final int[] LIMITS = {15, 30, 45, 60, 90, 120, 180};

    // The icon's night palette.
    private static final int SKY_TOP = 0xFF2E3375, SKY_BOTTOM = 0xFF14173A, CARD = 0xFF262B5C, CARD_FOCUS = 0xFF3A4190;
    private static final int MOON = 0xFFF4C542, CORAL = 0xFFE07A5F, GREEN = 0xFF7BC67E, MUTED = 0xFFB8BCE0;

    private static final String KIDS = "kids", SETUP = "setup", PIN = "pin";

    private Guard guard;
    private LinearLayout content;
    private boolean unlocked;
    private AlertDialog pinDialog;
    /** What had focus before a refresh (a package or one of the tags above), to put it back after. */
    private String focusTag;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        guard = Guard.get(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(36), dp(56), dp(36));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{SKY_TOP, SKY_BOTTOM}));
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!unlocked) askPin();
        else refresh();
    }

    /** Leaving the screen locks it again, so a kid can't come back to it later. */
    @Override protected void onStop() {
        super.onStop();
        unlocked = false;
        content.removeAllViews();
        if (pinDialog != null) pinDialog.dismiss();
    }

    // MARK: - PIN

    private void askPin() {
        if (!guard.store.hasPin()) {
            newPin(this::unlock);
            return;
        }
        long wait = Pin.lockoutMs(guard.store.pinFailures()) - (System.currentTimeMillis() - guard.store.pinLastFailure());
        if (wait > 0) {
            Toast.makeText(this, getString(R.string.pin_wait, (int) (wait / 1000) + 1), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        pinPrompt(getString(R.string.enter_pin), pin -> {
            if (guard.store.pinMatches(pin)) {
                guard.store.pinPassed();
                unlock();
            } else {
                guard.store.pinFailed(System.currentTimeMillis());
                Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_SHORT).show();
                askPin();
            }
        });
    }

    private void unlock() {
        unlocked = true;
        refresh();
    }

    /** Asks for a new PIN twice; `then` runs once it is saved. */
    private void newPin(final Runnable then) {
        pinPrompt(getString(R.string.create_pin), first -> {
            if (!Pin.wellFormed(first)) {
                Toast.makeText(this, R.string.pin_bad, Toast.LENGTH_SHORT).show();
                newPin(then);
                return;
            }
            pinPrompt(getString(R.string.confirm_pin), second -> {
                if (!first.equals(second)) {
                    Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show();
                    newPin(then);
                    return;
                }
                guard.store.setPin(first);
                then.run();
            });
        });
    }

    private interface PinEntered { void entered(String pin); }

    /**
     * The PIN on an on-screen keypad: the TV's own keyboard sends people to their phone. Arrows and OK move through the
     * keys; a remote's number keys and Back/Delete work too. Cancelling closes the app: nothing behind it is reachable
     * without a PIN.
     */
    private void pinPrompt(String title, final PinEntered done) {
        final StringBuilder pin = new StringBuilder();
        final TextView dots = text(" ", 34, Color.WHITE, true);
        dots.setGravity(Gravity.CENTER);
        dots.setLetterSpacing(0.3f);
        dots.setPadding(0, dp(4), 0, dp(16));
        final Runnable show = () -> {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(i < pin.length() ? "●" : i < 4 ? "○" : "");
            dots.setText(b);
        };
        show.run();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        box.setClipChildren(false);
        box.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{SKY_TOP, SKY_BOTTOM}));
        TextView heading = text(title, 20, Color.WHITE, true);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(12), 0, dp(4));
        box.addView(heading);
        box.addView(dots);

        final AlertDialog[] dialog = new AlertDialog[1];
        final Runnable submit = () -> {
            if (pin.length() < 4) return;
            dialog[0].dismiss();
            done.entered(pin.toString());
        };
        final Runnable delete = () -> {
            if (pin.length() > 0) pin.setLength(pin.length() - 1);
            show.run();
        };
        GridLayout keys = new GridLayout(this);
        keys.setColumnCount(3);
        keys.setClipChildren(false);
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        View five = null;
        for (final String label : labels) {
            Runnable action = label.equals("⌫") ? delete : label.equals("OK") ? submit : () -> {
                if (pin.length() < 8) pin.append(label);
                show.run();
            };
            TextView key = pill(label, label.equals("OK") ? MOON : 0x33FFFFFF, label.equals("OK") ? SKY_BOTTOM : Color.WHITE, "key" + label, action);
            key.setTextSize(24);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(84);
            lp.height = dp(60);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            keys.addView(key, lp);
            if (label.equals("5")) five = key;
        }
        box.addView(keys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog[0] = new AlertDialog.Builder(this).setView(box)
                .setOnCancelListener(d -> finish())
                .setOnKeyListener((d, code, e) -> {
                    if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                    if (code >= android.view.KeyEvent.KEYCODE_0 && code <= android.view.KeyEvent.KEYCODE_9) {
                        if (pin.length() < 8) pin.append((char) ('0' + code - android.view.KeyEvent.KEYCODE_0));
                        show.run();
                        return true;
                    }
                    if (code == android.view.KeyEvent.KEYCODE_DEL) {
                        delete.run();
                        return true;
                    }
                    return false;
                })
                .create();
        pinDialog = dialog[0];
        pinDialog.show();
        if (five != null) five.requestFocus();
    }

    // MARK: - Screen

    private void refresh() {
        if (!unlocked) return;
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) focusTag = (String) focused.getTag();
        content.removeAllViews();
        String today = Guard.today();
        boolean kids = guard.store.kidsMode(), ready = guard.serviceEnabled();

        // Header: icon, name and the kids mode switch.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.icon);
        header.addView(logo, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text(getString(R.string.app_name), 30, Color.WHITE, true);
        title.setPadding(dp(16), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView kidsSwitch = pill(getString(kids ? R.string.kids_mode_on : R.string.kids_mode_off), kids ? GREEN : 0x33FFFFFF,
                kids ? SKY_BOTTOM : Color.WHITE, KIDS, () -> {
                    // Kids mode without setup would show limits that nothing enforces: set up first, then turn it on.
                    if (!kids && !guard.serviceEnabled()) setup(() -> setKidsMode(true));
                    else setKidsMode(!kids);
                });
        kidsSwitch.setTextSize(20);
        header.addView(kidsSwitch);
        content.addView(header);
        TextView hint = text(getString(kids ? R.string.kids_hint_on : R.string.kids_hint_off) + " " + whenItChanges(), 16, MUTED, false);
        hint.setPadding(dp(72), dp(4), 0, dp(20));
        content.addView(hint);

        // Without the service nothing is enforced: say so before anything else.
        if (!ready) {
            TextView warning = pill(getString(R.string.setup_needed), CORAL, Color.WHITE, SETUP, () -> setup(null));
            warning.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(20);
            content.addView(warning, lp);
        }

        // The schedule: a button per window, and one to add a window.
        content.addView(sectionTitle(getString(R.string.schedule_title)));
        LinearLayout windows = new LinearLayout(this);
        windows.setClipChildren(false);
        windows.setPadding(0, 0, 0, dp(24));
        List<Schedule.Window> schedule = guard.store.windows();
        for (int i = 0; i < schedule.size(); i++) {
            final int index = i;
            windows.addView(pill(describe(schedule.get(i)), 0x33FFFFFF, Color.WHITE, "window" + i, () -> windowMenu(index)), spaced());
        }
        windows.addView(pill(getString(R.string.schedule_add), 0x1AFFFFFF, MOON, "window+", () -> editWindow(-1)), spaced());
        content.addView(windows);

        // A card per app.
        content.addView(sectionTitle(getString(R.string.apps_title)));
        int available = getResources().getDisplayMetrics().widthPixels - dp(112);
        int columns = Math.max(2, available / dp(200));
        int cardWidth = available / columns - dp(16);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(columns);
        grid.setClipChildren(false);
        for (ResolveInfo app : tvApps()) grid.addView(card(app, today, cardWidth, kids));
        content.addView(grid);
        guard.store.saveBudget(guard.budget);

        // Footer.
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.END);
        footer.setClipChildren(false);
        footer.setPadding(0, dp(24), 0, 0);
        if (ready) footer.addView(pill(getString(R.string.setup_ok), 0x33FFFFFF, Color.WHITE, SETUP, () -> setup(null)));
        TextView pin = pill(getString(R.string.change_pin), 0x33FFFFFF, Color.WHITE, PIN, () -> newPin(() -> {
            Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show();
            refresh();
        }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        footer.addView(pin, lp);
        content.addView(footer);

        View again = focusTag == null ? null : content.findViewWithTag(focusTag);
        (again != null ? again : kidsSwitch).requestFocus();
    }

    /** The app's banner (or icon), its name, its limit and a bar of today's time. */
    private View card(ResolveInfo app, String today, int width, boolean counting) {
        final String pkg = app.activityInfo.packageName;
        PackageManager pm = getPackageManager();
        Integer limit = guard.budget.limits.get(pkg);
        int inner = width - dp(28);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(focusable(CARD, CARD_FOCUS, dp(18)));
        makeFocusable(card, pkg, () -> pickLimit(pkg));

        Drawable banner = app.activityInfo.loadBanner(pm);
        ImageView image = new ImageView(this);
        image.setImageDrawable(banner != null ? banner : app.loadIcon(pm));
        image.setScaleType(banner != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(dp(10));
        image.setBackground(clip);
        image.setClipToOutline(true);
        card.addView(image, new LinearLayout.LayoutParams(inner, inner * 9 / 16));

        TextView name = text(guard.label(pkg), 17, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(10), 0, 0);
        card.addView(name, new LinearLayout.LayoutParams(inner, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (limit == null) {
            card.addView(text(getString(R.string.no_limit), 14, MUTED, false));
        } else {
            long leftMin = (guard.budget.remainingMs(pkg, today) + Budget.MINUTE - 1) / Budget.MINUTE;
            int total = limit + extra(pkg);
            card.addView(text(getString(R.string.minutes, limit), 14, MUTED, false));
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(total);
            bar.setProgress((int) (total - leftMin));
            int color = !counting ? MUTED : leftMin == 0 ? CORAL : leftMin * 4 <= total ? MOON : GREEN;
            bar.setProgressTintList(ColorStateList.valueOf(color));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(inner, dp(8));
            lp.topMargin = dp(8);
            card.addView(bar, lp);
            String state = !counting ? getString(R.string.counts_in_kids_mode)
                    : leftMin == 0 ? getString(R.string.out_today) : getString(R.string.left_today, leftMin);
            card.addView(text(state, 13, color, false));
        }

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = width;
        lp.setMargins(0, 0, dp(16), dp(16));
        card.setLayoutParams(lp);
        return card;
    }

    private int extra(String pkg) {
        Integer extra = guard.budget.extraMin.get(pkg);
        return extra == null ? 0 : extra;
    }

    /** The apps on the TV's launcher, by name; not this app, nor the home screen (limiting it would send home to home). */
    private List<ResolveInfo> tvApps() {
        PackageManager pm = getPackageManager();
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        String homePkg = home != null && home.activityInfo != null ? home.activityInfo.packageName : "";
        List<ResolveInfo> apps = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String p = r.activityInfo.packageName;
            if (p.equals(Guard.PKG) || p.equals(homePkg) || seen.contains(p)) continue;
            seen.add(p);
            apps.add(r);
        }
        Collections.sort(apps, (a, b) -> guard.label(a.activityInfo.packageName).compareToIgnoreCase(guard.label(b.activityInfo.packageName)));
        return apps;
    }

    private void pickLimit(final String pkg) {
        final String today = Guard.today();
        Integer limit = guard.budget.limits.get(pkg);
        String[] items = new String[LIMITS.length + 1];
        items[0] = getString(R.string.no_limit);
        int checked = 0;
        for (int i = 0; i < LIMITS.length; i++) {
            items[i + 1] = getString(R.string.minutes, LIMITS[i]);
            if (limit != null && limit == LIMITS[i]) checked = i + 1;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(guard.label(pkg))
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    guard.budget.setLimit(pkg, which == 0 ? 0 : LIMITS[which - 1]);
                    changed();
                    d.dismiss();
                });
        if (limit != null) dialog.setNeutralButton(R.string.extra_today, (d, w) -> {
            guard.budget.grantExtra(pkg, 30, today);
            changed();
        });
        dialog.show();
    }

    private void changed() {
        guard.store.saveBudget(guard.budget);
        guard.reconcile(null);
        refresh();
    }

    // MARK: - Schedule

    /** The second sentence under the switch: until when the current state holds. */
    private String whenItChanges() {
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", java.util.Locale.getDefault());
        java.time.LocalDateTime manual = guard.store.manualUntil();
        if (manual != null) return getString(R.string.until_manual, manual.format(f));
        java.time.LocalDateTime next = Schedule.nextBoundary(guard.store.windows(), java.time.LocalDateTime.now());
        return next == null ? "" : getString(R.string.until_schedule, next.format(f));
    }

    /** "Mon–Fri · 14:00–20:00": a run of days as a range, otherwise the days one by one. */
    private String describe(Schedule.Window w) {
        java.time.format.TextStyle style = java.time.format.TextStyle.SHORT;
        java.util.Locale locale = java.util.Locale.getDefault();
        String days;
        if (w.days == Schedule.ALL_DAYS) days = getString(R.string.every_day);
        else {
            int first = Integer.numberOfTrailingZeros(w.days), last = 31 - Integer.numberOfLeadingZeros(w.days);
            boolean run = Integer.bitCount(w.days) == last - first + 1 && last > first;
            if (run) days = java.time.DayOfWeek.of(first + 1).getDisplayName(style, locale) + "–" + java.time.DayOfWeek.of(last + 1).getDisplayName(style, locale);
            else {
                StringBuilder b = new StringBuilder();
                for (int d = 0; d < 7; d++) if ((w.days & (1 << d)) != 0) b.append(b.length() > 0 ? ", " : "").append(java.time.DayOfWeek.of(d + 1).getDisplayName(style, locale));
                days = b.toString();
            }
        }
        return days + "  ·  " + clock(w.start) + "–" + clock(w.end);
    }

    private static String clock(int minutes) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void windowMenu(final int index) {
        new AlertDialog.Builder(this).setTitle(describe(guard.store.windows().get(index)))
                .setItems(new String[]{getString(R.string.schedule_edit), getString(R.string.schedule_delete)}, (d, which) -> {
                    if (which == 0) editWindow(index);
                    else {
                        List<Schedule.Window> all = guard.store.windows();
                        all.remove(index);
                        saveWindows(all);
                    }
                })
                .show();
    }

    /** Days, then start, then end; `index` -1 adds a window. */
    private void editWindow(final int index) {
        final List<Schedule.Window> all = guard.store.windows();
        Schedule.Window old = index >= 0 ? all.get(index) : new Schedule.Window(Schedule.WEEKDAYS, 14 * 60, 20 * 60);
        final boolean[] checked = new boolean[7];
        String[] names = new String[7];
        for (int d = 0; d < 7; d++) {
            checked[d] = (old.days & (1 << d)) != 0;
            names[d] = java.time.DayOfWeek.of(d + 1).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault());
        }
        new AlertDialog.Builder(this).setTitle(R.string.schedule_days)
                .setMultiChoiceItems(names, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int days = 0;
                    for (int i = 0; i < 7; i++) if (checked[i]) days |= 1 << i;
                    if (days == 0) {
                        Toast.makeText(this, R.string.schedule_no_days, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final int pickedDays = days;
                    pickTime(R.string.schedule_start, old.start, start -> pickTime(R.string.schedule_end, old.end, end -> {
                        if (start == end) {
                            Toast.makeText(this, R.string.schedule_same, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Schedule.Window window = new Schedule.Window(pickedDays, start, end);
                        if (index >= 0) all.set(index, window);
                        else all.add(window);
                        saveWindows(all);
                    }));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface TimePicked { void picked(int minutes); }

    /** Every half hour in a list: easier with the remote than the clock dial. */
    private void pickTime(int title, int current, final TimePicked done) {
        String[] slots = new String[48];
        for (int i = 0; i < 48; i++) slots[i] = clock(i * 30);
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(slots, Math.min(47, current / 30), (d, which) -> {
                    d.dismiss();
                    done.picked(which * 30);
                })
                .show();
    }

    private void saveWindows(List<Schedule.Window> windows) {
        guard.store.windows(windows);
        android.util.Log.i(Guard.TAG, "schedule saved: " + windows.size() + " window(s), kids mode " + (guard.store.kidsMode() ? "on" : "off"));
        guard.scheduleAlarm();
        guard.reconcile(null);
        refresh();
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        return lp;
    }

    private void setKidsMode(boolean on) {
        android.util.Log.i(Guard.TAG, "kids mode " + (on ? "on" : "off"));
        guard.store.kidsMode(on);
        guard.scheduleAlarm();
        guard.reconcile(null);
        refresh();
    }

    /** Connects to the TV's ADB and turns the service on; `then` runs only if that worked. */
    private void setup(final Runnable then) {
        final AlertDialog waiting = new AlertDialog.Builder(this).setMessage(R.string.setup_connecting).setCancelable(false).show();
        guard.setup(() -> runOnUiThread(() -> waiting.setMessage(getString(R.string.setup_waiting))), error -> {
            waiting.dismiss();
            Toast.makeText(this, error == null ? getString(R.string.setup_done) : getString(R.string.setup_failed, error),
                    Toast.LENGTH_LONG).show();
            if (error == null && then != null) then.run();
            // The service shows up as enabled a moment after the setting changes.
            content.postDelayed(this::refresh, 1500);
        });
    }

    // MARK: - Small view helpers

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s.toUpperCase(), 14, MUTED, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, dp(12));
        return t;
    }

    /** A rounded button; focus shows as the moon's yellow ring. */
    private TextView pill(String s, int fill, int textColor, String tag, Runnable action) {
        TextView t = text(s, 17, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), dp(12), dp(22), dp(12));
        t.setBackground(focusable(fill, fill, dp(28)));
        makeFocusable(t, tag, action);
        return t;
    }

    private Drawable focusable(int normal, int focused, int radius) {
        GradientDrawable off = new GradientDrawable();
        off.setColor(normal);
        off.setCornerRadius(radius);
        GradientDrawable on = new GradientDrawable();
        on.setColor(focused);
        on.setCornerRadius(radius);
        on.setStroke(dp(3), MOON);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, on);
        states.addState(new int[]{}, off);
        return states;
    }

    private void makeFocusable(final View v, String tag, final Runnable action) {
        v.setTag(tag);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(x -> action.run());
        v.setOnFocusChangeListener((x, has) -> {
            float scale = has ? 1.06f : 1f;
            x.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
