package dev.subscout.recurring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.subscout.platform.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecurrenceDetectorTest {

    private final RecurrenceDetector detector = new RecurrenceDetector();

    private static List<RecurrenceDetector.Observation> monthly(String merchant, long minor, int count, LocalDate start) {
        List<RecurrenceDetector.Observation> observations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            observations.add(new RecurrenceDetector.Observation(
                    "txn-" + merchant + "-" + i,
                    "merchant-" + merchant,
                    merchant,
                    start.plusMonths(i),
                    Money.ofMinor(minor, "INR"),
                    "purchase"));
        }
        return observations;
    }

    @Test
    void detectsStableMonthlySubscription() {
        List<RecurrenceDetector.Series> series = detector.detect(
                monthly("Netflix", 64900, 6, LocalDate.of(2026, 1, 5)));

        assertEquals(1, series.size());
        RecurrenceDetector.Series detected = series.get(0);
        assertEquals(Cadence.MONTHLY, detected.cadence());
        assertTrue(detected.confidence() > 0.9, "expected high confidence, got " + detected.confidence());
        assertFalse(detected.variableAmount());
        assertNull(detected.priceChange());
        assertEquals(6, detected.evidence().size());
        assertEquals(LocalDate.of(2026, 7, 5), detected.nextExpected());
    }

    @Test
    void reportsPriceStepOnlyWhenTwoChargesAgree() {
        List<RecurrenceDetector.Observation> observations = new ArrayList<>(
                monthly("Netflix", 64900, 3, LocalDate.of(2026, 1, 5)));
        observations.add(new RecurrenceDetector.Observation("t4", "merchant-Netflix", "Netflix",
                LocalDate.of(2026, 4, 5), Money.ofMinor(79900, "INR"), "purchase"));
        observations.add(new RecurrenceDetector.Observation("t5", "merchant-Netflix", "Netflix",
                LocalDate.of(2026, 5, 5), Money.ofMinor(79900, "INR"), "purchase"));

        RecurrenceDetector.Series detected = detector.detect(observations).get(0);
        assertNotNull(detected.priceChange());
        assertEquals(64900, detected.priceChange().previous().minor());
        assertEquals(79900, detected.priceChange().current().minor());
        assertEquals(LocalDate.of(2026, 4, 5), detected.priceChange().changedOn());
    }

    @Test
    void ignoresSingleAmountOutlier() {
        List<RecurrenceDetector.Observation> observations = new ArrayList<>(
                monthly("Spotify", 11900, 3, LocalDate.of(2026, 1, 8)));
        observations.add(new RecurrenceDetector.Observation("t4", "merchant-Spotify", "Spotify",
                LocalDate.of(2026, 4, 8), Money.ofMinor(15900, "INR"), "purchase"));
        observations.add(new RecurrenceDetector.Observation("t5", "merchant-Spotify", "Spotify",
                LocalDate.of(2026, 5, 8), Money.ofMinor(11900, "INR"), "purchase"));

        assertNull(detector.detect(observations).get(0).priceChange());
    }

    @Test
    void doesNotClaimSubscriptionFromSingleCharge() {
        assertTrue(detector.detect(monthly("OneOff", 500000, 1, LocalDate.of(2026, 1, 5))).isEmpty());
    }

    @Test
    void capsConfidenceWithOnlyTwoObservations() {
        RecurrenceDetector.Series detected = detector
                .detect(monthly("Notion", 10000, 2, LocalDate.of(2026, 1, 12)))
                .get(0);
        assertEquals(Cadence.MONTHLY, detected.cadence());
        assertTrue(detected.confidence() <= 0.55, "two observations must stay a candidate");
    }

    @Test
    void excludesEmiInstalments() {
        List<RecurrenceDetector.Observation> emi = List.of(
                new RecurrenceDetector.Observation("e1", "m-emi", "EMI", LocalDate.of(2026, 1, 10),
                        Money.ofMinor(500000, "INR"), "emi_installment"),
                new RecurrenceDetector.Observation("e2", "m-emi", "EMI", LocalDate.of(2026, 2, 10),
                        Money.ofMinor(500000, "INR"), "emi_installment"),
                new RecurrenceDetector.Observation("e3", "m-emi", "EMI", LocalDate.of(2026, 3, 10),
                        Money.ofMinor(500000, "INR"), "emi_installment"));
        assertTrue(detector.detect(emi).isEmpty());
    }

    @Test
    void flagsVariableAmountObligation() {
        long[] amounts = {120000, 180000, 90000, 210000, 150000};
        List<RecurrenceDetector.Observation> observations = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            observations.add(new RecurrenceDetector.Observation("u" + i, "m-util", "Electricity",
                    LocalDate.of(2026, 1, 5).plusMonths(i), Money.ofMinor(amounts[i], "INR"), "purchase"));
        }
        RecurrenceDetector.Series detected = detector.detect(observations).get(0);
        assertTrue(detected.variableAmount());
        assertTrue(detected.confidence() <= 0.80);
    }

    @Test
    void ignoresIrregularSpend() {
        List<RecurrenceDetector.Observation> observations = List.of(
                new RecurrenceDetector.Observation("i1", "m-store", "Store", LocalDate.of(2026, 1, 1),
                        Money.ofMinor(10000, "INR"), "purchase"),
                new RecurrenceDetector.Observation("i2", "m-store", "Store", LocalDate.of(2026, 1, 6),
                        Money.ofMinor(10000, "INR"), "purchase"),
                new RecurrenceDetector.Observation("i3", "m-store", "Store", LocalDate.of(2026, 2, 15),
                        Money.ofMinor(10000, "INR"), "purchase"),
                new RecurrenceDetector.Observation("i4", "m-store", "Store", LocalDate.of(2026, 2, 27),
                        Money.ofMinor(10000, "INR"), "purchase"));
        assertTrue(detector.detect(observations).isEmpty());
    }
}
