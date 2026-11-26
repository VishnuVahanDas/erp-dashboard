package org.ajeet.leave;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LeaveStore.countDays: Sundays and company holidays don't count, a half-day
 * is 0.5, late arrival / early departure is hours / 8.
 * 2 Nov 2026 is a Monday; 5 Nov is a holiday here; 8 Nov is a Sunday.
 */
class LeaveRulesTest {

    private static final Set<LocalDate> HOLIDAYS = Set.of(d(5));

    private static LocalDate d(int day) { return LocalDate.of(2026, 11, day); }

    @Test
    void sundaysAndHolidaysAreNotCounted() {
        assertEquals(5, LeaveStore.countDays(d(2), d(7), "", 0, HOLIDAYS));
        assertEquals(5, LeaveStore.countDays(d(2), d(8), "", 0, HOLIDAYS));
        assertEquals(6, LeaveStore.countDays(d(2), d(8), "", 0, Set.of()));
    }

    @Test
    void aLeaveOnlyOnDaysOffIsZero() {
        assertEquals(0, LeaveStore.countDays(d(8), d(8), "", 0, HOLIDAYS));
        assertEquals(0, LeaveStore.countDays(d(5), d(5), "first", 0, HOLIDAYS));
    }

    @Test
    void halfDayIsHalf() {
        assertEquals(0.5, LeaveStore.countDays(d(3), d(3), "first", 0, HOLIDAYS));
        assertEquals(0.5, LeaveStore.countDays(d(3), d(3), "second", 0, HOLIDAYS));
    }

    @Test
    void lateOrEarlyIsHoursOverEight() {
        assertEquals(0.25, LeaveStore.countDays(d(3), d(3), "late", 2, HOLIDAYS));
        assertEquals(0.375, LeaveStore.countDays(d(3), d(3), "early", 3, HOLIDAYS));
        assertEquals(0.0625, LeaveStore.countDays(d(3), d(3), "late", 0.5, HOLIDAYS));
    }

    @Test
    void shortParts() {
        assertTrue(LeaveStore.isShort("late"));
        assertTrue(LeaveStore.isShort("early"));
        assertFalse(LeaveStore.isShort("first"));
        assertFalse(LeaveStore.isShort(""));
    }
}
