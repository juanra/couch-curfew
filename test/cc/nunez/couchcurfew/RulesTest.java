package cc.nunez.couchcurfew;

import java.util.Collections;
import java.util.Set;

/** Runs on the computer (test.sh): the daily budget and PIN rules, without a TV. */
public class RulesTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        String d1 = "2026-10-24", d2 = "2026-10-25";
        long m = Budget.MINUTE;

        Budget b = new Budget();
        check(b.remainingMs("netflix", d1) == Long.MAX_VALUE, "an app without a limit has no end");
        b.addUsage("netflix", 10 * m, d1);
        check(b.usedMs("netflix", d1) == 0, "time on an app without a limit isn't counted");

        b.setLimit("netflix", 30);
        b.addUsage("netflix", 20 * m, d1);
        check(b.remainingMs("netflix", d1) == 10 * m, "30 min limit, 20 used: 10 left");
        check(!b.exhausted("netflix", d1), "not out of time yet");
        b.addUsage("netflix", 15 * m, d1);
        check(b.remainingMs("netflix", d1) == 0, "going past the limit leaves zero, not negative");
        check(b.exhausted("netflix", d1), "out of time");

        b.grantExtra("netflix", 30, d1);
        check(b.remainingMs("netflix", d1) == 25 * m, "30 extra minutes on top of 35 used of 30: 25 left");
        b.grantExtra("youtube", 30, d1);
        check(!b.extraMin.containsKey("youtube"), "extra time for an app without a limit is ignored");

        b.setLimit("netflix", 60);
        check(b.usedMs("netflix", d1) == 35 * m, "changing the limit keeps today's usage");

        check(b.remainingMs("netflix", d2) == 60 * m, "a new day starts from zero, extras gone");
        check(b.limit("netflix", d2) == 60, "a new day keeps the limit");

        b.setLimit("netflix", Budget.NO_LIMIT);
        check(!b.limits.containsKey("netflix"), "NO_LIMIT removes the limit");

        // 2026-10-23 is a Friday, d1 a Saturday, d2 a Sunday and 2026-10-26 a Monday.
        String friday = "2026-10-23", monday = "2026-10-26";
        Budget w = new Budget();
        w.setLimit("youtube", 30);
        check(!w.split("youtube"), "one limit for every day is not split");
        w.setLimit("youtube", Budget.WEEKEND, 60);
        check(w.split("youtube") && w.limit("youtube", friday) == 30 && w.limit("youtube", d1) == 60 && w.limit("youtube", d2) == 60 && w.limit("youtube", monday) == 30,
                "30 min on weekdays, 60 at the weekend");
        w.addUsage("youtube", 30 * m, friday);
        check(w.exhausted("youtube", friday) && w.remainingMs("youtube", d1) == 60 * m, "out of time on Friday, a full hour on Saturday");
        w.setLimit("netflix", Budget.WEEKDAYS, 0);
        w.setLimit("netflix", Budget.WEEKEND, 120);
        check(w.blocked(true, new java.util.HashSet<String>(), monday).contains("netflix") && !w.blocked(true, new java.util.HashSet<String>(), d1).contains("netflix"),
                "blocked on weekdays, open at the weekend");
        w.grantExtra("netflix", 30, monday);
        check(w.remainingMs("netflix", monday) == 30 * m && w.remainingMs("netflix", d1) == 120 * m, "+30 min on a blocked weekday doesn't reach the weekend");
        w.setLimit("spotify", Budget.WEEKEND, 45);
        w.addUsage("spotify", 50 * m, monday);
        check(w.limit("spotify", monday) == null && w.usedMs("spotify", monday) == 0 && !w.exhausted("spotify", monday), "no limit on weekdays: nothing counted, nothing blocked");
        w.setLimit("spotify", Budget.WEEKEND, Budget.NO_LIMIT);
        check(!w.limits.containsKey("spotify"), "no limit on any day removes the app");
        w.setLimit("youtube", w.limitOn("youtube", 0));
        check(!w.split("youtube") && w.limit("youtube", d1) == 30, "same limit every day takes the weekday one");

        check(Usage.guidelineMinutes("2to4") == 60 && Usage.guidelineMinutes("5to17") == 120, "1 h for ages 2 to 4, 2 h for 5 to 17");
        check(Usage.guidelineMinutes("under2") == 0 && Usage.guidelineMinutes("off") == 0 && Usage.guidelineMinutes("") == 0, "no number under 2, off or not set");
        check(Usage.level(95 * m, 120) == Usage.UNDER && Usage.level(96 * m, 120) == Usage.CLOSE && Usage.level(120 * m + 59_000, 120) == Usage.CLOSE
                && Usage.level(121 * m, 120) == Usage.OVER, "under up to 80%, close up to the guideline, over past it");
        java.util.Map<String, Long> kidsDays = new java.util.HashMap<>();
        kidsDays.put(d1, 130 * m);
        kidsDays.put(d2, 100 * m);
        int[] levels = Usage.levels(kidsDays, java.time.LocalDate.parse("2026-10-23"), java.time.LocalDate.parse(d2), 120);
        check(levels[Usage.UNDER] == 1 && levels[Usage.CLOSE] == 1 && levels[Usage.OVER] == 1, "a day without time is under; one close, one over");

        Budget z = new Budget();
        z.setLimit("tiktok", 0);
        check(z.exhausted("tiktok", d1), "a limit of 0 is out of time from the start");
        check(z.blocked(true, new java.util.HashSet<String>(), d1).contains("tiktok"), "a limit of 0 blocks in kids mode");
        check(z.blocked(false, new java.util.HashSet<String>(), d1).isEmpty(), "a limit of 0 doesn't block outside kids mode");
        z.grantExtra("tiktok", 30, d1);
        check(z.remainingMs("tiktok", d1) == 30 * m, "+30 min opens a blocked app for today");
        check(z.exhausted("tiktok", d2), "the next day it is blocked again");

        Budget k = new Budget();
        k.setLimit("netflix", 1);
        k.setLimit("youtube", 60);
        k.addUsage("netflix", m, d1);
        k.addUsage("youtube", m, d1);
        Set<String> locked = Collections.singleton("com.android.tv.settings");
        check(k.blocked(false, locked, d1).isEmpty(), "outside kids mode nothing is blocked");
        Set<String> blocked = k.blocked(true, locked, d1);
        check(blocked.size() == 2 && blocked.contains("netflix") && blocked.contains("com.android.tv.settings"),
                "kids mode blocks the locked apps and the ones out of time, not the rest");
        check(!k.blocked(true, locked, d2).contains("netflix"), "the next day an app out of time opens again");

        String salt = Pin.newSalt(), hash = Pin.hash("2468", salt);
        check(Pin.matches("2468", salt, hash), "the right PIN matches");
        check(!Pin.matches("2469", salt, hash), "a wrong PIN doesn't");
        check(!Pin.hash("2468", Pin.newSalt()).equals(hash), "a different salt, a different hash");
        check(!Pin.wellFormed("12a4") && !Pin.wellFormed("123") && !Pin.wellFormed("123456789") && Pin.wellFormed("1234"),
                "only 4 to 8 digits");
        check(Pin.lockoutMs(4) == 0 && Pin.lockoutMs(5) == 60_000 && Pin.lockoutMs(7) == 180_000,
                "four free tries, then a growing wait");


        java.time.LocalDateTime mon = java.time.LocalDateTime.of(2026, 10, 26, 0, 0); // a Monday
        check(mon.getDayOfWeek() == java.time.DayOfWeek.MONDAY, "the test date is a Monday");
        java.util.List<Schedule.Window> school = java.util.Arrays.asList(
                new Schedule.Window(Schedule.WEEKDAYS, 14 * 60, 20 * 60),
                new Schedule.Window(Schedule.WEEKEND, 8 * 60, 21 * 60));
        check(!Schedule.active(school, mon.withHour(13).withMinute(59)), "Monday 13:59 is outside 14-20");
        check(Schedule.active(school, mon.withHour(14)), "Monday 14:00 starts the window");
        check(!Schedule.active(school, mon.withHour(20)), "Monday 20:00 ends it");
        check(Schedule.active(school, mon.minusDays(1).withHour(9)), "Sunday 09:00 is in the weekend window");
        check(!Schedule.active(school, mon.minusDays(1).withHour(22)), "Sunday 22:00 is out");
        check(Schedule.nextBoundary(school, mon.withHour(15)).equals(mon.withHour(20)), "inside a window the next change is its end");
        check(Schedule.nextBoundary(school, mon.plusDays(4).withHour(21)).equals(mon.plusDays(5).withHour(8)),
                "Friday night the next change is Saturday 08:00");

        java.util.List<Schedule.Window> night = java.util.Collections.singletonList(new Schedule.Window(1, 20 * 60, 7 * 60)); // Monday only
        check(Schedule.active(night, mon.withHour(23)), "a window across midnight is on late Monday");
        check(Schedule.active(night, mon.plusDays(1).withHour(6)), "and early Tuesday, the day after it starts");
        check(!Schedule.active(night, mon.withHour(6)), "but not early Monday (Sunday has no window)");
        check(Schedule.nextBoundary(night, mon.withHour(23)).equals(mon.plusDays(1).withHour(7)), "its end is on the next day");
        check(!new Schedule.Window(Schedule.ALL_DAYS, 600, 600).valid() && !new Schedule.Window(0, 1, 2).valid(),
                "no days, or the same start and end, is not a window");

        java.time.LocalDateTime until = Schedule.nextBoundary(school, mon.withHour(16));
        check(!Schedule.kidsMode(school, false, until, mon.withHour(16).withMinute(30)), "a parent's 'off' at 16:00 holds inside the window");
        check(Schedule.kidsMode(school, false, until, mon.plusDays(1).withHour(15)), "the next day the schedule is back");
        check(Schedule.kidsMode(java.util.Collections.<Schedule.Window>emptyList(), true, null, mon), "without windows a manual 'on' stays on");
        check(Schedule.kidsMode(school, null, null, mon.withHour(15)), "no manual change: the schedule decides");

        // The usage history: which hour and day time belongs to, and the dashboard's bars.
        final java.util.Map<String, Long> got = new java.util.LinkedHashMap<>();
        Usage.Sink sink = (day, hour, ms) -> got.merge(day + "@" + hour, ms, Long::sum);
        java.time.LocalDateTime t = java.time.LocalDateTime.of(2026, 10, 24, 20, 30, 15);
        Usage.split(t, 15_000, sink);
        check(got.size() == 1 && got.get("2026-10-24@20") == 15_000, "15 s inside an hour go to that hour");
        got.clear();
        Usage.split(java.time.LocalDateTime.of(2026, 10, 24, 21, 0, 5), 15_000, sink);
        check(got.get("2026-10-24@21") == 5_000 && got.get("2026-10-24@20") == 10_000, "across the hour: 5 s after, 10 s before");
        got.clear();
        Usage.split(java.time.LocalDateTime.of(2026, 10, 25, 0, 10, 0), 80 * m, sink);
        check(got.get("2026-10-25@0") == 10 * m && got.get("2026-10-24@23") == 60 * m && got.get("2026-10-24@22") == 10 * m,
                "across midnight: each day and hour gets its part");
        got.clear();
        Usage.split(java.time.LocalDateTime.of(2026, 10, 24, 21, 0, 0), 5 * m, sink);
        check(got.size() == 1 && got.get("2026-10-24@20") == 5 * m, "time that ends on the hour belongs to the hour before");
        got.clear();
        Usage.split(t, 0, sink);
        Usage.split(t, -5, sink);
        check(got.isEmpty(), "no time, nothing recorded");

        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 20);
        check(Usage.from(today, 1).equals(today) && Usage.from(today, 7).equals(java.time.LocalDate.of(2026, 11, 14)),
                "today is a range of one day; 7 days start six days back");
        java.util.Map<String, Long> byDay = new java.util.HashMap<>();
        byDay.put("2026-11-20", 3L);
        byDay.put("2026-11-14", 5L);
        byDay.put("2026-11-13", 7L);
        byDay.put("2026-10-31", 11L);
        long[] week = Usage.bySteps(byDay, Usage.from(today, 7), today, 1);
        check(week.length == 7 && week[0] == 5 && week[6] == 3 && week[3] == 0, "7 days: a bar per day, oldest first, the day before the range left out");
        long[] quarter = Usage.bySteps(byDay, Usage.from(today, 90), today, 7);
        check(quarter.length == 13 && quarter[12] == 8 && quarter[11] == 7 && quarter[10] == 11 && quarter[9] == 0,
                "90 days: 13 bars of a week ending today");
        long[] year = Usage.byMonths(byDay, Usage.from(today, 365), today);
        check(year.length == 13 && year[12] == 15 && year[11] == 11 && year[0] == 0, "a year: a bar per calendar month, the first and last partial");
        check(Usage.peak(new long[24]) == -1 && Usage.peak(new long[]{0, 4, 9, 9, 1}) == 2, "the busiest hour is the first with the most time, none when empty");

        System.out.println(failures == 0 ? "All checks passed." : failures + " check(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
