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
    private static final int[] LIMITS = {15, 30, 45, 60, 90, 120, 180};

    // The icon's night palette.
    static final int SKY_TOP = 0xFF2E3375, SKY_BOTTOM = 0xFF14173A;
    static final int MOON = 0xFFF4C542, CORAL = 0xFFE07A5F, GREEN = 0xFF7BC67E, MUTED = 0xFFB8BCE0;
    /** A settings row: faint on the night sky, near white with dark text when focused. */
    private static final int ROW = 0x1AFFFFFF, ROW_FOCUS = 0xFFF2F2F7, ROW_FOCUS_MUTED = 0xFF4A4F7A;

    static final String SETTINGS = "settings";
    private static final String KIDS = "kids", SETUP = "setup", PIN = "pin";

    private Guard guard;
    private FrameLayout root;
    /** The settings are showing; false is the dashboard. Only true after the PIN. */
    private boolean unlocked;
    /** The dashboard's range, an index into Usage.RANGES. */
    private int range;
    private AlertDialog pinDialog;
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
        focusTag = null;
        root.removeAllViews();
        if (pinDialog != null) pinDialog.dismiss();
    }

    /** Back from the settings goes to the dashboard, and locks them. */
    @Override public void onBackPressed() {
        if (!unlocked) {
            super.onBackPressed();
            return;
        }
        unlocked = false;
        root.removeAllViews(); // so refresh doesn't put the focus back on the row that had it
        focusTag = SETTINGS;
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
        if (again == null) again = root.findViewWithTag(unlocked ? KIDS : "range" + range);
        if (again != null) again.requestFocus();
    }

    /** The settings: what kids mode is doing on the left, and on the right a list to go down with the remote. */
    private View settings() {
        String today = Guard.today();
        final boolean kids = guard.store.kidsMode(), ready = guard.serviceEnabled();

        LinearLayout screen = new LinearLayout(this);
        screen.setClipChildren(false);

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
        screen.addView(about, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(24), dp(36), dp(56), dp(36));
        list.setClipChildren(false);
        list.setClipToPadding(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(list);
        screen.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));

        // Without the service nothing is enforced or recorded: say so before anything else.
        if (!ready) list.addView(row(SETUP, null, getString(R.string.setup_needed), "", CORAL, () -> setup(null)));
        list.addView(row(KIDS, null, getString(R.string.kids_mode), getString(kids ? R.string.state_on : R.string.state_off),
                kids ? GREEN : MUTED, () -> {
                    // Kids mode without setup would show limits that nothing enforces: set up first, then turn it on.
                    if (!kids && !guard.serviceEnabled()) setup(() -> setKidsMode(true));
                    else setKidsMode(!kids);
                }));

        list.addView(listTitle(getString(R.string.schedule_title)));
        List<Schedule.Window> schedule = guard.store.windows();
        for (int i = 0; i < schedule.size(); i++) {
            final int index = i;
            list.addView(row("window" + i, null, describe(schedule.get(i)), "›", MUTED, () -> windowMenu(index)));
        }
        list.addView(row("window+", null, getString(R.string.schedule_add), "", MUTED, () -> editWindow(-1)));

        list.addView(listTitle(getString(R.string.guideline_title)));
        list.addView(row("guideline", null, getString(R.string.guideline_age), guidelineText(), MUTED, this::pickGuideline));

        list.addView(listTitle(getString(R.string.apps_title)));
        PackageManager pm = getPackageManager();
        for (ResolveInfo app : tvApps()) {
            final String pkg = app.activityInfo.packageName;
            list.addView(row(pkg, app.loadIcon(pm), guard.label(pkg), limitText(pkg, today, kids), MUTED, () -> pickLimit(pkg)));
        }
        guard.store.saveBudget(guard.budget);

        list.addView(listTitle(getString(R.string.app_name)));
        if (ready) list.addView(row(SETUP, null, getString(R.string.setup_ok), "›", MUTED, () -> setup(null)));
        list.addView(row(PIN, null, getString(R.string.change_pin), "›", MUTED, () -> newPin(() -> {
            Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show();
            refresh();
        }, null)));
        return screen;
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
     * Under a limit list: the guideline is for the whole day across all apps, and what the other apps already allow
     * on those days (`days` 0 leaves that out). Null without a guideline.
     */
    private String limitNote(String pkg, int days) {
        String band = guard.store.guideline();
        int minutes = Usage.guidelineMinutes(band), others = 0;
        if (minutes == 0) return null;
        String note = getString(R.string.limit_note, getString(AGES[java.util.Arrays.asList(Usage.BANDS).indexOf(band)]), duration(minutes * Budget.MINUTE));
        if (days != 0) for (String other : guard.budget.limits.keySet()) {
            if (!other.equals(pkg)) others += Math.max(0, guard.budget.limitOn(other, days == Budget.WEEKEND ? 5 : 0));
        }
        return others == 0 ? note : note + " " + getString(R.string.limit_note_others, duration(others * Budget.MINUTE));
    }

    /** A section title over rows: in line with their text, and closer to its own rows than to the ones above. */
    private TextView listTitle(String s) {
        TextView t = sectionTitle(s);
        t.setPadding(dp(18), dp(20), 0, dp(8));
        return t;
    }

    /** "60 min a day · 12 min left today": the app's limit and where today stands. */
    private String limitText(String pkg, String today, boolean counting) {
        if (!guard.budget.limits.containsKey(pkg)) return getString(R.string.no_limit);
        // With different limits by day, say which days today's one belongs to.
        String days = guard.budget.split(pkg) ? days(Budget.weekday(today) < 5 ? Budget.WEEKDAYS : Budget.WEEKEND) + "  ·  " : "";
        Integer limit = guard.budget.limit(pkg, today);
        if (limit == null) return days + getString(R.string.no_limit);
        if (limit == 0 && extra(pkg) == 0) return days + getString(R.string.not_allowed);
        long leftMin = (guard.budget.remainingMs(pkg, today) + Budget.MINUTE - 1) / Budget.MINUTE;
        String state = !counting ? getString(R.string.counts_in_kids_mode)
                : leftMin == 0 ? getString(R.string.out_today) : getString(R.string.left_today, leftMin);
        // "Mon–Fri 30 min" already says it is by day; the longer "30 min a day" would push the app's name out of the row.
        String amount = limit == 0 ? getString(R.string.not_allowed) : days.isEmpty() ? getString(R.string.minutes, limit)
                : days(Budget.weekday(today) < 5 ? Budget.WEEKDAYS : Budget.WEEKEND) + " " + getString(R.string.duration_m, limit);
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

    /** "Mon–Fri" or "Sat–Sun", in the TV's language. */
    private static String days(int group) {
        java.util.Locale locale = java.util.Locale.getDefault();
        boolean week = group == Budget.WEEKDAYS;
        return java.time.DayOfWeek.of(week ? 1 : 6).getDisplayName(java.time.format.TextStyle.SHORT, locale) + "–"
                + java.time.DayOfWeek.of(week ? 5 : 7).getDisplayName(java.time.format.TextStyle.SHORT, locale);
    }

    /** "No limit", "Not allowed in kids mode" or "30 min a day". */
    private String limitName(int minutes) {
        return minutes < 0 ? getString(R.string.no_limit) : minutes == 0 ? getString(R.string.not_allowed) : getString(R.string.minutes, minutes);
    }

    /**
     * An app's limit. With one limit for every day: more time for today, a way into different limits for the weekend,
     * then the limits to pick from. With different limits already: the two groups of days, each opening its own list.
     */
    private void pickLimit(final String pkg) {
        pickLimit(pkg, -1);
    }

    /** `focus` is the row to start on in the two-group panel, -1 for the first. */
    private void pickLimit(final String pkg, int focus) {
        final String today = Guard.today();
        final Budget budget = guard.budget;
        final int extra = budget.limit(pkg, today) != null ? 1 : 0;
        if (budget.split(pkg) || focus >= 0) {
            String[] items = new String[3 + extra], values = new String[3 + extra];
            if (extra == 1) items[0] = getString(R.string.extra_today);
            items[extra] = days(Budget.WEEKDAYS);
            values[extra] = limitName(budget.limitOn(pkg, 0));
            items[extra + 1] = days(Budget.WEEKEND);
            values[extra + 1] = limitName(budget.limitOn(pkg, 5));
            items[extra + 2] = getString(R.string.same_every_day);
            choose(guard.label(pkg), limitNote(pkg, 0), items, values, -1, focus < 0 ? extra : Math.min(focus + extra, items.length - 1), picked -> {
                int which = picked - extra;
                if (which < 0) {
                    budget.grantExtra(pkg, 30, today);
                    changed();
                } else if (which == 2) {
                    budget.setLimit(pkg, budget.limitOn(pkg, 0));
                    changed();
                } else pickLimit(pkg, which == 0 ? Budget.WEEKDAYS : Budget.WEEKEND, 1); // back on the weekend row: next to set, or the one just set
            });
            return;
        }
        // The first choice of a group seeds the other with what the app had, so only the one that differs needs setting.
        String[] items = new String[extra + 1];
        if (extra == 1) items[0] = getString(R.string.extra_today);
        items[extra] = getString(R.string.different_weekends);
        pickLimit(pkg, Budget.ALL_DAYS, -1, items);
    }

    private void pickLimit(final String pkg, final int days, final int next) {
        pickLimit(pkg, days, next, new String[0]);
    }

    /**
     * The list of limits for `days`. `first` are rows shown before it: "+30 min today" when there is one to add to,
     * and "Different on weekends". `next` is the row of the two-group panel to come back to, -1 to close.
     */
    private void pickLimit(final String pkg, final int days, final int next, final String[] first) {
        final String today = Guard.today();
        final int n = first.length;
        int current = guard.budget.limitOn(pkg, days == Budget.WEEKEND ? 5 : 0);
        String[] items = new String[LIMITS.length + 2 + n];
        System.arraycopy(first, 0, items, 0, n);
        items[n] = getString(R.string.no_limit);
        items[n + 1] = getString(R.string.not_allowed);
        int checked = current < 0 ? n : current == 0 ? n + 1 : -1;
        for (int i = 0; i < LIMITS.length; i++) {
            items[i + 2 + n] = getString(R.string.minutes, LIMITS[i]);
            if (current == LIMITS[i]) checked = i + 2 + n;
        }
        String title = guard.label(pkg) + (days == Budget.ALL_DAYS ? "" : "  ·  " + days(days));
        choose(title, limitNote(pkg, days), items, null, checked, checked, picked -> {
            int which = picked - n;
            if (which == -1) {
                pickLimit(pkg, 0); // "Different on weekends": the two groups, starting from the same limit
                return;
            }
            if (which < 0) guard.budget.grantExtra(pkg, 30, today);
            else guard.budget.setLimit(pkg, days, which == 0 ? Budget.NO_LIMIT : which == 1 ? 0 : LIMITS[which - 2]);
            changed();
            if (next >= 0) pickLimit(pkg, next);
        });
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
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
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
        choose(describe(guard.store.windows().get(index)), new String[]{getString(R.string.schedule_edit), getString(R.string.schedule_delete)}, -1, which -> {
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
        choose(getString(title), slots, Math.min(47, current / 30), which -> done.picked(which * 30));
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
