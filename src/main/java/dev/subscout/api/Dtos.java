package dev.subscout.api;

import dev.subscout.platform.Money;
import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record MoneyView(long minor, String currency, int exponent, String display) {
        public static MoneyView of(Money money) {
            return money == null ? null
                    : new MoneyView(money.minor(), money.currency(), money.exponent(), money.display());
        }

        public static MoneyView zero(String currency) {
            return of(Money.zero(currency));
        }
    }

    public record SubscriptionView(
            String id,
            String seriesId,
            String name,
            String state,
            String cancellationStage,
            String cadence,
            MoneyView typical,
            MoneyView annualized,
            String nextExpected,
            double confidence,
            boolean variableAmount,
            String capability) {
    }

    public record SeriesView(
            String id,
            String merchantName,
            String cadence,
            MoneyView typical,
            String nextExpected,
            double confidence,
            boolean variableAmount,
            String priceChange) {
    }

    public record RenewalView(String subscriptionId, String name, String on, MoneyView amount, long daysRemaining) {
    }

    public record RecommendationView(
            String id,
            String kind,
            String title,
            String detail,
            String savingsClass,
            MoneyView amount,
            double confidence,
            String evidence,
            String assumptions) {
    }

    public record SavingsView(
            MoneyView potential,
            MoneyView estimated,
            MoneyView confirmed,
            MoneyView realized) {
    }

    public record CountsView(int transactions, int subscriptions, int candidates) {
    }

    public record OverviewView(
            String profileName,
            String currency,
            boolean catalogueUnverified,
            CountsView counts,
            List<SubscriptionView> subscriptions,
            List<SeriesView> candidates,
            List<RenewalView> renewals,
            List<RecommendationView> recommendations,
            SavingsView savings) {
    }

    public record ImportResultView(
            String importId,
            int totalRows,
            int imported,
            int duplicates,
            boolean duplicateFile,
            List<String> errors) {
    }

    public record OfferView(
            String region,
            MoneyView price,
            String billingPeriod,
            boolean taxInclusive,
            String sourceUrl,
            String retrievedAt,
            String reviewStatus) {
    }

    public record PlanView(String id, String name, String capability, List<OfferView> offers) {
    }

    public record ProviderView(String id, String name, String category, String homepage, List<PlanView> plans) {
    }

    public record CreateSubscriptionRequest(
            String name,
            String cadence,
            String amount,
            String currency,
            String nextExpected,
            String capability) {
    }

    public record TriageRecommendationRequest(
            String status,
            String actionTaken,
            Integer snoozeDays) {
    }

    public record PerkView(
            String id,
            String sourcePlanId,
            String sourceName,
            String capability,
            String targetProviderId,
            String targetPlanId,
            String description,
            boolean activationRequired,
            String status,
            String termsUrl,
            boolean heldByUser) {
    }

    public record ReconciledItemView(
            String id,
            String txnId,
            String subscriptionId,
            String subscriptionName,
            String postedOn,
            String descriptorRaw,
            String matchType,
            MoneyView expectedAmount,
            MoneyView actualAmount,
            MoneyView variance,
            String notes) {
    }

    public record ForecastEventView(
            String subscriptionId,
            String name,
            String scheduledOn,
            String billingFrequency,
            MoneyView amount,
            String eventType,
            String inclusionReason,
            long daysFromNow) {
    }

    public record CashflowForecastView(
            MoneyView horizon30Days,
            MoneyView horizon60Days,
            MoneyView horizon90Days,
            int eventCount30Days,
            int eventCount60Days,
            int eventCount90Days,
            List<ForecastEventView> upcomingEvents) {
    }

    public record UpdateProfileRequest(String displayName, String currency) {
    }

    public record CatalogueView(String note, List<ProviderView> providers) {
    }
}
