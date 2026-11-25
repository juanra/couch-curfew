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
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Two screens. The dashboard opens first, without a PIN, and only shows the usage history. "Limits and settings" asks
 * for the parent PIN and opens a list in the style of a TV's own settings: kids mode, the schedule, a row per app with
 * its limit, setup and the PIN. Built in code (no layouts, no AndroidX) and made for the remote.
 */
public class MainActivity extends Activity {
    /** The minutes offered for an app's daily limit, for the daily total, and for a time frame's total and its apps. */
    private static final int[] AMOUNTS = {15, 30, 45, 60, 90, 120, 180}, TOTAL_AMOUNTS = {30, 45, 60, 90, 120, 180, 240};
    private static final int[] FRAME_TOTALS = {10, 15, 20, 30, 45, 60, 90, 120}, FRAME_AMOUNTS = {10, 15, 20, 30, 45, 60, 90};

    // The icon's night palette.
    static final int SKY_TOP = 0xFF2E3375, SKY_BOTTOM = 0xFF14173A;
    static final int MOON = 0xFFF4C542, CORAL = 0xFFE07A5F, GREEN = 0xFF7BC67E, MUTED = 0xFFB8BCE0;
    /** A settings row: faint on the night sky, near white with dark text when focused. */
    private static final int ROW = 0x1AFFFFFF, ROW_FOCUS = 0xFFF2F2F7, ROW_FOCUS_MUTED = 0xFF4A4F7A;

    static final String SETTINGS = "settings";
    private static final String KIDS = "kids", SETUP = "setup", PIN = "pin";
    /** The screens under the settings; a row that opens one carries its name as its tag. A frame's is FRAME + its index. */
    private static final String SCHEDULE = "schedule", LIMITS = "limits", APPS = "apps", ALLOWED = "allowed", PARENT = "parent", FRAME = "frame";

