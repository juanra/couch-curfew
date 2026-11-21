package cc.nunez.couchcurfew;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * What opens first, without a PIN: how long the TV was on, which apps took that time and at what hours, over today,
 * the last 7, 30 or 90 days or the last year. It only reads the history; changing anything is behind the PIN.
 */
final class Dashboard {
    private static final int[] RANGE_NAMES = {R.string.range_today, R.string.range_7, R.string.range_30, R.string.range_90, R.string.range_365};
    private static final int TOP_APPS = 5;
    /** Under, close to and over the guideline, in the order of Usage.UNDER, CLOSE and OVER. */
    private static final int[] LEVEL_COLORS = {MainActivity.GREEN, MainActivity.MOON, MainActivity.CORAL};
    private static final int[] LEVEL_NAMES = {R.string.days_under, R.string.days_close, R.string.days_over};
    /** What parents can do beyond counting minutes, and who says so; one a day. */
    private static final int[][] TIPS = {
            {R.string.tip_together, R.string.source_aap, R.drawable.tip_together}, {R.string.tip_meals, R.string.source_aap, R.drawable.tip_meals},
            {R.string.tip_autoplay, R.string.source_aap, R.drawable.tip_autoplay}, {R.string.tip_model, R.string.source_aap, R.drawable.tip_model},
            {R.string.tip_quality, R.string.source_aap, R.drawable.tip_quality}, {R.string.tip_room, R.string.source_aap, R.drawable.tip_room},
            {R.string.tip_guideline, 0, R.drawable.tip_guideline}};
    private static final int[] BAND_TIPS = {R.string.tip_under2, R.string.tip_2to4, R.string.tip_5to17};

    private final MainActivity a;
    private final Guard guard;

    Dashboard(MainActivity a, Guard guard) {
        this.a = a;
        this.guard = guard;
    }

