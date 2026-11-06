package dev.subscout.recurring;

import dev.subscout.insight.InsightRepository;
import dev.subscout.platform.Money;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Deterministic cash flow forecasting engine.
 * Computes projected recurring outflows across 30, 60, and 90-day time horizons.
 */
@Service
public class CashflowForecastService {

    public record ForecastEvent(
            String subscriptionId,
            String name,
            LocalDate scheduledOn,
            String billingFrequency,
            Money amount,
            String eventType,
            String inclusionReason,
            long daysFromNow) {
    }

    public record ForecastSummary(
            Money horizon30Days,
            Money horizon60Days,
            Money horizon90Days,
            int eventCount30Days,
            int eventCount60Days,
            int eventCount90Days,
            List<ForecastEvent> upcomingEvents) {
    }

    private final InsightRepository insightRepository;

    public CashflowForecastService(InsightRepository insightRepository) {
        this.insightRepository = insightRepository;
    }

    public ForecastSummary forecast(String profileId, String currency) {
        LocalDate today = LocalDate.now();
        LocalDate horizon90 = today.plusDays(90);

        List<InsightRepository.SubscriptionRow> subs = insightRepository.subscriptions(profileId).stream()
                .filter(s -> !"cancelled".equals(s.state()))
                .filter(s -> !"USER_CONFIRMED_CANCELLED".equals(s.cancellationStage()))
                .filter(s -> !"REALIZED_SAVINGS".equals(s.cancellationStage()))
                .filter(s -> s.nextExpected() != null)
                .toList();

        List<ForecastEvent> allEvents = new ArrayList<>();
        long total30Minor = 0;
        long total60Minor = 0;
        long total90Minor = 0;

        int count30 = 0;
        int count60 = 0;
        int count90 = 0;

        for (var sub : subs) {
            LocalDate next = sub.nextExpected();
            long amountMinor = sub.typicalMinor();
            int exponent = sub.exponent();
            Money eventMoney = Money.ofMinor(amountMinor, currency, exponent);

            // Roll forward up to 90 days based on cadence
            Cadence cadence = Cadence.fromWire(sub.cadence());
            LocalDate cursor = next;

            while (cursor != null && !cursor.isAfter(horizon90)) {
                if (!cursor.isBefore(today)) {
                    long days = ChronoUnit.DAYS.between(today, cursor);
                    String horizonTag = days <= 30 ? "Included in 30d/60d/90d horizon" : days <= 60 ? "Included in 60d/90d horizon" : "Included in 90d horizon";
                    ForecastEvent event = new ForecastEvent(
                            sub.id(), sub.displayName(), cursor,
                            cadence.wire(), eventMoney, "RENEWAL",
                            horizonTag, days);
                    allEvents.add(event);

                    if (days <= 30) {
                        total30Minor += amountMinor;
                        count30++;
                    }
                    if (days <= 60) {
                        total60Minor += amountMinor;
                        count60++;
                    }
                    if (days <= 90) {
                        total90Minor += amountMinor;
                        count90++;
                    }
                }
                cursor = cadence.next(cursor);
            }
        }

        allEvents.sort(Comparator.comparing(ForecastEvent::scheduledOn));

        return new ForecastSummary(
                Money.ofMinor(total30Minor, currency),
                Money.ofMinor(total60Minor, currency),
                Money.ofMinor(total90Minor, currency),
                count30,
                count60,
                count90,
                allEvents);
    }
}
