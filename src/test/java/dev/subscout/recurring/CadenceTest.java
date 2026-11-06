package dev.subscout.recurring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CadenceTest {

    @Test
    void classifiesMonthlyByCalendarStep() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 2, 5),
                LocalDate.of(2026, 3, 5),
                LocalDate.of(2026, 4, 5));
        assertEquals(Cadence.MONTHLY, Cadence.classify(dates));
    }

    @Test
    void classifiesTwentyEightDayCycleAsFourWeekly() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 29),
                LocalDate.of(2026, 2, 26),
                LocalDate.of(2026, 3, 26));
        assertEquals(Cadence.FOUR_WEEKLY, Cadence.classify(dates));
    }

    @Test
    void classifiesMonthEndBillingAsMonthly() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31));
        assertEquals(Cadence.MONTHLY, Cadence.classify(dates));
    }

    @Test
    void classifiesAnnualIncludingLeapYear() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2023, 3, 1),
                LocalDate.of(2024, 3, 1),
                LocalDate.of(2025, 3, 1));
        assertEquals(Cadence.ANNUAL, Cadence.classify(dates));
    }

    @Test
    void rejectsIrregularDates() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 6),
                LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 3, 20));
        assertEquals(Cadence.VARIABLE, Cadence.classify(dates));
    }

    @Test
    void clampsMonthEndWhenAdvancing() {
        assertEquals(LocalDate.of(2026, 2, 28), Cadence.MONTHLY.next(LocalDate.of(2026, 1, 31)));
        assertEquals(LocalDate.of(2025, 2, 28), Cadence.ANNUAL.next(LocalDate.of(2024, 2, 29)));
    }

    @Test
    void monthStepToleratesClampedDates() {
        assertTrue(Cadence.MONTHLY.fits(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28)));
        assertFalse(Cadence.MONTHLY.fits(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 3, 5)));
    }
}
