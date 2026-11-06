package dev.subscout.deals;

import dev.subscout.insight.InsightRepository;
import dev.subscout.platform.Money;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

@Service
public class DealsIntelligenceService {

    public record DealView(
            String id,
            String merchantId,
            String merchantName,
            String title,
            String description,
            String category,
            String offerType,
            Double discountPct,
            Money discountAmount,
            Money maxDiscount,
            Money minSpend,
            String requiredPaymentMethod,
            String requiredMembershipId,
            String promoCode,
            String validFrom,
            String validTo,
            String sourceUrl,
            String terms,
            String verificationStatus,
            // Personalized Intelligence Fields
            double relevanceScore,
            String relevanceReason,
            boolean isEligible,
            String eligibilityReason,
            Money estimatedSavings,
            boolean expiringSoon,
            long daysRemaining,
            String userStatus // 'available', 'saved', 'redeemed', 'dismissed'
    ) {}

    public record DealsSummaryView(
            int totalActiveDeals,
            int eligibleDealsCount,
            int savedDealsCount,
            int redeemedDealsCount,
            Money totalPotentialSavings,
            Money totalRealizedSavings,
            List<DealView> deals
    ) {}

    private final DealsRepository dealsRepository;
    private final InsightRepository insightRepository;
    private final DSLContext dsl;

    public DealsIntelligenceService(DealsRepository dealsRepository, InsightRepository insightRepository, DSLContext dsl) {
        this.dealsRepository = dealsRepository;
        this.insightRepository = insightRepository;
        this.dsl = dsl;
    }

    public DealsSummaryView getDealsSummary(String profileId, String currency) {
        List<DealsRepository.DealRecord> allDeals = dealsRepository.activeDeals();
        List<DealsRepository.InteractionRecord> interactions = dealsRepository.userInteractions(profileId);
        Map<String, DealsRepository.InteractionRecord> interactionMap = interactions.stream()
                .collect(Collectors.toMap(DealsRepository.InteractionRecord::dealId, i -> i, (a, b) -> a));

        // Get user's active subscriptions to match memberships
        List<InsightRepository.SubscriptionRow> subs = insightRepository.subscriptions(profileId).stream()
                .filter(s -> !"cancelled".equals(s.state()))
                .filter(s -> !"USER_CONFIRMED_CANCELLED".equals(s.cancellationStage()))
                .toList();

        Set<String> activeMembershipNames = subs.stream()
                .map(s -> s.displayName().toLowerCase())
                .collect(Collectors.toSet());

        // Get recent merchant spend history
        Map<String, Long> spendByMerchant = getMerchantSpend(profileId);
        Map<String, Integer> txnCountsByMerchant = getMerchantTxnCounts(profileId);

        LocalDate today = LocalDate.now();
        List<DealView> rankedDeals = new ArrayList<>();

        long potentialSavingsMinor = 0;
        long realizedSavingsMinor = 0;
        int eligibleCount = 0;
        int savedCount = 0;
        int redeemedCount = 0;

        for (DealsRepository.DealRecord d : allDeals) {
            DealsRepository.InteractionRecord interaction = interactionMap.get(d.id());
            String userStatus = interaction != null ? interaction.status() : "available";

            if ("dismissed".equals(userStatus)) {
                continue; // Do not show dismissed offers in the active deck
            }
            if ("saved".equals(userStatus)) savedCount++;
            if ("redeemed".equals(userStatus)) {
                redeemedCount++;
                realizedSavingsMinor += interaction.realizedSavingsMinor();
            }

            // Days remaining calculation
            long daysRemaining = 999;
            boolean expiringSoon = false;
            if (d.validTo() != null) {
                LocalDate expiry = LocalDate.parse(d.validTo());
                daysRemaining = ChronoUnit.DAYS.between(today, expiry);
                if (daysRemaining <= 7 && daysRemaining >= 0) {
                    expiringSoon = true;
                }
            }

            // Membership & Eligibility Verification
            boolean isEligible = true;
            String eligibilityReason = "Open to all verified users";
            if (d.requiredMembershipId() != null) {
                boolean hasMembership = activeMembershipNames.stream()
                        .anyMatch(m -> m.contains(d.requiredMembershipId().replace("-", " ")) || d.requiredMembershipId().contains(m));
                if (hasMembership) {
                    isEligible = true;
                    eligibilityReason = "Unlocked via your active " + d.merchantName() + " subscription";
                } else {
                    isEligible = false;
                    eligibilityReason = "Requires active " + d.merchantName() + " membership";
                }
            }

            // Relevance & Explanation Scoring
            double relevance = 0.50;
            String relevanceReason = "Verified offer in " + d.category().replace("_", " ");

            String normMerchant = d.merchantName().toLowerCase();
            int txnCnt = txnCountsByMerchant.getOrDefault(normMerchant, 0);
            long pastSpend = spendByMerchant.getOrDefault(normMerchant, 0L);

            if (txnCnt > 0) {
                relevance += 0.35;
                relevanceReason = String.format("Relevant because you frequently spend at %s (%d transactions in statement)", d.merchantName(), txnCnt);
            } else if (isEligible && d.requiredMembershipId() != null) {
                relevance += 0.30;
                relevanceReason = "Included with a membership you are already paying for";
            } else if (expiringSoon) {
                relevance += 0.15;
                relevanceReason = "Offer expires in " + daysRemaining + " days";
            }

            if (isEligible) eligibleCount++;

            // Accurate financial benefit calculation (Strict Non-Inflationary)
            long estSavings = calculateEstimatedSavings(
                    d.discountAmountMinor(),
                    d.discountPct(),
                    d.maxDiscountMinor(),
                    d.minSpendMinor(),
                    pastSpend,
                    txnCnt
            );

            if (userStatus.equals("available") || userStatus.equals("saved")) {
                potentialSavingsMinor += estSavings;
            }

            Money estMoney = Money.ofMinor(estSavings, currency);
            Money discMoney = Money.ofMinor(d.discountAmountMinor(), d.currency());
            Money maxMoney = d.maxDiscountMinor() != null ? Money.ofMinor(d.maxDiscountMinor(), d.currency()) : null;
            Money minSpendMoney = Money.ofMinor(d.minSpendMinor(), d.currency());

            rankedDeals.add(new DealView(
                    d.id(), d.merchantId(), d.merchantName(), d.title(), d.description(),
                    d.category(), d.offerType(), d.discountPct(), discMoney, maxMoney,
                    minSpendMoney, d.requiredPaymentMethod(), d.requiredMembershipId(),
                    d.promoCode(), d.validFrom(), d.validTo(), d.sourceUrl(), d.terms(),
                    d.verificationStatus(), relevance, relevanceReason, isEligible,
                    eligibilityReason, estMoney, expiringSoon, daysRemaining, userStatus
            ));
        }

        // Rank by user-status (saved first), then relevance score descending
        rankedDeals.sort((a, b) -> {
            if ("saved".equals(a.userStatus()) && !"saved".equals(b.userStatus())) return -1;
            if (!"saved".equals(a.userStatus()) && "saved".equals(b.userStatus())) return 1;
            return Double.compare(b.relevanceScore(), a.relevanceScore());
        });

        return new DealsSummaryView(
                rankedDeals.size(),
                eligibleCount,
                savedCount,
                redeemedCount,
                Money.ofMinor(potentialSavingsMinor, currency),
                Money.ofMinor(realizedSavingsMinor, currency),
                rankedDeals
        );
    }

