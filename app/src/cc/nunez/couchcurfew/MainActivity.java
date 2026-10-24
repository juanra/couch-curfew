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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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

    /** Cancelling any PIN prompt closes the app: nothing behind it is reachable without one. */
    private void pinPrompt(String title, final PinEntered done) {
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setGravity(Gravity.CENTER);
        field.setTextSize(28);
        field.setLetterSpacing(0.4f);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(32), dp(8), dp(32), 0);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pinDialog = new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> done.entered(field.getText().toString()))
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .create();
        pinDialog.show();
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
        TextView hint = text(getString(kids ? R.string.kids_hint_on : R.string.kids_hint_off), 16, MUTED, false);
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

    private void setKidsMode(boolean on) {
        android.util.Log.i(Guard.TAG, "kids mode " + (on ? "on" : "off"));
        guard.store.kidsMode(on);
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