    /** The whole screen for one of Usage.RANGES. It fits the TV without scrolling: the charts take the height left. */
    View build(final int range) {
        guard.tick(); // so the last seconds are in today's numbers
        LocalDate today = LocalDate.now(), from = Usage.from(today, Usage.RANGES[range]);
        Map<String, Long> byDay = Collections.emptyMap(), byApp = Collections.emptyMap(), kidsByDay = Collections.emptyMap();
        long[] byHour = new long[24];
        Map<String, String> saved = Collections.emptyMap();
        try {
            saved = guard.history.labels();
            byDay = guard.history.byDay(from, today);
            kidsByDay = guard.history.kidsByDay(from, today);
            byApp = guard.history.byApp(from, today);
            byHour = guard.history.byHour(from, today);
        } catch (android.database.sqlite.SQLiteException e) {
            android.util.Log.w(Guard.TAG, "history unreadable: " + e.getMessage());
        }
        long total = 0, kidsTotal = 0;
        for (long ms : byDay.values()) total += ms;
        for (long ms : kidsByDay.values()) kidsTotal += ms;
        // With a daily guideline for the kids' age, the days are about the time in kids mode: the total stays everyone's.
        String band = guard.store.guideline();
        int guide = Usage.guidelineMinutes(band);
        String guideName = a.duration(guide * Budget.MINUTE);

        LinearLayout screen = new LinearLayout(a);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(a.dp(56), a.dp(36), a.dp(56), a.dp(36));
        screen.setClipChildren(false);
        screen.setClipToPadding(false);

        // Header: icon, name, whether kids mode is on, and the way into the settings.
        LinearLayout header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.icon);
        header.addView(logo, new LinearLayout.LayoutParams(a.dp(40), a.dp(40)));
        TextView title = a.text(a.getString(R.string.app_name), 22, Color.WHITE, true);
        title.setPadding(a.dp(12), 0, a.dp(12), 0);
        header.addView(title);
        // Kids mode as a chip beside the name; screen readers still get the full words.
        boolean kids = guard.store.kidsMode();
        TextView state = a.text(a.getString(kids ? R.string.state_on : R.string.state_off), 13, kids ? MainActivity.SKY_BOTTOM : MainActivity.MUTED, true);
        state.setContentDescription(a.getString(kids ? R.string.kids_mode_on : R.string.kids_mode_off));
        state.setPadding(a.dp(10), a.dp(3), a.dp(10), a.dp(3));
        android.graphics.drawable.GradientDrawable chip = new android.graphics.drawable.GradientDrawable();
        chip.setColor(kids ? MainActivity.GREEN : 0x33FFFFFF);
        chip.setCornerRadius(a.dp(12));
        state.setBackground(chip);
        header.addView(state);
        header.addView(new View(a), new LinearLayout.LayoutParams(0, 0, 1));
        // The way into the settings is a gear; its name stays for screen readers.
        TextView gear = a.pill("", 0x33FFFFFF, Color.WHITE, MainActivity.SETTINGS, a::openSettings);
        android.graphics.drawable.Drawable cog = a.getDrawable(R.drawable.gear);
        cog.setBounds(0, 0, a.dp(24), a.dp(24));
        gear.setCompoundDrawables(cog, null, null, null);
        gear.setCompoundDrawableTintList(gear.getTextColors()); // dark on the focused pill, like the text
        gear.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
        gear.setContentDescription(a.getString(R.string.open_settings));
        header.addView(gear, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
        screen.addView(header);

        // The ranges.
        LinearLayout ranges = new LinearLayout(a);
        ranges.setClipChildren(false);
        ranges.setClipToPadding(false); // the focused pill grows past the row
        ranges.setPadding(0, a.dp(16), 0, a.dp(20));
        for (int i = 0; i < RANGE_NAMES.length; i++) {
            final int index = i;
            boolean on = i == range;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = a.dp(8);
            TextView pill = a.pill(a.getString(RANGE_NAMES[i]), on ? MainActivity.MOON : 0x33FFFFFF, on ? MainActivity.SKY_BOTTOM : Color.WHITE,
                    "range" + i, () -> a.showRange(index));
            pill.setTextSize(15);
            pill.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
            ranges.addView(pill, lp);
        }

        if (total == 0) {
            screen.addView(ranges);
            String why = a.getString(R.string.no_history) + (guard.serviceEnabled() ? "" : "\n" + a.getString(R.string.history_needs_setup));
            TextView empty = a.text(why, 20, MainActivity.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            screen.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            return screen;
        }

        LinearLayout columns = new LinearLayout(a);
        LinearLayout left = new LinearLayout(a), right = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        right.setOrientation(LinearLayout.VERTICAL);
        columns.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2);
        rightParams.leftMargin = a.dp(48);
        columns.addView(right, rightParams);
        screen.addView(columns, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Left: the ranges, the total, the time along the range and the hours of the day.
        columns.setClipChildren(false);
        left.setClipChildren(false);
        left.addView(ranges);
        right.setPadding(0, a.dp(20), 0, 0);
        left.addView(a.sectionTitle(a.getString(R.string.tv_time)));
        int days = Usage.RANGES[range];
        // With the guideline the days chart carries more, so the total gives it some of its height.
        TextView big = a.text(a.duration(total), guide > 0 && days > 1 ? 44 : 56, Color.WHITE, true);
        left.addView(big);
        if (days == 1 && guide > 0) {
            TextView kidsToday = a.text(a.getString(R.string.kids_today, a.duration(kidsTotal), guideName), 16, LEVEL_COLORS[Usage.level(kidsTotal, guide)], false);
            kidsToday.setPadding(0, 0, 0, a.dp(4));
            left.addView(kidsToday);
        }
        if (days > 1) {
            String text = guide > 0 ? a.getString(R.string.daily_average_kids, a.duration(total / days), a.duration(kidsTotal / days))
                    : a.getString(R.string.daily_average, a.duration(total / days));
            TextView average = a.text(text, 16, MainActivity.MUTED, false);
            average.setPadding(0, 0, 0, a.dp(guide > 0 ? 6 : 12));
            left.addView(average);
            Bars bars;
            if (guide > 0) {
                left.addView(legend(Usage.levels(kidsByDay, from, today, guide), guideName));
                // Each bar is the average day of its week or month, so one line serves every range.
                Map<String, Long> ones = new java.util.HashMap<>();
                for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) ones.put(d.toString(), 1L);
                long[] values = timeline(kidsByDay, from, today, days), count = timeline(ones, from, today, days);
                int[] colors = new int[values.length];
                for (int i = 0; i < values.length; i++) {
                    if (count[i] > 0) values[i] /= count[i];
                    colors[i] = values[i] == 0 ? 0x66FFFFFF : LEVEL_COLORS[Usage.level(values[i], guide)];
                }
                bars = new Bars(a, values, timelineLabels(from, today, days), 0x66FFFFFF, MainActivity.MOON, MainActivity.MUTED);
                bars.guideline(colors, guide * Budget.MINUTE, guideName);
            } else bars = new Bars(a, timeline(byDay, from, today, days), timelineLabels(from, today, days), 0x66FFFFFF, MainActivity.MOON, MainActivity.MUTED);
            left.addView(chart(bars, guide > 0 ? 3 : 1));
        }
        TextView hoursTitle = a.sectionTitle(a.getString(R.string.busiest_hours));
        hoursTitle.setPadding(0, a.dp(16), 0, a.dp(4));
        left.addView(hoursTitle);
        int peak = Usage.peak(byHour);
        left.addView(a.text(a.getString(R.string.peak_hour, hour(peak), hour((peak + 1) % 24)), 16, MainActivity.MUTED, false));
        String[] hourLabels = new String[24];
        for (int h = 0; h < 24; h += 6) hourLabels[h] = hour(h);
        left.addView(chart(new Bars(a, byHour, hourLabels, 0x66FFFFFF, MainActivity.MOON, MainActivity.MUTED), guide > 0 && days > 1 ? 2 : 1));

        // Right: the apps that took the most time.
        right.addView(a.sectionTitle(a.getString(R.string.by_app)));
        long most = byApp.values().iterator().next();
        int shown = 0;
        for (Map.Entry<String, Long> e : byApp.entrySet()) {
            if (shown++ == TOP_APPS) break;
            right.addView(appRow(e.getKey(), saved.get(e.getKey()), e.getValue(), most));
        }
        // Under the apps, one thing a parent can do: the minutes are only part of it.
        int[] tip = tip(band, today);
        right.addView(new View(a), new LinearLayout.LayoutParams(0, 0, 1));
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(a.text(a.getString(tip[0]), 16, MainActivity.MUTED, false));
        if (tip[1] != 0) {
            TextView source = a.text(a.getString(tip[1]), 12, 0x99B8BCE0, false);
            source.setPadding(0, a.dp(4), 0, 0);
            words.addView(source);
        }
        // The drawing sits on the leading side of the words it illustrates.
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        ImageView art = new ImageView(a);
        art.setImageResource(tip[2]);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(a.dp(72), a.dp(72));
        size.setMarginEnd(a.dp(16));
        line.addView(art, size);
        line.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        right.addView(line);
        return screen;
    }