    private Guard guard;
    private FrameLayout root;
    /** The settings are showing; false is the dashboard. Only true after the PIN. */
    private boolean unlocked;
    /** Which screen of the settings; empty is the first one. */
    private String screen = "";
    /** The dashboard's range, an index into Usage.RANGES. */
    private int range;
    private AlertDialog pinDialog;
    /** Every dialog shown, so leaving the screen closes them: an open limit picker would outlive the lock. */
    private final List<AlertDialog> dialogs = new ArrayList<>();
    /** What had focus before a refresh (a package or one of the tags above), to put it back after. */
    private String focusTag;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        guard = Guard.get(this);
        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{SKY_TOP, SKY_BOTTOM}));
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        refresh();
    }

    /** Leaving the screen locks the settings again, so a kid can't come back to them later. */
    @Override protected void onStop() {
        super.onStop();
        unlocked = false;
        screen = "";
        focusTag = null;
        root.removeAllViews();
        for (AlertDialog dialog : dialogs) dialog.dismiss();
        dialogs.clear();
    }

    /** Back goes up one screen, onto the row that opened it; from the first one, to the dashboard, and locks the settings. */
    @Override public void onBackPressed() {
        if (!unlocked) {
            super.onBackPressed();
            return;
        }
        root.removeAllViews(); // so refresh doesn't put the focus back on the row that had it
        if (screen.isEmpty()) {
            unlocked = false;
            focusTag = SETTINGS;
        } else {
            focusTag = screen;
            screen = screen.startsWith(FRAME) || screen.equals(APPS) ? LIMITS : "";
        }
        refresh();
    }

    private void open(String name) {
        screen = name;
        root.removeAllViews();
        focusTag = null;
        refresh();
    }

    void showRange(int index) {
        range = index;
        focusTag = "range" + index;
        refresh();
    }

    /**
     * From the dashboard: the PIN, then the settings. A wrong or cancelled PIN stays on the dashboard. The first time
     * there is no PIN yet, so this is where the parent creates it.
     */
    void openSettings() {
        if (!guard.store.hasPin()) {
            newPin(this::showSettings, null);
            return;
        }
        long wait = Pin.lockoutMs(guard.store.pinFailures()) - (System.currentTimeMillis() - guard.store.pinLastFailure());
        if (wait > 0) {
            Toast.makeText(this, getString(R.string.pin_wait, (int) (wait / 1000) + 1), Toast.LENGTH_LONG).show();
            return;
        }
        pinPrompt(getString(R.string.enter_pin), pin -> {
            if (guard.store.pinMatches(pin)) {
                guard.store.pinPassed();
                showSettings();
            } else {
                guard.store.pinFailed(System.currentTimeMillis());
                Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_SHORT).show();
                openSettings();
            }
        }, null);
    }

    // MARK: - PIN

    private void showSettings() {
        unlocked = true;
        screen = "";
        root.removeAllViews();
        focusTag = KIDS;
        refresh();
    }

    /** Asks for a new PIN twice; `then` runs once it is saved, `cancelled` (may be null) if the parent backs out. */
    private void newPin(final Runnable then, final Runnable cancelled) {
        pinPrompt(getString(R.string.create_pin), first -> {
            if (!Pin.wellFormed(first)) {
                Toast.makeText(this, R.string.pin_bad, Toast.LENGTH_SHORT).show();
                newPin(then, cancelled);
                return;
            }
            pinPrompt(getString(R.string.confirm_pin), second -> {
                if (!first.equals(second)) {
                    Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show();
                    newPin(then, cancelled);
                    return;
                }
                guard.store.setPin(first);
                then.run();
            }, cancelled);
        }, cancelled);
    }

    private interface PinEntered { void entered(String pin); }

    /**
     * The PIN on an on-screen keypad: the TV's own keyboard sends people to their phone. Arrows and OK move through the
     * keys; a remote's number keys and Back/Delete work too. Cancelling runs `cancelled` (may be null) and leaves what
     * was behind it, which is never more than the dashboard or the settings already unlocked.
     */
    private void pinPrompt(String title, final PinEntered done, final Runnable cancelled) {
        final StringBuilder pin = new StringBuilder();
        final LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(16), 0, dp(20));
        // Four empty dots to start with; a longer PIN adds its own.
        final Runnable show = () -> {
            dots.removeAllViews();
            for (int i = 0; i < Math.max(4, pin.length()); i++) {
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                if (i < pin.length()) dot.setColor(MOON);
                else dot.setStroke(dp(2), MUTED);
                View v = new View(this);
                v.setBackground(dot);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(14));
                lp.setMargins(dp(7), 0, dp(7), 0);
                dots.addView(v, lp);
            }
        };
        show.run();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(16));
        box.setClipChildren(false);
        box.setBackground(panel());
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
                .setOnCancelListener(d -> { if (cancelled != null) cancelled.run(); })
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
        showPanel(pinDialog, 360);
        if (five != null) five.requestFocus();
    }

    // MARK: - Screen

    private void refresh() {
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) focusTag = (String) focused.getTag();
        root.removeAllViews();
        root.addView(unlocked ? settings() : new Dashboard(this, guard).build(range));
        View again = focusTag == null ? null : root.findViewWithTag(focusTag);
        if (again == null && !unlocked) again = root.findViewWithTag("range" + range);
        if (again != null) again.requestFocus();
        else {
            java.util.ArrayList<View> rows = root.getFocusables(View.FOCUS_DOWN);
            if (!rows.isEmpty()) rows.get(0).requestFocus(); // the first row of the screen
        }
    }

    /** The settings: what kids mode is doing on the left, and on the right a list to go down with the remote. */
    private View settings() {
        final boolean kids = guard.store.kidsMode();

        LinearLayout page = new LinearLayout(this);
        page.setClipChildren(false);

        // Left: the icon, the name and what kids mode is doing now. It stays put while the list scrolls.
        LinearLayout about = new LinearLayout(this);
        about.setOrientation(LinearLayout.VERTICAL);
        about.setGravity(Gravity.CENTER);
        about.setPadding(dp(56), dp(36), dp(24), dp(36));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.icon);
        about.addView(logo, new LinearLayout.LayoutParams(dp(128), dp(128)));
        TextView title = text(getString(R.string.app_name), 30, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, dp(10));
        about.addView(title);
        TextView hint = text(getString(kids ? R.string.kids_hint_on : R.string.kids_hint_off) + " " + whenItChanges(), 16, MUTED, false);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setMinLines(3); // the text changes with kids mode; the block above must not jump
        about.addView(hint, new LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(about, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(24), dp(36), dp(56), dp(36));
        list.setClipChildren(false);
        list.setClipToPadding(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));

        Budget.Frame frame = null;
        if (screen.startsWith(FRAME)) {
            int index = Integer.parseInt(screen.substring(FRAME.length()));
            if (index < guard.budget.frames.size()) frame = guard.budget.frames.get(index);
            else screen = LIMITS; // the frame is gone
        }
        if (frame != null) frameRows(list, frame, kids);
        else if (screen.equals(SCHEDULE)) scheduleRows(list);
        else if (screen.equals(LIMITS)) limitsRows(list, kids);
        else if (screen.equals(APPS)) appsRows(list, kids);
        else if (screen.equals(ALLOWED)) allowedRows(list);
        else if (screen.equals(PARENT)) parentRows(list);
        else mainRows(list, kids);
        guard.store.saveBudget(guard.budget); // reading today's numbers may have started a new day
        return page;
    }

    /** The first screen: kids mode, and a row into each group of settings showing where it stands. */
    private void mainRows(LinearLayout list, final boolean kids) {
        boolean ready = guard.serviceEnabled();
        int allowed = guard.budget.allowed.size();
        // Without the service nothing is enforced or recorded: say so before anything else.
        if (!ready) list.addView(row(SETUP, null, getString(R.string.setup_needed), "", CORAL, () -> setup(null)));
        list.addView(row(KIDS, null, getString(R.string.kids_mode), getString(kids ? R.string.state_on : R.string.state_off),
                kids ? GREEN : MUTED, () -> {
                    // Kids mode without setup would show limits that nothing enforces: set up first, then turn it on.
                    if (!kids && !guard.serviceEnabled()) setup(() -> setKidsMode(true));
                    else setKidsMode(!kids);
                }));
        if (kids) list.addView(row("more", null, getString(R.string.more_time), "›", MUTED, this::moreTime));
        list.addView(row(SCHEDULE, null, getString(R.string.schedule_title), scheduleText(), MUTED, () -> open(SCHEDULE)));
        list.addView(row(LIMITS, null, getString(R.string.limits_title), limitsText(), MUTED, () -> open(LIMITS)));
        list.addView(row(ALLOWED, null, getString(R.string.always_allowed), allowed == 0 ? "›" : String.valueOf(allowed), MUTED, () -> open(ALLOWED)));
        list.addView(row("guideline", null, getString(R.string.guideline_title), guidelineText(), MUTED, this::pickGuideline));
        list.addView(row(PARENT, null, getString(R.string.parent_setup), "›", MUTED, () -> open(PARENT)));
    }

    private void parentRows(LinearLayout list) {
        list.addView(listTitle(getString(R.string.parent_setup)));
        list.addView(row(PIN, null, getString(R.string.change_pin), "›", MUTED, () -> newPin(() -> {
            Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show();
            refresh();
        }, null)));
        boolean ready = guard.serviceEnabled();
        list.addView(row(SETUP, null, getString(ready ? R.string.setup_ok : R.string.setup_needed), "›", ready ? MUTED : CORAL, () -> setup(null)));
    }

    /** "Mon–Fri · 14:00–20:00", with "+1" when there are more windows. */
    private String scheduleText() {
        List<Schedule.Window> windows = guard.store.windows();
        if (windows.isEmpty()) return getString(R.string.state_off);
        Schedule.Window w = windows.get(0);
        return describe(w.days, w.start, w.end) + (windows.size() > 1 ? "  +" + (windows.size() - 1) : "");
    }

    /** The limits that are on: "2 h · Julieta, Tiago · App limits". */
    private String limitsText() {
        Budget budget = guard.budget;
        List<String> parts = new ArrayList<>();
        Integer total = budget.limit(Budget.TOTAL, Guard.today());
        if (total != null) parts.add(duration(total * Budget.MINUTE));
        if (budget.framesOn && !budget.frames.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Budget.Frame f : budget.frames) names.add(f.name);
            parts.add(String.join(", ", names));
        }
        if (budget.appsOn && !budget.limits.isEmpty()) parts.add(getString(R.string.app_limits));
        return parts.isEmpty() ? getString(R.string.state_off) : String.join("  ·  ", parts);
    }

    private void scheduleRows(LinearLayout list) {
        list.addView(listTitle(getString(R.string.schedule_title)));
        List<Schedule.Window> schedule = guard.store.windows();
        for (int i = 0; i < schedule.size(); i++) {
            final int index = i;
            Schedule.Window w = schedule.get(i);
            list.addView(row("window" + i, null, describe(w.days, w.start, w.end), "›", MUTED, () -> windowMenu(index)));
        }
        list.addView(row("window+", null, getString(R.string.schedule_add), "", MUTED, () -> editWindow(-1)));
    }

    /** The three kinds of limit. Each one can be off and keeps what was set. */
    private void limitsRows(LinearLayout list, boolean kids) {
        final Budget budget = guard.budget;
        String today = Guard.today();
        list.addView(listTitle(getString(R.string.limits_title)));
        list.addView(row(Budget.TOTAL, null, getString(R.string.daily_total), limitText(Budget.TOTAL, today, kids), MUTED, () -> pickLimit(Budget.TOTAL)));

        list.addView(listTitle(getString(R.string.frames_title)));
        list.addView(note(getString(R.string.frame_note)));
        if (!budget.frames.isEmpty()) list.addView(toggle("framesOn", getString(R.string.frames_title), budget.framesOn, () -> budget.framesOn = !budget.framesOn));
        for (int i = 0; i < budget.frames.size(); i++) {
            final String name = FRAME + i;
            Budget.Frame f = budget.frames.get(i);
            String total = f.total < 0 ? "" : "  ·  " + duration(f.total * Budget.MINUTE);
            list.addView(row(name, null, f.name, describe(f.days, f.start, f.end) + total, MUTED, () -> open(name)));
        }
        list.addView(row("frame+", null, getString(R.string.frame_add), "", MUTED, this::addFrame));

        list.addView(listTitle(getString(R.string.app_limits)));
        String apps = getString(budget.appsOn ? R.string.state_on : R.string.state_off);
        list.addView(row(APPS, null, getString(R.string.app_limits), apps, MUTED, () -> open(APPS)));
    }

    private void appsRows(LinearLayout list, boolean kids) {
        final Budget budget = guard.budget;
        String today = Guard.today();
        list.addView(listTitle(getString(R.string.app_limits)));
        list.addView(note(getString(R.string.app_limits_note)));
        list.addView(toggle("appsOn", getString(R.string.app_limits), budget.appsOn, () -> budget.appsOn = !budget.appsOn));
        list.addView(listTitle(getString(R.string.apps_title)));
        PackageManager pm = getPackageManager();
        for (ResolveInfo app : tvApps()) {
            final String pkg = app.activityInfo.packageName;
            if (budget.allowed.contains(pkg)) continue;
            list.addView(row(pkg, app.loadIcon(pm), guard.label(pkg), limitText(pkg, today, kids), MUTED, () -> pickLimit(pkg)));
        }
    }

    private void allowedRows(LinearLayout list) {
        final Budget budget = guard.budget;
        list.addView(listTitle(getString(R.string.always_allowed)));
        list.addView(note(getString(R.string.always_allowed_note)));
        PackageManager pm = getPackageManager();
        for (ResolveInfo app : tvApps()) {
            final String pkg = app.activityInfo.packageName;
            final boolean on = budget.allowed.contains(pkg);
            list.addView(row(pkg, app.loadIcon(pm), guard.label(pkg), on ? "●" : "", MOON, () -> {
                budget.setAllowed(pkg, !on);
                changed();
            }));
        }
    }

    /** One time frame: its name, when it runs, its total, and a limit per app that only holds inside it. */
    private void frameRows(LinearLayout list, final Budget.Frame f, boolean kids) {
        final Budget budget = guard.budget;
        list.addView(listTitle(f.name));
        list.addView(row("name", null, getString(R.string.frame_name), f.name, MUTED, () -> pickName(name -> {
            f.name = name;
            changed();
        })));
        list.addView(row("hours", null, getString(R.string.frame_hours), describe(f.days, f.start, f.end), MUTED, () -> editFrame(f, false)));
        String total = getString(R.string.state_off);
        if (f.total >= 0) {
            total = duration(f.total * Budget.MINUTE);
            // The countdown only while this frame is the one running.
            if (budget.frameAt(Guard.today(), Guard.minute()) == f) {
                total += "  ·  " + (kids ? left(f.totalRemainingMs()) : getString(R.string.counts_in_kids_mode));
            }
        }
        list.addView(row("total", null, getString(R.string.frame_total), total, MUTED, () -> pickMinutes(f.name + "  ·  " + getString(R.string.frame_total),
                new int[]{Budget.NO_LIMIT}, new String[]{getString(R.string.state_off)}, FRAME_TOTALS, f.total, minutes -> {
                    f.total = minutes;
                    changed();
                })));
        list.addView(row("delete", null, getString(R.string.frame_delete), "", CORAL, () ->
                choose(f.name, new String[]{getString(R.string.frame_delete), getString(android.R.string.cancel)}, -1, which -> {
                    if (which != 0) return;
                    budget.frames.remove(f);
                    screen = LIMITS;
                    root.removeAllViews();
                    focusTag = "frame+";
                    changed();
                })));

        list.addView(listTitle(getString(R.string.frame_apps)));
        PackageManager pm = getPackageManager();
        for (ResolveInfo app : tvApps()) {
            final String pkg = app.activityInfo.packageName;
            if (budget.allowed.contains(pkg)) continue;
            final Integer limit = f.limits.get(pkg);
            String value = limit == null ? "" : limit == 0 ? getString(R.string.not_allowed) : duration(limit * Budget.MINUTE);
            list.addView(row(pkg, app.loadIcon(pm), guard.label(pkg), value, MUTED, () -> pickMinutes(guard.label(pkg) + "  ·  " + f.name,
                    new int[]{Budget.NO_LIMIT, 0}, new String[]{getString(R.string.no_limit), getString(R.string.not_allowed)}, FRAME_AMOUNTS,
                    limit == null ? Budget.NO_LIMIT : limit, minutes -> {
                        if (minutes < 0) f.limits.remove(pkg);
                        else f.limits.put(pkg, minutes);
                        changed();
                    })));
        }
    }

    /** "12 min left", or that time is up. */
    private String left(long ms) {
        long minutes = (ms + Budget.MINUTE - 1) / Budget.MINUTE;
        return minutes == 0 ? getString(R.string.time_up) : getString(R.string.minutes_left, minutes);
    }

    /** A row that switches something on or off in place. */
    private View toggle(String tag, String label, boolean on, final Runnable flip) {
        return row(tag, null, label, getString(on ? R.string.state_on : R.string.state_off), on ? GREEN : MUTED, () -> {
            flip.run();
            changed();
        });
    }

    /** A line of context under a section title. */
    private TextView note(String s) {
        TextView t = text(s, 14, MUTED, false);
        t.setPadding(dp(18), 0, dp(18), dp(12));
        return t;
    }

    // MARK: - Time frames

    private void addFrame() {
        if (guard.budget.frames.size() >= Budget.MAX_FRAMES) {
            Toast.makeText(this, getString(R.string.frame_max, Budget.MAX_FRAMES), Toast.LENGTH_SHORT).show();
            return;
        }
        pickName(name -> editFrame(new Budget.Frame(name, Budget.WEEKDAYS, 6 * 60, 12 * 60), true));
    }

    private interface Named { void named(String name); }

    /** A few names that fit most homes, or one typed with the TV's keyboard: a kid's name says who the frame is for. */
    private void pickName(final Named done) {
        final String[] items = {getString(R.string.frame_morning), getString(R.string.frame_afternoon), getString(R.string.frame_evening),
                getString(R.string.frame_name_type)};
        choose(getString(R.string.frame_name), items, -1, which -> {
            if (which < items.length - 1) {
                done.named(items[which]);
                return;
            }
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setSingleLine(true);
            input.setPadding(dp(24), dp(8), dp(24), dp(12));
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(20)});
            dialogs.add(new AlertDialog.Builder(this).setTitle(R.string.frame_name).setView(input)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String name = input.getText().toString().trim();
                        if (!name.isEmpty()) done.named(name);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        });
    }

    /** Days, then start, then end. Refused if it would overlap another frame: two counts at once is what frames avoid. */
    private void editFrame(final Budget.Frame f, final boolean isNew) {
        final Budget budget = guard.budget;
        pickDays(f.days, days -> pickTime(R.string.frame_start, f.start, 0, start -> pickTime(R.string.frame_end, f.end, 30, end -> {
            Budget.Frame candidate = new Budget.Frame(f.name, days, start, end), other = budget.overlapping(candidate, f);
            if (!candidate.valid() || other != null) {
                Toast.makeText(this, other == null ? getString(R.string.frame_order) : getString(R.string.frame_overlap, other.name), Toast.LENGTH_LONG).show();
                return;
            }
            f.days = days;
            f.start = start;
            f.end = end;
            if (isNew) {
                budget.frames.add(f);
                screen = FRAME + (budget.frames.size() - 1);
                root.removeAllViews();
                focusTag = null;
            }
            changed();
        })));
    }

    // MARK: - More time

    /**
     * More time for today, for an app that some limit holds back: the ones out of time first. One place for it,
     * whatever limit ran out, because the parent comes here from a kid who is blocked, not from a setting.
     */
    private void moreTime() {
        final String today = Guard.today();
        final Budget budget = guard.budget;
        final List<String> pkgs = new ArrayList<>(), values = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (ResolveInfo app : tvApps()) {
                String pkg = app.activityInfo.packageName;
                long left = budget.remainingMs(pkg, today, Guard.minute());
                if (left == Long.MAX_VALUE || (left == 0) != (pass == 0)) continue;
                pkgs.add(pkg);
                values.add(left(left));
            }
        }
        if (pkgs.isEmpty()) {
            Toast.makeText(this, R.string.more_time_none, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[pkgs.size()];
        for (int i = 0; i < items.length; i++) items[i] = guard.label(pkgs.get(i));
        choose(getString(R.string.more_time), getString(R.string.more_time_note), items, values.toArray(new String[0]), -1, 0, picked -> {
            final int[] more = {15, 30, 60};
            String[] amounts = new String[more.length];
            for (int i = 0; i < more.length; i++) amounts[i] = getString(R.string.plus_minutes, more[i]);
            choose(items[picked], amounts, -1, which -> {
                budget.grantExtra(pkgs.get(picked), more[which], today, Guard.minute());
                changed();
            });
        });
    }

    // MARK: - The daily guideline

    private static final int[] AGES = {R.string.age_under2, R.string.age_2to4, R.string.age_5to17};

    /** "5 to 17 years · 2 h a day", or what was chosen instead of an age. */
    private String guidelineText() {
        String band = guard.store.guideline();
        int age = java.util.Arrays.asList(Usage.BANDS).indexOf(band), minutes = Usage.guidelineMinutes(band);
        if (age < 0) return getString(band.isEmpty() ? R.string.guideline_unset : R.string.state_off);
        return minutes == 0 ? getString(AGES[age]) : getString(R.string.guideline_value, getString(AGES[age]), duration(minutes * Budget.MINUTE));
    }

    /** The age of the youngest child decides the guideline the dashboard compares kids-mode days with. */
    private void pickGuideline() {
        String[] items = new String[AGES.length + 1];
        for (int i = 0; i < AGES.length; i++) items[i] = getString(AGES[i]);
        items[AGES.length] = getString(R.string.guideline_off);
        String band = guard.store.guideline();
        int checked = band.equals("off") ? AGES.length : java.util.Arrays.asList(Usage.BANDS).indexOf(band);
        choose(getString(R.string.guideline_age), items, checked, picked -> {
            guard.store.guideline(picked < AGES.length ? Usage.BANDS[picked] : "off");
            refresh();
        });
    }

    /**
     * Under a limit list: what a daily total counts, the guideline for the whole day and, for an app, what the other
     * apps already allow on those days (`days` 0 leaves that out). Null when there is nothing to say.
     */
    private String limitNote(String subject, int days) {
        boolean total = Budget.TOTAL.equals(subject);
        String band = guard.store.guideline(), own = total ? getString(R.string.total_note) : null;
        int minutes = Usage.guidelineMinutes(band), others = 0;
        if (minutes == 0) return own;
        String note = getString(R.string.limit_note, getString(AGES[java.util.Arrays.asList(Usage.BANDS).indexOf(band)]), duration(minutes * Budget.MINUTE));
        if (total) return own + " " + note;
        if (days != 0) for (String other : guard.budget.limits.keySet()) {
            if (!other.equals(subject)) others += Math.max(0, guard.budget.limitOn(other, days == Budget.WEEKEND ? 5 : 0));
        }
        return others == 0 ? note : note + " " + getString(R.string.limit_note_others, duration(others * Budget.MINUTE));
    }

    /** A section title over rows: in line with their text, and closer to its own rows than to the ones above. */
    private TextView listTitle(String s) {
        TextView t = sectionTitle(s);
        t.setPadding(dp(18), dp(20), 0, dp(8));
        return t;
    }

    /** "60 min a day · 12 min left today": a daily limit (an app's, or the total's) and where today stands. */
    private String limitText(String subject, String today, boolean counting) {
        Budget budget = guard.budget;
        String none = getString(Budget.TOTAL.equals(subject) ? R.string.state_off : R.string.no_limit);
        if (budget.limitOn(subject, 0) < 0 && budget.limitOn(subject, 5) < 0) return none;
        // With different limits by day, say which days today's one belongs to.
        String group = days(Budget.weekday(today) < 5 ? Budget.WEEKDAYS : Budget.WEEKEND), days = budget.split(subject) ? group + "  ·  " : "";
        Integer limit = budget.limit(subject, today);
        if (limit == null) return days + none;
        long leftMs = budget.ownRemainingMs(subject, today), leftMin = (leftMs + Budget.MINUTE - 1) / Budget.MINUTE;
        if (limit == 0 && leftMs == 0) return days + getString(R.string.not_allowed);
        String state = !counting ? getString(R.string.counts_in_kids_mode) : leftMin == 0 ? getString(R.string.out_today) : getString(R.string.left_today, leftMin);
        // "Mon–Fri 30 min" already says it is by day; the longer "30 min a day" would push the name out of the row.
        String amount = limit == 0 ? getString(R.string.not_allowed) : days.isEmpty() ? getString(R.string.minutes, limit)
                : group + " " + getString(R.string.duration_m, limit);
        return (limit == 0 ? days : "") + amount + "  ·  " + state;
    }

    /** One line of the list: an optional icon, the name on the left and its value on the right. */
    private View row(String tag, Drawable icon, String label, String value, int valueColor, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(12), dp(18), dp(12));
        row.setMinimumHeight(dp(56));
        row.setBackground(focusable(ROW, ROW_FOCUS, dp(14)));
        makeFocusable(row, tag, action, 1.03f);
        if (icon != null) {
            ImageView image = new ImageView(this);
            image.setImageDrawable(icon);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
            lp.rightMargin = dp(14);
            row.addView(image, lp);
        }
        TextView name = text(label, 18, Color.WHITE, true);
        name.setTextColor(onFocus(Color.WHITE, SKY_BOTTOM));
        name.setDuplicateParentStateEnabled(true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView side = text(value, value.equals("›") ? 22 : 16, valueColor, false);
        side.setSingleLine(true);
        side.setEllipsize(android.text.TextUtils.TruncateAt.END);
        side.setMaxWidth(dp(380));
        // On the white focused row the state keeps its meaning, in a shade that reads on white.
        side.setTextColor(onFocus(valueColor, valueColor == GREEN ? 0xFF2E7D32 : valueColor == CORAL ? 0xFFB5482F : ROW_FOCUS_MUTED));
        side.setDuplicateParentStateEnabled(true);
        side.setPadding(dp(16), 0, 0, 0);
        row.addView(side);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private static ColorStateList onFocus(int normal, int focused) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{focused, normal});
    }

    /** The apps a limit can be set for, by name. */
    private List<ResolveInfo> tvApps() {
        List<ResolveInfo> apps = guard.tvApps();
        Collections.sort(apps, (a, b) -> guard.label(a.activityInfo.packageName).compareToIgnoreCase(guard.label(b.activityInfo.packageName)));
        return apps;
    }

    /** "Mon–Fri" or "Sat–Sun", in the TV's language. */
    private static String days(int group) {
        java.util.Locale locale = java.util.Locale.getDefault();
        boolean week = group == Budget.WEEKDAYS;
        return java.time.DayOfWeek.of(week ? 1 : 6).getDisplayName(java.time.format.TextStyle.SHORT, locale) + "–"
                + java.time.DayOfWeek.of(week ? 5 : 7).getDisplayName(java.time.format.TextStyle.SHORT, locale);
    }

    /** "No limit" ("Off" for the total), "Not allowed in kids mode" or "30 min a day". */
    private String limitName(String subject, int minutes) {
        if (minutes < 0) return getString(Budget.TOTAL.equals(subject) ? R.string.state_off : R.string.no_limit);
        return minutes == 0 ? getString(R.string.not_allowed) : getString(R.string.minutes, minutes);
    }

    private String subjectName(String subject) {
        return Budget.TOTAL.equals(subject) ? getString(R.string.daily_total) : guard.label(subject);
    }

    /**
     * A daily limit: an app's, or the total's under Budget.TOTAL. With one limit for every day: a way into different
     * limits for the weekend, then the limits to pick from. With different limits already: the two groups of days,
     * each opening its own list.
     */
    private void pickLimit(final String subject) {
        pickLimit(subject, -1);
    }

    /** `focus` is the row to start on in the two-group panel, -1 for the first. */
    private void pickLimit(final String subject, int focus) {
        final Budget budget = guard.budget;
        if (!budget.split(subject) && focus < 0) {
            pickLimit(subject, Budget.ALL_DAYS, -1);
            return;
        }
        String[] items = {days(Budget.WEEKDAYS), days(Budget.WEEKEND), getString(R.string.same_every_day)};
        String[] values = {limitName(subject, budget.limitOn(subject, 0)), limitName(subject, budget.limitOn(subject, 5)), null};
        choose(subjectName(subject), limitNote(subject, 0), items, values, -1, Math.max(0, focus), which -> {
            if (which == 2) {
                budget.setLimit(subject, budget.limitOn(subject, 0));
                changed();
            } else pickLimit(subject, which == 0 ? Budget.WEEKDAYS : Budget.WEEKEND, 1); // back on the weekend row: next to set, or the one just set
        });
    }

    /**
     * The list of limits for `days`. For every day it starts with "Different on weekends"; the first choice of a group
     * seeds the other with what there was, so only the one that differs needs setting. `next` is the row of the
     * two-group panel to come back to, -1 to close.
     */
    private void pickLimit(final String subject, final int days, final int next) {
        final boolean total = Budget.TOTAL.equals(subject);
        final int[] amounts = total ? TOTAL_AMOUNTS : AMOUNTS;
        // Before the amounts: "Different on weekends", no limit and, for an app, not allowed.
        final int n = days == Budget.ALL_DAYS ? 1 : 0, fixed = total ? 1 : 2;
        int current = guard.budget.limitOn(subject, days == Budget.WEEKEND ? 5 : 0);
        String[] items = new String[amounts.length + fixed + n];
        if (n == 1) items[0] = getString(R.string.different_weekends);
        items[n] = limitName(subject, Budget.NO_LIMIT);
        if (!total) items[n + 1] = getString(R.string.not_allowed);
        int checked = current < 0 ? n : current == 0 && !total ? n + 1 : -1;
        for (int i = 0; i < amounts.length; i++) {
            items[i + fixed + n] = getString(R.string.minutes, amounts[i]);
            if (current == amounts[i]) checked = i + fixed + n;
        }
        String title = subjectName(subject) + (days == Budget.ALL_DAYS ? "" : "  ·  " + days(days));
        choose(title, limitNote(subject, days), items, null, checked, checked, picked -> {
            int which = picked - n;
            if (which < 0) {
                pickLimit(subject, 0); // "Different on weekends": the two groups, starting from the same limit
                return;
            }
            guard.budget.setLimit(subject, days, which == 0 ? Budget.NO_LIMIT : which < fixed ? 0 : amounts[which - fixed]);
            changed();
            if (next >= 0) pickLimit(subject, next);
        });
    }

    /** A number of minutes from a list; `fixed` are the choices before the amounts, such as off or not allowed. */
    private void pickMinutes(String title, final int[] fixed, String[] fixedNames, final int[] amounts, int current, final Chosen done) {
        String[] items = new String[fixed.length + amounts.length];
        int checked = -1;
        for (int i = 0; i < items.length; i++) {
            int minutes = i < fixed.length ? fixed[i] : amounts[i - fixed.length];
            items[i] = i < fixed.length ? fixedNames[i] : duration(minutes * Budget.MINUTE);
            if (minutes == current) checked = i;
        }
        choose(title, items, checked, which -> done.chosen(which < fixed.length ? fixed[which] : amounts[which - fixed.length]));
    }

    private interface Chosen { void chosen(int which); }

    private void choose(String title, String[] items, int checked, final Chosen done) {
        choose(title, null, items, null, checked, checked, done);
    }

    /**
     * A list to pick one item from, in the app's own look. `checked` (or -1) carries a dot, `focus` (or -1 for the
     * top) starts with the focus, `values` (or null) puts each row's current value on its right, and `note` (or null)
     * is a line of context under the title. Back closes it without choosing.
     */
    private void choose(String title, String note, String[] items, String[] values, int checked, int focus, final Chosen done) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(20), dp(8), dp(12));
        box.setBackground(panel());
        TextView heading = text(title, 20, Color.WHITE, true);
        heading.setPadding(dp(30), 0, dp(30), dp(note == null ? 14 : 6));
        box.addView(heading);
        if (note != null) {
            TextView context = text(note, 14, MUTED, false);
            context.setPadding(dp(30), 0, dp(30), dp(12));
            box.addView(context);
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), 0);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        final AlertDialog[] dialog = new AlertDialog[1];
        View first = null;
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            String value = values != null && values[i] != null ? values[i] : i == checked ? "●" : "";
            View item = row("choice" + i, null, items[i], value, value.equals("●") ? MOON : MUTED, () -> {
                dialog[0].dismiss();
                done.chosen(which);
            });
            list.addView(item);
            if (first == null || i == focus) first = item;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        // Six rows at most (five under a note), so a long list scrolls inside the panel instead of touching the screen's edges.
        int rows = note == null ? 6 : 5;
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, items.length > rows ? dp(rows * 64 + 4) : ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog[0] = new AlertDialog.Builder(this).setView(box).create();
        showPanel(dialog[0], 440);
        if (first != null) first.requestFocus();
    }

    /** The night sky with round corners: what the app's own dialogs sit on. */
    private Drawable panel() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{SKY_TOP, SKY_BOTTOM});
        d.setCornerRadius(dp(20));
        d.setStroke(dp(1), 0x33FFFFFF);
        return d;
    }

    /** Shows a dialog whose view draws its own `panel()`: no stock background behind it, and no wider than it needs. */
    private void showPanel(AlertDialog dialog, int widthDp) {
        dialogs.add(dialog);
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** A rule changed: a time frame's hours are among the moments the alarm wakes up for. */
    private void changed() {
        guard.store.saveBudget(guard.budget);
        guard.scheduleAlarm();
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
    private String describe(int mask, int start, int end) {
        java.time.format.TextStyle style = java.time.format.TextStyle.SHORT;
        java.util.Locale locale = java.util.Locale.getDefault();
        String days;
        if (mask == Schedule.ALL_DAYS) days = getString(R.string.every_day);
        else {
            int first = Integer.numberOfTrailingZeros(mask), last = 31 - Integer.numberOfLeadingZeros(mask);
            boolean run = Integer.bitCount(mask) == last - first + 1 && last > first;
            if (run) days = java.time.DayOfWeek.of(first + 1).getDisplayName(style, locale) + "–" + java.time.DayOfWeek.of(last + 1).getDisplayName(style, locale);
            else {
                StringBuilder b = new StringBuilder();
                for (int d = 0; d < 7; d++) if ((mask & (1 << d)) != 0) b.append(b.length() > 0 ? ", " : "").append(java.time.DayOfWeek.of(d + 1).getDisplayName(style, locale));
                days = b.toString();
            }
        }
        return days + "  ·  " + clock(start) + "–" + clock(end);
    }

    static String clock(int minutes) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void windowMenu(final int index) {
        Schedule.Window shown = guard.store.windows().get(index);
        choose(describe(shown.days, shown.start, shown.end), new String[]{getString(R.string.schedule_edit), getString(R.string.schedule_delete)}, -1, which -> {
            if (which == 0) editWindow(index);
            else {
                List<Schedule.Window> all = guard.store.windows();
                all.remove(index);
                saveWindows(all);
            }
        });
    }

    /** Days, then start, then end; `index` -1 adds a window. */
    private void editWindow(final int index) {
        final List<Schedule.Window> all = guard.store.windows();
        final Schedule.Window old = index >= 0 ? all.get(index) : new Schedule.Window(Schedule.WEEKDAYS, 14 * 60, 20 * 60);
        pickDays(old.days, days -> pickTime(R.string.schedule_start, old.start, 0, start -> pickTime(R.string.schedule_end, old.end, 0, end -> {
            if (start == end) {
                Toast.makeText(this, R.string.schedule_same, Toast.LENGTH_SHORT).show();
                return;
            }
            Schedule.Window window = new Schedule.Window(days, start, end);
            if (index >= 0) all.set(index, window);
            else all.add(window);
            saveWindows(all);
        })));
    }

    /** The days of the week to tick, for a schedule window or a time frame. */
    private void pickDays(int current, final Chosen done) {
        final boolean[] checked = new boolean[7];
        String[] names = new String[7];
        for (int d = 0; d < 7; d++) {
            checked[d] = (current & (1 << d)) != 0;
            names[d] = java.time.DayOfWeek.of(d + 1).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault());
        }
        dialogs.add(new AlertDialog.Builder(this).setTitle(R.string.schedule_days)
                .setMultiChoiceItems(names, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int days = 0;
                    for (int i = 0; i < 7; i++) if (checked[i]) days |= 1 << i;
                    if (days == 0) Toast.makeText(this, R.string.schedule_no_days, Toast.LENGTH_SHORT).show();
                    else done.chosen(days);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    /** Every half hour in a list, from `first` (30 for an end that can be midnight): easier with the remote than the clock dial. */
    private void pickTime(int title, int current, final int first, final Chosen done) {
        String[] slots = new String[48];
        for (int i = 0; i < 48; i++) slots[i] = clock(first + i * 30);
        choose(getString(title), slots, Math.max(0, Math.min(47, (current - first) / 30)), which -> done.chosen(first + which * 30));
    }

    private void saveWindows(List<Schedule.Window> windows) {
        guard.store.windows(windows);
        android.util.Log.i(Guard.TAG, "schedule saved: " + windows.size() + " window(s), kids mode " + (guard.store.kidsMode() ? "on" : "off"));
        guard.scheduleAlarm();
        guard.reconcile(null);
        refresh();
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
            root.postDelayed(this::refresh, 1500);
        });
    }

    // MARK: - Small view helpers

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    TextView sectionTitle(String s) {
        TextView t = text(s.toUpperCase(), 14, MUTED, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, dp(12), 0, dp(12));
        return t;
    }

    /** "2 h 15 min", or "15 min" under an hour. Whole hours and totals of 100 h or more drop the minutes: "9 h". */
    String duration(long ms) {
        long minutes = Math.round(ms / (double) Budget.MINUTE);
        if (minutes < 60) return getString(R.string.duration_m, (int) minutes);
        if (minutes >= 6000) return getString(R.string.duration_h, (int) Math.round(minutes / 60.0));
        return minutes % 60 == 0 ? getString(R.string.duration_h, (int) (minutes / 60)) : getString(R.string.duration_hm, (int) (minutes / 60), (int) (minutes % 60));
    }

    /** A rounded button; with the focus it turns white with dark text, like a settings row. */
    TextView pill(String s, int fill, int textColor, String tag, Runnable action) {
        TextView t = text(s, 17, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), dp(12), dp(22), dp(12));
        t.setTextColor(onFocus(textColor, SKY_BOTTOM));
        t.setBackground(focusable(fill, ROW_FOCUS, dp(28)));
        makeFocusable(t, tag, action, 1.06f);
        return t;
    }

    private Drawable focusable(int normal, int focused, int radius) {
        GradientDrawable off = new GradientDrawable();
        off.setColor(normal);
        off.setCornerRadius(radius);
        GradientDrawable on = new GradientDrawable();
        on.setColor(focused);
        on.setCornerRadius(radius);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, on);
        states.addState(new int[]{}, off);
        return states;
    }

    private void makeFocusable(final View v, String tag, final Runnable action, final float grow) {
        v.setTag(tag);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(x -> action.run());
        v.setOnFocusChangeListener((x, has) -> {
            float scale = has ? grow : 1f;
            x.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