    public void updateStatus(String profileId, String dealId, String status, Long realizedSavingsMinor, String note) {
        dealsRepository.setInteraction(profileId, dealId, status, realizedSavingsMinor != null ? realizedSavingsMinor : 0L, note);
    }

    private Map<String, Long> getMerchantSpend(String profileId) {
        return dsl.fetch("""
                select lower(coalesce(m.canonical_name, t.descriptor_normalized)) as m_name,
                       sum(t.amount_minor) as total_spend
                from txn t
                left join merchant m on m.id = t.merchant_id
                where t.profile_id = ? and t.direction = 'debit'
                group by m_name
                """, profileId)
                .stream()
                .collect(Collectors.toMap(
                        r -> r.get("m_name", String.class).toLowerCase(),
                        r -> r.get("total_spend", Long.class) != null ? r.get("total_spend", Long.class) : 0L,
                        (a, b) -> a + b));
    }

    private Map<String, Integer> getMerchantTxnCounts(String profileId) {
        return dsl.fetch("""
                select lower(coalesce(m.canonical_name, t.descriptor_normalized)) as m_name,
                       count(t.id) as cnt
                from txn t
                left join merchant m on m.id = t.merchant_id
                where t.profile_id = ? and t.direction = 'debit'
                group by m_name
                """, profileId)
                .stream()
                .collect(Collectors.toMap(
                        r -> r.get("m_name", String.class).toLowerCase(),
                        r -> r.get("cnt", Integer.class) != null ? r.get("cnt", Integer.class) : 0,
                        (a, b) -> a + b));
    }

    public static long calculateEstimatedSavings(
            Long discountAmountMinor,
            Double discountPct,
            Long maxDiscountMinor,
            Long minSpendMinor,
            long pastSpendMinor,
            int txnCount
    ) {
        if (discountAmountMinor != null && discountAmountMinor > 0) {
            return discountAmountMinor;
        }
        if (discountPct != null && discountPct > 0) {
            long referenceSpend = pastSpendMinor > 0 ? (pastSpendMinor / Math.max(1, txnCount)) : (minSpendMinor != null && minSpendMinor > 0 ? minSpendMinor : 100000L);
            long uncapped = Math.round(referenceSpend * (discountPct / 100.0));
            return maxDiscountMinor != null ? Math.min(uncapped, maxDiscountMinor) : uncapped;
        }
        return 0L;
    }

    public static double calculateRelevanceScore(
            String category,
            String merchantName,
            int txnCnt,
            boolean isEligible,
            boolean requiresMembership,
            boolean expiringSoon
    ) {
        double relevance = 0.50;
        if (txnCnt > 0) {
            relevance += 0.35;
        } else if (isEligible && requiresMembership) {
            relevance += 0.30;
        } else if (expiringSoon) {
            relevance += 0.15;
        }
        return Math.min(1.0, relevance);
    }
}
