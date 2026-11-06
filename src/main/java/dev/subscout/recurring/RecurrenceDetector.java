package dev.subscout.recurring;

import dev.subscout.platform.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic recurring-charge detection.
 *
 * <p>Design rules that exist because the alternative produces confident nonsense:
 * a single charge is never a subscription; instalment loans and EMI conversions are a
 * different obligation type; amount tolerance is not used to hide price increases
 * (step changes are reported instead); and every result carries confidence plus the
 * transaction ids that produced it.
 */
public final class RecurrenceDetector {

    public record Observation(
            String txnId,
            String merchantId,
            String merchantName,
            LocalDate postedOn,
            Money amount,
            String kind) {
    }

    public record PriceChange(LocalDate changedOn, Money previous, Money current) {
    }

    public record Series(
            String merchantId,
            String merchantName,
            Cadence cadence,
            Money typical,
            Money low,
            Money high,
            boolean variableAmount,
            int occurrences,
            LocalDate firstSeen,
            LocalDate lastSeen,
            LocalDate nextExpected,
            double confidence,
            PriceChange priceChange,
            List<String> evidence) {
    }

    private static final int MIN_OCCURRENCES = 2;
    private static final double MIN_GAP_FIT = 0.5;

    public List<Series> detect(List<Observation> input) {
        Map<String, List<Observation>> byMerchant = new LinkedHashMap<>();
        for (Observation observation : input) {
            if (!"purchase".equals(observation.kind())) {
                continue;
            }
            String key = observation.merchantId() + "|" + observation.amount().currency();
            byMerchant.computeIfAbsent(key, ignored -> new ArrayList<>()).add(observation);
        }

        List<Series> series = new ArrayList<>();
        for (List<Observation> observations : byMerchant.values()) {
            analyze(observations).ifPresent(series::add);
        }
        series.sort(Comparator.comparingDouble(Series::confidence).reversed());
        return series;
    }

    private java.util.Optional<Series> analyze(List<Observation> observations) {
        List<Observation> ordered = observations.stream()
                .sorted(Comparator.comparing(Observation::postedOn))
                .toList();
        if (ordered.size() < MIN_OCCURRENCES) {
            return java.util.Optional.empty();
        }

        String currency = ordered.get(0).amount().currency();
        if (ordered.stream().anyMatch(o -> !o.amount().currency().equals(currency))) {
            return java.util.Optional.empty();
        }

        List<LocalDate> dates = ordered.stream().map(Observation::postedOn).toList();
        Cadence cadence = Cadence.classify(dates);
        if (cadence == Cadence.VARIABLE) {
            return java.util.Optional.empty();
        }

        int fits = 0;
        for (int i = 1; i < dates.size(); i++) {
            if (cadence.fits(dates.get(i - 1), dates.get(i))) {
                fits++;
            }
        }
        double gapFit = fits / (double) (dates.size() - 1);
        if (gapFit < MIN_GAP_FIT) {
            return java.util.Optional.empty();
        }
        if (dates.size() == 2 && gapFit < 1.0) {
            return java.util.Optional.empty();
        }

        List<Long> amounts = ordered.stream().map(o -> o.amount().minor()).sorted().toList();
        long medianAmount = amounts.get(amounts.size() / 2);
        long low = amounts.get(0);
        long high = amounts.get(amounts.size() - 1);
        double spread = medianAmount == 0 ? 0 : (high - low) / (double) medianAmount;
        double amountScore = Math.max(0, 1 - spread);
        boolean variableAmount = amountScore < 0.6;

        double countScore = Math.min(1.0, (dates.size() - 1) / 5.0);
        double confidence = 0.30 + 0.30 * countScore + 0.25 * gapFit + 0.15 * amountScore;
        if (dates.size() == 2) {
            confidence = Math.min(confidence, 0.55);
        }
        if (variableAmount) {
            confidence = Math.min(confidence, 0.80);
        }
        confidence = Math.min(0.99, Math.max(0.05, confidence));

        return java.util.Optional.of(new Series(
                ordered.get(0).merchantId(),
                ordered.get(0).merchantName(),
                cadence,
                Money.ofMinor(medianAmount, currency),
                Money.ofMinor(low, currency),
                Money.ofMinor(high, currency),
                variableAmount,
                ordered.size(),
                dates.get(0),
                dates.get(dates.size() - 1),
                cadence.next(dates.get(dates.size() - 1)),
                confidence,
                detectPriceChange(ordered, currency),
                ordered.stream().map(Observation::txnId).toList()));
    }

    /**
     * Reports a step change only when the two most recent charges agree with each other
     * and both differ from the prior baseline by more than 3%. A single outlier is not a
     * price increase; it is usually FX, a pro-rated charge, or a retry.
     */
    private PriceChange detectPriceChange(List<Observation> ordered, String currency) {
        if (ordered.size() < 4) {
            return null;
        }
        List<Long> prior = ordered.subList(0, ordered.size() - 2).stream()
                .map(o -> o.amount().minor())
                .sorted()
                .toList();
        long baseline = prior.get(prior.size() / 2);
        if (baseline <= 0) {
            return null;
        }
        long latest = ordered.get(ordered.size() - 1).amount().minor();
        long previous = ordered.get(ordered.size() - 2).amount().minor();
        long tolerance = Math.max(1, baseline / 50);
        if (Math.abs(latest - previous) > tolerance) {
            return null;
        }
        double change = (latest - baseline) / (double) baseline;
        if (Math.abs(change) <= 0.03) {
            return null;
        }
        return new PriceChange(
                ordered.get(ordered.size() - 2).postedOn(),
                Money.ofMinor(baseline, currency),
                Money.ofMinor(latest, currency));
    }
}
