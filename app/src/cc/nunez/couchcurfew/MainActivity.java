package cc.nunez.couchcurfew;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The parent's screen, behind the PIN: kids mode, a limit per app, setup. Built in code, one list, remote friendly. */
public class MainActivity extends Activity {
    private static final int[] LIMITS = {15, 30, 45, 60, 90, 120, 180};

    private Guard guard;
    private ListView list;
    private boolean unlocked;
    private AlertDialog pinDialog;

    /** One row of the list: what it says and what OK on the remote does. */
    private static final class Row {
        final String text;
        final Runnable action;
        Row(String text, Runnable action) { this.text = text; this.action = action; }
        @Override public String toString() { return text; }
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        guard = Guard.get(this);
        list = new ListView(this);
        list.setPadding(48, 32, 48, 32);
        list.setOnItemClickListener((parent, view, position, id) -> ((Row) parent.getItemAtPosition(position)).action.run());
        setContentView(list);
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
        list.setAdapter(null);
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
        pinDialog = new AlertDialog.Builder(this).setTitle(title).setView(field)
                .setPositiveButton(android.R.string.ok, (d, w) -> done.entered(field.getText().toString()))
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .create();
        pinDialog.show();
    }

    // MARK: - Main list

    private void refresh() {
        if (!unlocked) return;
        String today = Guard.today();
        boolean kids = guard.store.kidsMode(), ready = guard.serviceEnabled();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(getString(kids ? R.string.kids_on : R.string.kids_off), () -> {
            guard.store.kidsMode(!kids);
            guard.reconcile(null);
            refresh();
        }));
        rows.add(new Row(getString(ready ? R.string.setup_ok : R.string.setup_needed), this::setup));
        rows.add(new Row(getString(R.string.change_pin), () -> newPin(() -> {
            Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show();
            refresh();
        })));
        for (final String pkg : tvApps()) {
            Integer limit = guard.budget.limits.get(pkg);
            String state = limit == null ? getString(R.string.no_limit)
                    : getString(R.string.limit_state, limit, guard.budget.remainingMs(pkg, today) / Budget.MINUTE);
            rows.add(new Row(guard.label(pkg) + "   ·   " + state, () -> pickLimit(pkg)));
        }
        guard.store.saveBudget(guard.budget);
        int position = list.getSelectedItemPosition();
        list.setAdapter(new ArrayAdapter<Row>(this, android.R.layout.simple_list_item_1, rows) {
            @Override public View getView(int i, View convert, ViewGroup parent) {
                TextView v = (TextView) super.getView(i, convert, parent);
                v.setTextSize(20);
                return v;
            }
        });
        if (position >= 0 && position < rows.size()) list.setSelection(position);
    }

    /** The apps on the TV's launcher, by name; not this app, nor the home screen (limiting it would send home to home). */
    private List<String> tvApps() {
        PackageManager pm = getPackageManager();
        List<String> pkgs = new ArrayList<>();
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        String homePkg = home != null && home.activityInfo != null ? home.activityInfo.packageName : "";
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String p = r.activityInfo.packageName;
            if (!p.equals(Guard.PKG) && !p.equals(homePkg) && !pkgs.contains(p)) pkgs.add(p);
        }
        Collections.sort(pkgs, (a, b) -> guard.label(a).compareToIgnoreCase(guard.label(b)));
        return pkgs;
    }

    private void pickLimit(final String pkg) {
        final String today = Guard.today();
        final boolean limited = guard.budget.limits.containsKey(pkg);
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.no_limit));
        for (int m : LIMITS) items.add(getString(R.string.minutes, m));
        if (limited) items.add(getString(R.string.extra_today));
        new AlertDialog.Builder(this).setTitle(guard.label(pkg))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) guard.budget.setLimit(pkg, 0);
                    else if (which <= LIMITS.length) guard.budget.setLimit(pkg, LIMITS[which - 1]);
                    else guard.budget.grantExtra(pkg, 30, today);
                    guard.store.saveBudget(guard.budget);
                    guard.reconcile(null);
                    refresh();
                })
                .show();
    }

    private void setup() {
        final AlertDialog waiting = new AlertDialog.Builder(this).setMessage(R.string.setup_connecting).setCancelable(false).show();
        guard.setup(() -> runOnUiThread(() -> waiting.setMessage(getString(R.string.setup_waiting))), error -> {
            waiting.dismiss();
            Toast.makeText(this, error == null ? getString(R.string.setup_done) : getString(R.string.setup_failed, error),
                    Toast.LENGTH_LONG).show();
            // The service shows up as enabled a moment after the setting changes.
            list.postDelayed(this::refresh, 1500);
        });
    }
}
