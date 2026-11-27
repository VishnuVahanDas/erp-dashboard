package org.ajeet.payroll;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pay rules in PayrollRules.compute / missedMinutes (moved from PayrollStore). These pin today's
 * results so the Spring Boot move can't change anyone's pay unnoticed.
 * November 2026: 30 days, Sundays on the 1st, 8th, 15th, 22nd and 29th.
 */
class PayrollRulesTest {

    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final double GROSS = 30_000; // day rate 1000, hour rate 125

    // ------------------------------------------------------------ missed minutes

    @Test
    void fullSpanMissesNothing() {
        assertEquals(0, PayrollRules.missedMinutes(510, 510));
        assertEquals(0, PayrollRules.missedMinutes(510, 600));
    }

    @Test
    void tenMinutesAreForgiven() {
        assertEquals(0, PayrollRules.missedMinutes(510, 500));
    }

    @Test
    void moreThanGraceRoundsUpToTheHalfHour() {
        assertEquals(30, PayrollRules.missedMinutes(510, 499)); // 11 min short
        assertEquals(30, PayrollRules.missedMinutes(510, 480)); // 30 min short
        assertEquals(60, PayrollRules.missedMinutes(510, 470)); // 40 min short
    }

    @Test
    void anAbsentDayCostsAtMostEightHours() {
        assertEquals(480, PayrollRules.missedMinutes(510, 0));
    }

    @Test
    void halfDayLeaveLowersTheRequiredSpan() {
        assertEquals(0, PayrollRules.missedMinutes(240, 245));
        assertEquals(30, PayrollRules.missedMinutes(240, 220)); // 20 min short
        assertEquals(60, PayrollRules.missedMinutes(240, 200)); // 40 min short
    }

    // ------------------------------------------------------------------ compute

    @Test
    void aMonthWithShortAbsentLeaveHolidayAndOvertime() {
        Map<LocalDate, LocalDateTime[]> spans = fullSpans(NOV.atDay(1));
        spans.put(day(3), span(3, "09:00", "17:15"));   // 8:15 -> 15 min short -> 30 min missed
        spans.remove(day(4));                           // absent -> 8 h missed
        spans.put(day(6), span(6, "09:00", "13:05"));   // half-day leave, 4:05 of 4:00 needed
        spans.remove(day(9));                           // full-day leave

        Map<LocalDate, PayrollRules.Leave> leave = Map.of(
                day(6), new PayrollRules.Leave(false, PayrollRules.SHIFT_MIN - PayrollRules.HALF_DAY_REQUIRED_MIN, "Personal leave (first half)"),
                day(9), new PayrollRules.Leave(true, PayrollRules.SHIFT_MIN, "Sick leave"));
        Map<LocalDate, PayrollRules.Overtime> ot = Map.of(day(10), new PayrollRules.Overtime(180, 0.5, "overtime 3:00 h"));

        PayrollRules.Computed c = PayrollRules.compute(NOV, GROSS, LocalDate.of(2026, 10, 1), NOV.atEndOfMonth(),
                Map.of(day(5), "Diwali"), leave, spans, Map.of(), ot, 0);

        // 25 Mon-Sat days, minus the holiday and the full leave day
        assertEquals(23, c.workingDays());
        assertEquals(8.5, c.missedHours(), 1e-9);
        assertEquals(1000, c.dayRate(), 1e-9);
        assertEquals(125, c.hourRate(), 1e-9);
        assertEquals(30_000, c.gross(), 1e-9);
        assertEquals(1062.5, c.cut(), 1e-9);
        assertEquals(0.5, c.otDays(), 1e-9);
        assertEquals(500, c.otPay(), 1e-9);
        assertEquals(29_437.5, c.net(), 1e-9);

        assertEquals("sunday", type(c, 1));
        assertEquals("work", type(c, 3));
        assertEquals(30, dayOf(c, 3).missedMin());
        assertEquals(480, dayOf(c, 4).missedMin());
        assertEquals("holiday", type(c, 5));
        assertEquals("Diwali", dayOf(c, 5).note());
        assertEquals(240, dayOf(c, 6).requiredMin());
        assertEquals(0, dayOf(c, 6).missedMin());
        assertEquals("leave", type(c, 9));
        assertEquals(0.5, dayOf(c, 10).otDays(), 1e-9);
        assertEquals(30, c.days().size());
    }

    @Test
    void midMonthStartProratesGrossAndSkipsEarlierDays() {
        PayrollRules.Computed c = PayrollRules.compute(NOV, GROSS, day(16), NOV.atEndOfMonth(),
                Map.of(), Map.of(), fullSpans(day(16)), Map.of(), Map.of(), 0);

        assertEquals(15_000, c.gross(), 1e-9);      // 15 days at 1000
        assertEquals(13, c.workingDays());          // 16-30 without the 22nd and 29th
        assertEquals(0, c.cut(), 1e-9);
        assertEquals(15_000, c.net(), 1e-9);
        assertEquals("before", type(c, 15));
        assertEquals("work", type(c, 16));
    }

    @Test
    void directorCorrectionAddsToMissedHours() {
        PayrollRules.Computed c = PayrollRules.compute(NOV, GROSS, LocalDate.of(2026, 10, 1), NOV.atEndOfMonth(),
                Map.of(), Map.of(), fullSpans(NOV.atDay(1)), Map.of(), Map.of(), 2);
        assertEquals(250, c.cut(), 1e-9);
        assertEquals(29_750, c.net(), 1e-9);
    }

    @Test
    void daysAfterCountUntilAreNotCountedYet() {
        PayrollRules.Computed c = PayrollRules.compute(NOV, GROSS, LocalDate.of(2026, 10, 1), day(10),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0);
        assertEquals(8, c.workingDays());           // 2-7, 9, 10
        assertEquals(64, c.missedHours(), 1e-9);    // all absent: 8 x 8 h
        assertEquals("future", type(c, 11));
    }

    @Test
    void overtimeIsPaidOnASundayToo() {
        PayrollRules.Computed c = PayrollRules.compute(NOV, GROSS, LocalDate.of(2026, 10, 1), NOV.atEndOfMonth(),
                Map.of(), Map.of(), fullSpans(NOV.atDay(1)),
                Map.of(), Map.of(day(8), new PayrollRules.Overtime(420, 1, null)), 0);
        assertEquals(1, c.otDays(), 1e-9);
        assertEquals(31_000, c.net(), 1e-9);
        assertEquals("sunday", type(c, 8));
    }

    // ------------------------------------------------------------------ helpers

    private static LocalDate day(int d) { return NOV.atDay(d); }

    private static LocalDateTime[] span(int d, String in, String out) {
        return new LocalDateTime[]{LocalDateTime.parse(day(d) + "T" + in), LocalDateTime.parse(day(d) + "T" + out)};
    }

    /** 09:00-17:35 (8:35 h, a full day) on every Monday-Saturday from 'from' to month end. */
    private static Map<LocalDate, LocalDateTime[]> fullSpans(LocalDate from) {
        Map<LocalDate, LocalDateTime[]> m = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(NOV.atEndOfMonth()); d = d.plusDays(1))
            if (d.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) m.put(d, span(d.getDayOfMonth(), "09:00", "17:35"));
        return m;
    }

    private static PayrollRules.Day dayOf(PayrollRules.Computed c, int d) {
        List<PayrollRules.Day> days = c.days();
        return days.stream().filter(x -> x.date().equals(day(d).toString())).findFirst().orElseThrow();
    }

    private static String type(PayrollRules.Computed c, int d) { return dayOf(c, d).type(); }
}
