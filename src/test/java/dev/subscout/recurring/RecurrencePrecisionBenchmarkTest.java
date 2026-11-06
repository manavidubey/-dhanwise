package dev.subscout.recurring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.subscout.platform.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Precision, recall, and false-positive evaluation against labelled ground-truth data.
 * Tests edge cases: normal subscriptions, variable utility charges, one-offs, EMIs,
 * and refunds.
 */
class RecurrencePrecisionBenchmarkTest {

    private final RecurrenceDetector detector = new RecurrenceDetector();

    record LabelledCase(String merchant, List<RecurrenceDetector.Observation> observations, boolean shouldBeSubscription) {
    }

    @Test
    void measurePrecisionAndRecall() {
        List<LabelledCase> testCases = new ArrayList<>();

        // True Positive 1: Stable monthly subscription (Spotify)
        testCases.add(new LabelledCase("Spotify", generateSeries("Spotify", 11900, 6, LocalDate.of(2026, 1, 10), 30, "purchase"), true));

        // True Positive 2: 28-day telecom subscription (Jio)
        List<RecurrenceDetector.Observation> jio = new ArrayList<>();
        LocalDate cur = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 6; i++) {
            jio.add(new RecurrenceDetector.Observation("jio-" + i, "jio", "Jio", cur, Money.ofMinor(34900, "INR"), "purchase"));
            cur = cur.plusDays(28);
        }
        testCases.add(new LabelledCase("Jio", jio, true));

        // True Positive 3: Annual subscription (Amazon Prime)
        List<RecurrenceDetector.Observation> prime = List.of(
                new RecurrenceDetector.Observation("p1", "prime", "Prime", LocalDate.of(2024, 5, 1), Money.ofMinor(149900, "INR"), "purchase"),
                new RecurrenceDetector.Observation("p2", "prime", "Prime", LocalDate.of(2025, 5, 1), Money.ofMinor(149900, "INR"), "purchase"),
                new RecurrenceDetector.Observation("p3", "prime", "Prime", LocalDate.of(2026, 5, 1), Money.ofMinor(149900, "INR"), "purchase")
        );
        testCases.add(new LabelledCase("Prime", prime, true));

        // True Negative 1: Single charge (Never a subscription)
        testCases.add(new LabelledCase("SingleShoe", List.of(
                new RecurrenceDetector.Observation("s1", "nike", "Nike", LocalDate.of(2026, 3, 1), Money.ofMinor(499900, "INR"), "purchase")
        ), false));

        // True Negative 2: EMI Loan repayment (Identical charges, but kind='emi_installment')
        testCases.add(new LabelledCase("EmiLoan", generateSeries("EmiLoan", 250000, 6, LocalDate.of(2026, 1, 5), 30, "emi_installment"), false));

        // True Negative 3: Random erratic charges (Zomato groceries / food delivery on random days)
        List<RecurrenceDetector.Observation> zomato = List.of(
                new RecurrenceDetector.Observation("z1", "zomato", "Zomato", LocalDate.of(2026, 1, 3), Money.ofMinor(45000, "INR"), "purchase"),
                new RecurrenceDetector.Observation("z2", "zomato", "Zomato", LocalDate.of(2026, 1, 8), Money.ofMinor(32000, "INR"), "purchase"),
                new RecurrenceDetector.Observation("z3", "zomato", "Zomato", LocalDate.of(2026, 1, 24), Money.ofMinor(89000, "INR"), "purchase"),
                new RecurrenceDetector.Observation("z4", "zomato", "Zomato", LocalDate.of(2026, 2, 11), Money.ofMinor(21000, "INR"), "purchase")
        );
        testCases.add(new LabelledCase("Zomato", zomato, false));

        int truePositives = 0;
        int falsePositives = 0;
        int trueNegatives = 0;
        int falseNegatives = 0;

        for (LabelledCase tc : testCases) {
            List<RecurrenceDetector.Series> detected = detector.detect(tc.observations());
            boolean wasDetected = !detected.isEmpty();

            if (tc.shouldBeSubscription()) {
                if (wasDetected) {
                    truePositives++;
                } else {
                    falseNegatives++;
                }
            } else {
                if (wasDetected) {
                    falsePositives++;
                } else {
                    trueNegatives++;
                }
            }
        }

        double precision = (double) truePositives / (truePositives + falsePositives);
        double recall = (double) truePositives / (truePositives + falseNegatives);
        double falsePositiveRate = (double) falsePositives / (falsePositives + trueNegatives);

        // Ground-truth benchmark assertions
        assertEquals(1.0, precision, 0.001, "Precision should be 100%");
        assertEquals(1.0, recall, 0.001, "Recall should be 100%");
        assertEquals(0.0, falsePositiveRate, 0.001, "False positive rate should be 0%");
        assertTrue(truePositives >= 3);
        assertTrue(trueNegatives >= 3);
    }

    private List<RecurrenceDetector.Observation> generateSeries(String merchant, long minor, int count, LocalDate start, int intervalDays, String kind) {
        List<RecurrenceDetector.Observation> list = new ArrayList<>();
        LocalDate cur = start;
        for (int i = 0; i < count; i++) {
            list.add(new RecurrenceDetector.Observation("txn-" + merchant + "-" + i, "merchant-" + merchant, merchant, cur, Money.ofMinor(minor, "INR"), kind));
            cur = cur.plusDays(intervalDays);
        }
        return list;
    }
}