    /** Today's tip and its source (0 without one). Until the parent picks an age, how to get the guideline. */
    private static int[] tip(String band, LocalDate today) {
        if (band.isEmpty()) return new int[]{R.string.guideline_setup, 0, R.drawable.tip_guideline};
        int own = java.util.Arrays.asList(Usage.BANDS).indexOf(band), n = TIPS.length + (own < 0 ? 0 : 1);
        int pick = today.getDayOfYear() % n;
        return pick < TIPS.length ? TIPS[pick] : new int[]{BAND_TIPS[own], R.string.source_canada, R.drawable.tip_guideline};
    }

    /** "Kids mode, 2 h a day guideline   3 over   1 close   3 under": the count says what the colors say. */
    private View legend(int[] levels, String guideName) {
        LinearLayout line = new LinearLayout(a);
        line.addView(a.text(a.getString(R.string.kids_vs_guideline, guideName), 14, MainActivity.MUTED, false));
        for (int level = Usage.OVER; level >= Usage.UNDER; level--) {
            TextView count = a.text(a.getString(LEVEL_NAMES[level], levels[level]), 14, LEVEL_COLORS[level], true);
            count.setPadding(a.dp(16), 0, 0, 0);
            line.addView(count);
        }
        return line;
    }

    private View chart(Bars bars, int weight) {
        bars.setMinimumHeight(a.dp(60));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
        lp.topMargin = a.dp(8);
        bars.setLayoutParams(lp);
        return bars;
    }

    /** A bar per day up to 30 days, per week for 90 and per month for the year. */
    private static long[] timeline(Map<String, Long> byDay, LocalDate from, LocalDate to, int days) {
        return days > 90 ? Usage.byMonths(byDay, from, to) : Usage.bySteps(byDay, from, to, days > 30 ? 7 : 1);
    }

    private static String[] timelineLabels(LocalDate from, LocalDate to, int days) {
        Locale locale = Locale.getDefault();
        if (days > 90) {
            String[] out = new String[(int) java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(from), java.time.YearMonth.from(to)) + 1];
            for (int i = 0; i < out.length; i++) out[i] = from.plusMonths(i).getMonth().getDisplayName(TextStyle.SHORT, locale);
            return out;
        }
        int step = days > 30 ? 7 : 1, n = (days + step - 1) / step;
        String[] out = new String[n];
        DateTimeFormatter dayMonth = DateTimeFormatter.ofPattern("d MMM", locale);
        for (int i = 0; i < n; i++) {
            LocalDate end = to.minusDays((long) (n - 1 - i) * step);
            if (days <= 7) out[i] = end.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale);
            else if ((n - 1 - i) % (step == 1 ? 5 : 3) == 0) out[i] = step == 1 ? String.valueOf(end.getDayOfMonth()) : end.format(dayMonth);
        }
        return out;
    }

    private static String hour(int h) {
        return String.format(Locale.ROOT, "%02d:00", h);
    }

    /** Icon, name and time, over a bar as long as this app's share of the most used one. */
    private View appRow(String pkg, String saved, long ms, long most) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, a.dp(10));
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(a);
        String label = guard.label(pkg);
        try {
            icon.setImageDrawable(a.getPackageManager().getApplicationIcon(pkg));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            // An app that was removed keeps its history, under the name it had and with a blank tile for an icon.
            if (saved != null) label = saved;
            icon.setBackground(rounded(0x33FFFFFF));
        }
        line.addView(icon, new LinearLayout.LayoutParams(a.dp(24), a.dp(24)));
        TextView name = a.text(label, 16, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(a.dp(10), 0, a.dp(10), 0);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(a.text(a.duration(ms), 16, MainActivity.MUTED, false));
        row.addView(line);

        LinearLayout track = new LinearLayout(a);
        track.setBackground(rounded(0x26FFFFFF));
        View fill = new View(a);
        fill.setBackground(rounded(ms == most ? MainActivity.MOON : 0x99FFFFFF)); // yellow marks the top one, as in the charts
        float share = most == 0 ? 0 : ms / (float) most;
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, share));
        track.addView(new View(a), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1 - share));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(4));
        lp.topMargin = a.dp(5);
        row.addView(track, lp);
        return row;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(a.dp(2));
        return d;
    }
}
