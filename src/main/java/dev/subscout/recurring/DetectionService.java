package dev.subscout.recurring;

import dev.subscout.catalogue.CatalogueRepository;
import dev.subscout.insight.InsightRepository;
import dev.subscout.ledger.LedgerRepository;
import dev.subscout.platform.Ids;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DetectionService {

    public record Result(List<RecurrenceDetector.Series> series, int subscriptionsCreated) {
    }

    private static final double AUTO_PROMOTE_CONFIDENCE = 0.70;

    private final LedgerRepository ledger;
    private final InsightRepository insights;
    private final CatalogueRepository catalogue;
    private final RecurrenceDetector detector = new RecurrenceDetector();

    public DetectionService(LedgerRepository ledger, InsightRepository insights, CatalogueRepository catalogue) {
        this.ledger = ledger;
        this.insights = insights;
        this.catalogue = catalogue;
    }

    public Result run(String profileId) {
        List<RecurrenceDetector.Series> detected = detector.detect(ledger.observations(profileId));
        Map<String, String> statuses = insights.nonCandidateStatuses(profileId);
        insights.deleteCandidates(profileId);

        int created = 0;
        for (RecurrenceDetector.Series series : detected) {
            String id = seriesId(profileId, series);
            String status = statuses.getOrDefault(id, "candidate");
            boolean promotable = !"rejected".equals(status)
                    && series.confidence() >= AUTO_PROMOTE_CONFIDENCE
                    && !series.variableAmount();

            // A promoted series is no longer pending: it must leave the candidate queue,
            // otherwise the note and the subscription table disagree about the same charge.
            insights.upsertSeries(id, profileId, series,
                    promotable && "candidate".equals(status) ? "confirmed" : status);

            if (promotable) {
                CatalogueRepository.OfferMatch offer = catalogue.match(
                        series.merchantName(), "IN", series.typical().minor(), series.cadence().wire()).orElse(null);
                String displayName = offer == null ? series.merchantName() : offer.providerName();
                if (insights.insertSubscriptionIfAbsent(Ids.newId(), profileId, id,
                        offer == null ? null : offer.offerId(), displayName, "active", series.nextExpected())) {
                    created++;
                }
            }
        }
        return new Result(detected, created);
    }

    public void triage(String seriesId, String status) {
        if (!"confirmed".equals(status) && !"rejected".equals(status)) {
            throw new IllegalArgumentException("status must be confirmed or rejected");
        }
        InsightRepository.SeriesRow series = insights.series(seriesId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown series: " + seriesId));
        insights.setSeriesStatus(seriesId, status);

        if ("confirmed".equals(status)) {
            String offerId = catalogue.match(series.merchantName(), "IN", series.typicalMinor(), series.cadence())
                    .map(CatalogueRepository.OfferMatch::offerId)
                    .orElse(null);
            insights.insertSubscriptionIfAbsent(Ids.newId(), series.profileId(), seriesId, offerId,
                    series.merchantName(), "active", series.nextExpected());
        }
    }

    private static String seriesId(String profileId, RecurrenceDetector.Series series) {
        return Ids.sha256(profileId + "|" + series.merchantId() + "|" + series.cadence().wire()
                + "|" + series.typical().currency());
    }
}
