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
        check(b.limits.get("netflix") == 60, "a new day keeps the limit");

        b.setLimit("netflix", 0);
        check(!b.limits.containsKey("netflix"), "a limit of 0 removes it");

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

        System.out.println(failures == 0 ? "All checks passed." : failures + " check(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
