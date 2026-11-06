package dev.subscout.catalogue;

import java.util.List;

/**
 * Shape of {@code catalogue/catalogue.json}. The catalogue is reviewed data with a citation
 * per offer, not scraper output: every price can be traced to a source and a retrieval date.
 */
public final class CatalogueData {

    private CatalogueData() {
    }

    public record File(String version, String region, List<Provider> providers) {
    }

    public record Provider(String id, String name, String category, String homepage, List<Plan> plans) {
    }

    public record BundledPerk(
            String capability,
            String targetProviderId,
            String targetPlanId,
            String description,
            boolean activationRequired,
            String status,
            String termsUrl) {
    }

    public record Plan(
            String id,
            String name,
            String capability,
            Integer storageGb,
            Integer seats,
            String maxResolution,
            Integer simultaneousScreens,
            Boolean hasAds,
            Boolean familySharing,
            List<String> lossTradeoffs,
            List<BundledPerk> bundledPerks,
            List<Offer> offers) {
    }

    public record Offer(
            String region,
            String currency,
            String billingPeriod,
            String price,
            boolean taxInclusive,
            String validFrom,
            String validTo,
            Source source) {
    }

    public record Source(String url, String retrievedAt, String reviewStatus, String reviewer) {
    }
}
