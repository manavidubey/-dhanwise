package dev.subscout.deals;

import dev.subscout.platform.Ids;
import dev.subscout.platform.Money;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DealsRepository implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DealsRepository.class);

    public record DealRecord(
            String id,
            String merchantId,
            String merchantName,
            String title,
            String description,
            String category,
            String offerType,
            Double discountPct,
            long discountAmountMinor,
            Long maxDiscountMinor,
            long minSpendMinor,
            String currency,
            String requiredPaymentMethod,
            String requiredMembershipId,
            String promoCode,
            String validFrom,
            String validTo,
            String sourceUrl,
            String terms,
            String verificationStatus
    ) {}

    public record InteractionRecord(
            String id,
            String profileId,
            String dealId,
            String status,
            String savedAt,
            String redeemedAt,
            long realizedSavingsMinor,
            String userNote
    ) {}

    private final DSLContext dsl;
    private final ObjectMapper mapper;

    public DealsRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (InputStream input = getClass().getResourceAsStream("/catalogue/deals.json")) {
            if (input == null) {
                log.warn("No bundled deals catalogue found");
                return;
            }
            List<DealRecord> deals = mapper.readValue(input, new TypeReference<>() {});
            for (DealRecord d : deals) {
                ensureDeal(d);
            }
            log.info("Deals repository initialized with {} verified offers", deals.size());
        } catch (Exception e) {
            log.error("Failed to seed deals catalogue", e);
        }
    }

    public void ensureDeal(DealRecord d) {
        String now = Instant.now().toString();
        dsl.execute("""
                insert or replace into deal_offer
                  (id, merchant_id, merchant_name, title, description, category, offer_type,
                   discount_pct, discount_amount_minor, max_discount_minor, min_spend_minor,
                   currency, required_payment_method, required_membership_id, promo_code,
                   valid_from, valid_to, source_url, terms, verification_status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                d.id(), d.merchantId(), d.merchantName(), d.title(), d.description(), d.category(),
                d.offerType(), d.discountPct(), d.discountAmountMinor(), d.maxDiscountMinor(),
                d.minSpendMinor(), d.currency(), d.requiredPaymentMethod(), d.requiredMembershipId(),
                d.promoCode(), d.validFrom(), d.validTo(), d.sourceUrl(), d.terms(),
                d.verificationStatus(), now);
    }

    public List<DealRecord> activeDeals() {
        String today = LocalDate.now().toString();
        return dsl.fetch("""
                select id, merchant_id, merchant_name, title, description, category, offer_type,
                       discount_pct, discount_amount_minor, max_discount_minor, min_spend_minor,
                       currency, required_payment_method, required_membership_id, promo_code,
                       valid_from, valid_to, source_url, terms, verification_status
                from deal_offer
                where valid_from <= ? and (valid_to is null or valid_to >= ?)
                order by category, merchant_name
                """, today, today)
                .stream()
                .map(this::mapDeal)
                .toList();
    }

    public Optional<DealRecord> findById(String id) {
        return dsl.fetch("""
                select id, merchant_id, merchant_name, title, description, category, offer_type,
                       discount_pct, discount_amount_minor, max_discount_minor, min_spend_minor,
                       currency, required_payment_method, required_membership_id, promo_code,
                       valid_from, valid_to, source_url, terms, verification_status
                from deal_offer where id = ?
                """, id)
                .stream()
                .findFirst()
                .map(this::mapDeal);
    }

    public List<InteractionRecord> userInteractions(String profileId) {
        return dsl.fetch("""
                select id, profile_id, deal_id, status, saved_at, redeemed_at, realized_savings_minor, user_note
                from user_deal_interaction where profile_id = ?
                """, profileId)
                .stream()
                .map(r -> new InteractionRecord(
                        r.get("id", String.class),
                        r.get("profile_id", String.class),
                        r.get("deal_id", String.class),
                        r.get("status", String.class),
                        r.get("saved_at", String.class),
                        r.get("redeemed_at", String.class),
                        r.get("realized_savings_minor", Long.class) != null ? r.get("realized_savings_minor", Long.class) : 0L,
                        r.get("user_note", String.class)))
                .toList();
    }

    public void setInteraction(String profileId, String dealId, String status, long realizedSavingsMinor, String note) {
        if ("available".equalsIgnoreCase(status) || "unsaved".equalsIgnoreCase(status) || "none".equalsIgnoreCase(status)) {
            dsl.execute("delete from user_deal_interaction where profile_id = ? and deal_id = ?", profileId, dealId);
            return;
        }

        String id = Ids.sha256(profileId + "|" + dealId);
        String now = Instant.now().toString();
        String savedAt = "saved".equals(status) ? now : null;
        String redeemedAt = "redeemed".equals(status) ? now : null;

        dsl.execute("""
                insert into user_deal_interaction
                  (id, profile_id, deal_id, status, saved_at, redeemed_at, realized_savings_minor, user_note, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(profile_id, deal_id) do update set
                  status = excluded.status,
                  saved_at = coalesce(excluded.saved_at, user_deal_interaction.saved_at),
                  redeemed_at = coalesce(excluded.redeemed_at, user_deal_interaction.redeemed_at),
                  realized_savings_minor = excluded.realized_savings_minor,
                  user_note = coalesce(excluded.user_note, user_deal_interaction.user_note)
                """, id, profileId, dealId, status, savedAt, redeemedAt, realizedSavingsMinor, note, now);
    }

    private DealRecord mapDeal(Record r) {
        return new DealRecord(
                r.get("id", String.class),
                r.get("merchant_id", String.class),
                r.get("merchant_name", String.class),
                r.get("title", String.class),
                r.get("description", String.class),
                r.get("category", String.class),
                r.get("offer_type", String.class),
                r.get("discount_pct", Double.class),
                r.get("discount_amount_minor", Long.class) != null ? r.get("discount_amount_minor", Long.class) : 0L,
                r.get("max_discount_minor", Long.class),
                r.get("min_spend_minor", Long.class) != null ? r.get("min_spend_minor", Long.class) : 0L,
                r.get("currency", String.class),
                r.get("required_payment_method", String.class),
                r.get("required_membership_id", String.class),
                r.get("promo_code", String.class),
                r.get("valid_from", String.class),
                r.get("valid_to", String.class),
                r.get("source_url", String.class),
                r.get("terms", String.class),
                r.get("verification_status", String.class)
        );
    }
}
