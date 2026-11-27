package org.ajeet.overtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OvertimeService.otDays: whole hours past a 9:00 h span, in 2-hour bands.
 * As spans: under 10:00 -> 0 · 10:00-11:59 -> 0.25 · 12:00-13:59 -> 0.5 ·
 * 14:00-15:59 -> 0.75 · 16:00 or more -> 1.
 */
class OvertimeRulesTest {

    private static double forSpan(int h, int m) { return OvertimeService.otDays(h * 60 + m - OvertimeService.OT_START_MIN); }

    @Test
    void lessThanAnHourPastNineIsNothing() {
        assertEquals(0, OvertimeService.otDays(-30));
        assertEquals(0, OvertimeService.otDays(0));
        assertEquals(0, OvertimeService.otDays(59));
        assertEquals(0, forSpan(9, 59));
    }

    @Test
    void bandsByWholeHours() {
        assertEquals(0.25, forSpan(10, 0));
        assertEquals(0.25, forSpan(11, 59));
        assertEquals(0.5, forSpan(12, 0));
        assertEquals(0.5, forSpan(13, 59));
        assertEquals(0.75, forSpan(14, 0));
        assertEquals(0.75, forSpan(15, 59));
        assertEquals(1, forSpan(16, 0));
        assertEquals(1, forSpan(20, 0));
    }

    @Test
    void overtimeStartsAfterNineHours() {
        assertEquals(9 * 60, OvertimeService.OT_START_MIN);
        assertEquals(60, OvertimeService.MIN_OT_MIN);
    }
}
