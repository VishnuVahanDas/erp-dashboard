package org.ajeet.payroll;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The pay rules, without any database access (moved unchanged from PayrollStore; pinned by
 * PayrollRulesTest).
 *
 * Day rate = monthly gross / days in that month; hour rate = day rate / 8.
 * Each working day (Mon-Sat, not a holiday, from the salary's start date, up to
 * yesterday while the month is running) needs an 8:30 h first-to-last punch
 * span (lunch included), lowered by approved leave (full day: skipped;
 * half-day: 4 h; late/early of X h: 8:30 - X h). Missed time per day is capped
 * at 8 h (an absent day costs one day's pay); up to 10 minutes is forgiven,
 * anything more is rounded up to the next half hour. Cut = hour rate *
 * (missed + director's correction). Approved overtime (quarter days for whole
 * hours past a 9:00 h span, see OvertimeService) adds day rate * overtime days:
 * net = gross - cut + overtime.
 */
public final class PayrollRules {

    static final int SHIFT_MIN = 8 * 60 + 30; // 8:30 h day, lunch inside; overtime counts from 9:00 h
    static final int WORK_MIN = 8 * 60;       // paid working time per day
    static final int HALF_DAY_REQUIRED_MIN = 4 * 60;
    static final int GRACE_MIN = 10;
    static final int ROUND_MIN = 30;

    private PayrollRules() {}

    /**
     * One calendar day of a line's breakdown. type: work | sunday | holiday |
     * leave | before (salary not started) | future. Times are device-local HH:mm.
     * otMin / otDays: approved overtime that day (0 on lines generated before overtime existed).
     * Stored as JSON in payroll_lines.days.
     */
    public record Day(String date, String type, String note, String first, String last,
                      int spanMin, int requiredMin, int missedMin, int otMin, double otDays) {}

    /** Approved leave on one day: full day, or how many minutes of the shift it covers. */
    record Leave(boolean fullDay, int coveredMin, String note) {}

    /** Approved overtime on one day. */
    record Overtime(int otMin, double days, String note) {}

    record Computed(int workingDays, double missedHours, double dayRate, double hourRate, double gross,
                    double cut, double otDays, double otPay, double net, List<Day> days) {}

    /**
     * The pay rules for one person and month, without any database access.
     * spans: date -> {first punch, last punch}. correctionHours adds to missed.
     * overtime: approved overtime per day, paid at the day rate on any day the
     * salary has started (a Sunday or holiday too).
     */
    static Computed compute(YearMonth ym, double fullGross, LocalDate effectiveFrom, LocalDate countUntil,
                            Map<LocalDate, String> holidays, Map<LocalDate, Leave> leave,
                            Map<LocalDate, LocalDateTime[]> spans, Map<LocalDate, String> fixed,
                            Map<LocalDate, Overtime> overtime, double correctionHours) {
        int daysInMonth = ym.lengthOfMonth();
        double dayRate = fullGross / daysInMonth;
        double hourRate = dayRate / 8.0;
        LocalDate first = ym.atDay(1), start = effectiveFrom.isAfter(first) ? effectiveFrom : first;
        int paidDays = start.isAfter(ym.atEndOfMonth()) ? 0 : ym.atEndOfMonth().getDayOfMonth() - start.getDayOfMonth() + 1;
        double gross = start.equals(first) ? round2(fullGross) : round2(dayRate * paidDays);

        int workingDays = 0, missedMin = 0;
        double otDays = 0;
        List<Day> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            LocalDateTime[] span = spans.get(d);
            String f = span == null ? null : span[0].toLocalTime().toString().substring(0, 5);
            String l = span == null ? null : span[1].toLocalTime().toString().substring(0, 5);
            int spanMin = span == null ? 0 : (int) Duration.between(span[0], span[1]).toMinutes();
            if (d.isBefore(start)) { days.add(new Day(d.toString(), "before", "before salary start", f, l, spanMin, 0, 0, 0, 0)); continue; }
            Overtime ot = overtime.get(d);
            int otMin = ot == null ? 0 : ot.otMin();
            double otD = ot == null ? 0 : ot.days();
            otDays += otD;
            String otNote = ot == null ? null : ot.note();
            if (d.getDayOfWeek() == DayOfWeek.SUNDAY) { days.add(new Day(d.toString(), "sunday", notes("Sunday", otNote), f, l, spanMin, 0, 0, otMin, otD)); continue; }
            if (holidays.containsKey(d)) { days.add(new Day(d.toString(), "holiday", notes(holidays.get(d), otNote), f, l, spanMin, 0, 0, otMin, otD)); continue; }
            if (d.isAfter(countUntil)) { days.add(new Day(d.toString(), "future", notes("not counted yet", otNote), f, l, spanMin, 0, 0, otMin, otD)); continue; }
            Leave lv = leave.get(d);
            if (lv != null && lv.fullDay()) { days.add(new Day(d.toString(), "leave", notes(lv.note(), otNote), f, l, spanMin, 0, 0, otMin, otD)); continue; }
            int required = Math.max(0, SHIFT_MIN - (lv == null ? 0 : lv.coveredMin()));
            int missed = missedMinutes(required, spanMin);
            workingDays++;
            missedMin += missed;
            days.add(new Day(d.toString(), "work", notes(lv == null ? null : lv.note(), fixed.get(d), otNote),
                    f, l, spanMin, required, missed, otMin, otD));
        }
        double missedHours = missedMin / 60.0;
        double cut = round2(hourRate * Math.max(0, missedHours + correctionHours));
        double otPay = round2(dayRate * otDays);
        return new Computed(workingDays, missedHours, round4(dayRate), round4(hourRate), gross, cut,
                otDays, otPay, round2(Math.max(0, round2(gross - cut)) + otPay), days);
    }

    /** The non-empty notes joined with ", ". */
    private static String notes(String... parts) {
        return String.join(", ", Arrays.stream(parts).filter(x -> x != null && !x.isEmpty()).toList());
    }

    /** Short of the required span: capped at 8 h, 10-minute grace, then up to the next half hour. */
    static int missedMinutes(int requiredMin, int spanMin) {
        int short_ = Math.min(WORK_MIN, Math.max(0, requiredMin - spanMin));
        if (short_ <= GRACE_MIN) return 0;
        return Math.min(WORK_MIN, (short_ + ROUND_MIN - 1) / ROUND_MIN * ROUND_MIN);
    }

    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
    static String fmtH(double h) { return h == Math.floor(h) ? String.valueOf((long) h) : String.valueOf(h); }
}
