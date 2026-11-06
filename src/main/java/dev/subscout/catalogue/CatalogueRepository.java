package dev.subscout.catalogue;

import dev.subscout.platform.Ids;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogueRepository {

    private final DSLContext dsl;

    public CatalogueRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record OfferMatch(String offerId, String providerName, String capability, long priceMinor,
                             String currency, int exponent, String billingPeriod) {
    }

    public record OfferRow(String planId, String planName, String capability, String region, long priceMinor,
                           String currency, int exponent, String billingPeriod, boolean taxInclusive,
                           String sourceUrl, String retrievedAt, String reviewStatus) {
    }

    public record ProviderRow(String id, String name, String category, String homepage) {
    }

    public record BundledBenefitRow(String id, String sourceType, String sourceId, String capability,
                                   String targetProviderId, String targetPlanId, String description,
                                   boolean activationRequired, String status, String termsUrl) {
    }

    public void ensureCitation(String id, String url, String retrievedAt, String reviewStatus, String reviewer) {
        dsl.execute("insert or ignore into source_citation(id, url, retrieved_at, review_status, reviewer) "
                        + "values (?, ?, ?, ?, ?)",
                id, url, retrievedAt, reviewStatus, reviewer);
    }

    public void ensureProvider(String id, String name, String category, String homepage) {
        dsl.execute("insert or ignore into provider(id, name, category, homepage) values (?, ?, ?, ?)",
                id, name, category, homepage);
    }

    public void ensurePlan(String id, String providerId, String name, String capability,
                           Integer storageGb, Integer seats, String maxResolution,
                           Integer simultaneousScreens, Boolean hasAds, Boolean familySharing,
                           String lossTradeoffsJson) {
        dsl.execute("""
                insert or replace into catalogue_plan(id, provider_id, name, capability, storage_gb, seats,
                                                     max_resolution, simultaneous_screens, has_ads, family_sharing, loss_tradeoffs)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, providerId, name, capability, storageGb, seats,
                maxResolution, simultaneousScreens, hasAds != null && hasAds ? 1 : 0,
                familySharing != null && familySharing ? 1 : 0, lossTradeoffsJson);
    }

    public void ensureBundledBenefit(String id, String sourceType, String sourceId, String capability,
                                    String targetProviderId, String targetPlanId, String description,
                                    boolean activationRequired, String status, String termsUrl) {
        dsl.execute("""
                insert or replace into bundled_benefit(id, source_type, source_id, capability,
                                                       target_provider_id, target_plan_id, description,
                                                       activation_required, status, terms_url, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
                """,
                id, sourceType, sourceId, capability, targetProviderId, targetPlanId,
                description, activationRequired ? 1 : 0, status, termsUrl);
    }

    public void ensureOffer(String id, String planId, String region, String currency, int exponent,
                            String billingPeriod, long priceMinor, boolean taxInclusive,
                            String validFrom, String validTo, String citationId) {
        dsl.execute("insert or ignore into plan_offer(id, plan_id, region, currency, exponent, billing_period, "
                        + "price_minor, tax_inclusive, valid_from, valid_to, citation_id) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, planId, region, currency, exponent, billingPeriod, priceMinor,
                taxInclusive ? 1 : 0, validFrom, validTo, citationId);
    }

    /**
     * Matches a merchant to the cheapest current offer in a region, preferring an exact
     * provider-name match over a containment match. Returns empty when nothing matches,
     * which is the honest outcome for an unlisted merchant.
     */
    /**
     * Matches a merchant and optional observed amount to the most appropriate current offer in a region.
     * Prefers matching the active billing period and typical price when known, ensuring accurate plan tier linkage.
     */
    public Optional<OfferMatch> match(String merchantName, String region, Long typicalAmountMinor, String cadence) {
        if (merchantName == null || merchantName.isBlank()) {
            return Optional.empty();
        }
        String today = LocalDate.now().toString();
        String period = cadence == null ? "monthly" : cadence.toLowerCase();
        return dsl.fetch("""
                        select o.id as offer_id, p.name as provider_name, pl.capability,
                               o.price_minor, o.currency, o.exponent, o.billing_period
                        from plan_offer o
                        join catalogue_plan pl on pl.id = o.plan_id
                        join provider p on p.id = pl.provider_id
                        where o.region = ?
                          and (o.valid_to is null or o.valid_to >= ?)
                          and (upper(p.name) = upper(?) or upper(?) like '%' || upper(p.name) || '%')
                        order by
                          case when ? is not null and o.price_minor = ? then 0 else 1 end,
                          case when o.billing_period = ? then 0 else 1 end,
                          case when upper(p.name) = upper(?) then 0 else 1 end,
                          o.price_minor asc
                        limit 1
                        """, region, today, merchantName, merchantName, typicalAmountMinor, typicalAmountMinor, period, merchantName)
                .stream()
                .findFirst()
                .map(row -> new OfferMatch(
                        text(row, "offer_id"), text(row, "provider_name"), text(row, "capability"),
                        longValue(row, "price_minor"), text(row, "currency"),
                        intValue(row, "exponent"), text(row, "billing_period")));
    }

    public Optional<OfferMatch> match(String merchantName, String region) {
        return match(merchantName, region, null, null);
    }

    public record PlanOfferDetail(
            String offerId,
            String providerId,
            String providerName,
            String planId,
            String planName,
            String capability,
            Integer storageGb,
            Integer seats,
            long priceMinor,
            String currency,
            int exponent,
            String billingPeriod,
            boolean taxInclusive,
            String sourceUrl,
            String retrievedAt,
            String reviewStatus,
            String validFrom,
            String validTo) {
    }

    public Optional<PlanOfferDetail> findEquivalentAnnualOffer(String planOfferId, String currency) {
        if (planOfferId == null || planOfferId.isBlank()) {
            return Optional.empty();
        }
        String today = LocalDate.now().toString();
        // Finds an annual offer for the EXACT SAME catalogue_plan
        return dsl.fetch("""
                        select o.id as offer_id, p.id as provider_id, p.name as provider_name,
                               pl.id as plan_id, pl.name as plan_name, pl.capability,
                               pl.storage_gb, pl.seats,
                               o.price_minor, o.currency, o.exponent, o.billing_period,
                               o.tax_inclusive, c.url as source_url, c.retrieved_at, c.review_status,
                               o.valid_from, o.valid_to
                        from plan_offer curr_o
                        join catalogue_plan pl on pl.id = curr_o.plan_id
                        join provider p on p.id = pl.provider_id
                        join plan_offer o on o.plan_id = pl.id
                        join source_citation c on c.id = o.citation_id
                        where curr_o.id = ?
                          and o.billing_period = 'annual'
                          and o.currency = ?
                          and (o.valid_to is null or o.valid_to >= ?)
                        order by o.price_minor asc
                        limit 1
                        """, planOfferId, currency, today)
                .stream()
                .findFirst()
                .map(row -> new PlanOfferDetail(
                        text(row, "offer_id"), text(row, "provider_id"), text(row, "provider_name"),
                        text(row, "plan_id"), text(row, "plan_name"), text(row, "capability"),
                        row.get("storage_gb") == null ? null : ((Number) row.get("storage_gb")).intValue(),
                        row.get("seats") == null ? null : ((Number) row.get("seats")).intValue(),
                        longValue(row, "price_minor"), text(row, "currency"),
                        intValue(row, "exponent"), text(row, "billing_period"),
                        intValue(row, "tax_inclusive") == 1,
                        text(row, "source_url"), text(row, "retrieved_at"), text(row, "review_status"),
                        text(row, "valid_from"), text(row, "valid_to")));
    }

    public Optional<PlanOfferDetail> offerDetail(String offerId) {
        if (offerId == null || offerId.isBlank()) {
            return Optional.empty();
        }
        return dsl.fetch("""
                        select o.id as offer_id, p.id as provider_id, p.name as provider_name,
                               pl.id as plan_id, pl.name as plan_name, pl.capability,
                               pl.storage_gb, pl.seats,
                               o.price_minor, o.currency, o.exponent, o.billing_period,
                               o.tax_inclusive, c.url as source_url, c.retrieved_at, c.review_status,
                               o.valid_from, o.valid_to
                        from plan_offer o
                        join catalogue_plan pl on pl.id = o.plan_id
                        join provider p on p.id = pl.provider_id
                        join source_citation c on c.id = o.citation_id
                        where o.id = ?
                        """, offerId)
                .stream().findFirst()
                .map(row -> new PlanOfferDetail(
                        text(row, "offer_id"), text(row, "provider_id"), text(row, "provider_name"),
                        text(row, "plan_id"), text(row, "plan_name"), text(row, "capability"),
                        row.get("storage_gb") == null ? null : ((Number) row.get("storage_gb")).intValue(),
                        row.get("seats") == null ? null : ((Number) row.get("seats")).intValue(),
                        longValue(row, "price_minor"), text(row, "currency"),
                        intValue(row, "exponent"), text(row, "billing_period"),
                        intValue(row, "tax_inclusive") == 1,
                        text(row, "source_url"), text(row, "retrieved_at"), text(row, "review_status"),
                        text(row, "valid_from"), text(row, "valid_to")));
    }

    public Optional<OfferMatch> offer(String offerId) {
        return dsl.fetch("""
                        select o.id as offer_id, p.name as provider_name, pl.capability,
                               o.price_minor, o.currency, o.exponent, o.billing_period
                        from plan_offer o
                        join catalogue_plan pl on pl.id = o.plan_id
                        join provider p on p.id = pl.provider_id
                        where o.id = ?
                        """, offerId)
                .stream().findFirst()
                .map(row -> new OfferMatch(
                        text(row, "offer_id"), text(row, "provider_name"), text(row, "capability"),
                        longValue(row, "price_minor"), text(row, "currency"),
                        intValue(row, "exponent"), text(row, "billing_period")));
    }

    public List<ProviderRow> providers() {
        return dsl.fetch("select id, name, category, homepage from provider order by name")
                .stream()
                .map(row -> new ProviderRow(text(row, "id"), text(row, "name"),
                        text(row, "category"), text(row, "homepage")))
                .toList();
    }

    public List<BundledBenefitRow> bundledBenefits() {
        return dsl.fetch("select id, source_type, source_id, capability, target_provider_id, target_plan_id, description, activation_required, status, terms_url from bundled_benefit order by id")
                .stream()
                .map(row -> new BundledBenefitRow(
                        text(row, "id"), text(row, "source_type"), text(row, "source_id"), text(row, "capability"),
                        text(row, "target_provider_id"), text(row, "target_plan_id"), text(row, "description"),
                        intValue(row, "activation_required") == 1, text(row, "status"), text(row, "terms_url")))
                .toList();
    }

    public List<OfferRow> offers() {
        return dsl.fetch("""
                        select pl.id as plan_id, pl.name as plan_name, pl.capability,
                               o.region, o.price_minor, o.currency, o.exponent, o.billing_period,
                               o.tax_inclusive, c.url as source_url, c.retrieved_at, c.review_status
                        from plan_offer o
                        join catalogue_plan pl on pl.id = o.plan_id
                        join source_citation c on c.id = o.citation_id
                        order by pl.name, o.region, o.price_minor
                        """)
                .stream()
                .map(row -> new OfferRow(
                        text(row, "plan_id"), text(row, "plan_name"), text(row, "capability"),
                        text(row, "region"), longValue(row, "price_minor"), text(row, "currency"),
                        intValue(row, "exponent"), text(row, "billing_period"),
                        intValue(row, "tax_inclusive") == 1, text(row, "source_url"),
                        text(row, "retrieved_at"), text(row, "review_status")))
                .toList();
    }

    public boolean hasProposedOffers() {
        return dsl.fetch("select count(*) as c from source_citation where review_status = 'proposed'")
                .stream().findFirst().map(row -> longValue(row, "c") > 0).orElse(false);
    }

    public Map<String, List<OfferRow>> offersByPlan() {
        Map<String, List<OfferRow>> grouped = new LinkedHashMap<>();
        for (OfferRow row : offers()) {
            grouped.computeIfAbsent(row.planId(), ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    public String deterministicOfferId(String planId, CatalogueData.Offer offer) {
        return Ids.sha256(planId + "|" + offer.region() + "|" + offer.currency() + "|"
                + offer.billingPeriod() + "|" + offer.validFrom());
    }

    private static String text(Record row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }

    private static long longValue(Record row, String column) {
        Object value = row.get(column);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static int intValue(Record row, String column) {
        Object value = row.get(column);
        return value == null ? 0 : ((Number) value).intValue();
    }
}
