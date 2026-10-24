package cc.nunez.couchcurfew;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * When kids mode turns itself on: windows of days and hours ("Mon-Fri 14:00-20:00"). A window whose end is before its
 * start crosses midnight and belongs to the day it starts on. A parent's manual change (behind the PIN) wins until the
 * next boundary of any window. Plain Java, tested by test.sh.
 */
final class Schedule {
    private Schedule() {}

    static final int ALL_DAYS = 0x7F, WEEKDAYS = 0x1F, WEEKEND = 0x60;

    static final class Window {
        /** Bit 0 is Monday ... bit 6 is Sunday. */
        final int days;
        /** Minutes after midnight; end < start crosses midnight; end == start is not a window. */
        final int start, end;

        Window(int days, int start, int end) {
            this.days = days & ALL_DAYS;
            this.start = start;
            this.end = end;
        }

        boolean valid() {
            return days != 0 && start != end && start >= 0 && start < 1440 && end >= 0 && end < 1440;
        }

        boolean on(DayOfWeek d) {
            return (days & (1 << (d.getValue() - 1))) != 0;
        }

        boolean covers(LocalDateTime t) {
            int m = t.getHour() * 60 + t.getMinute();
            DayOfWeek today = t.getDayOfWeek();
            if (start < end) return on(today) && m >= start && m < end;
            return (on(today) && m >= start) || (on(today.minus(1)) && m < end);
        }
    }

    static boolean active(List<Window> windows, LocalDateTime now) {
        for (Window w : windows) if (w.valid() && w.covers(now)) return true;
        return false;
    }

    /** The first start or end of any window after `now`, or null without windows. */
    static LocalDateTime nextBoundary(List<Window> windows, LocalDateTime now) {
        LocalDateTime best = null;
        LocalDate day = now.toLocalDate().minusDays(1);
        for (int i = 0; i < 9; i++, day = day.plusDays(1)) {
            for (Window w : windows) {
                if (!w.valid() || !w.on(day.getDayOfWeek())) continue;
                LocalDateTime start = day.atStartOfDay().plusMinutes(w.start);
                LocalDateTime end = (w.end > w.start ? day : day.plusDays(1)).atStartOfDay().plusMinutes(w.end);
                for (LocalDateTime t : new LocalDateTime[]{start, end}) {
                    if (t.isAfter(now) && (best == null || t.isBefore(best))) best = t;
                }
            }
        }
        return best;
    }

    /**
     * Kids mode right now. `manual` is the parent's last change (null if none) and `until` when it stops counting
     * (null: until the parent changes it again).
     */
    static boolean kidsMode(List<Window> windows, Boolean manual, LocalDateTime until, LocalDateTime now) {
        if (manual != null && (until == null || now.isBefore(until))) return manual;
        return active(windows, now);
    }
}
