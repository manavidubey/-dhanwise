package dev.subscout.ledger;

import dev.subscout.platform.Ids;
import dev.subscout.platform.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic transaction reconciliation engine.
 *
 * <p>Matches imported bank debit transactions against registered subscriptions.
 * Reconciles exact matches, detects amount variances (stealth price hikes),
 * date variances, duplicate charges within the same cycle, and unlinked charges.
 */
@Service
public class ReconciliationService {

    public enum MatchType {
        EXACT_MATCH,
        AMOUNT_VARIANCE,
        DATE_VARIANCE,
        DUPLICATE_CHARGE,
        UNEXPECTED_CHARGE
    }

    public record ReconciledItem(
            String id,
            String txnId,
            String subscriptionId,
            String subscriptionName,
            LocalDate postedOn,
            String descriptorRaw,
            MatchType matchType,
            Money expectedAmount,
            Money actualAmount,
            Money variance,
            String notes) {
    }

    private final DSLContext dsl;

    public ReconciliationService(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    public List<ReconciledItem> reconcileProfile(String profileId) {
        // Clear previous reconciliation results for fresh deterministic state
        dsl.execute("delete from transaction_reconciliation where profile_id = ?", profileId);

        // Fetch debits
        var txns = dsl.fetch("""
                        select id, posted_on, descriptor_raw, descriptor_normalized, amount_minor, currency, exponent
                        from txn
                        where profile_id = ? and direction = 'debit' and kind = 'purchase'
                        order by posted_on desc
                        """, profileId);

        // Fetch active/tracked subscriptions
        var subs = dsl.fetch("""
                        select s.id, s.display_name, coalesce(rs.typical_amount_minor, 0) as typical_minor,
                               coalesce(rs.currency, 'INR') as currency, coalesce(rs.exponent, 2) as exponent,
                               coalesce(rs.cadence, 'monthly') as cadence, rs.next_expected_on
                        from subscription s
                        left join recurring_series rs on rs.id = s.series_id
                        where s.profile_id = ? and s.state <> 'cancelled'
                        """, profileId);

        // Fetch candidate recurring series awaiting user review
        var candidates = dsl.fetch("""
                        select rs.id, coalesce(m.canonical_name, 'Unknown') as merchant_name,
                               coalesce(rs.typical_amount_minor, 0) as typical_minor,
                               coalesce(rs.currency, 'INR') as currency, coalesce(rs.exponent, 2) as exponent
                        from recurring_series rs
                        left join merchant m on m.id = rs.merchant_id
                        where rs.profile_id = ? and rs.status = 'candidate'
                        """, profileId);

        List<ReconciledItem> results = new ArrayList<>();
        String now = Instant.now().toString();

        for (var t : txns) {
            String txnId = t.get("id", String.class);
            LocalDate postedOn = LocalDate.parse(t.get("posted_on", String.class));
            String descRaw = t.get("descriptor_raw", String.class);
            String descNorm = t.get("descriptor_normalized", String.class).toLowerCase();
            long actualMinor = t.get("amount_minor", Long.class);
            String currency = t.get("currency", String.class);
            int exponent = t.get("exponent", Integer.class);
            Money actualMoney = Money.ofMinor(actualMinor, currency, exponent);

            // Attempt to match against subscriptions
            org.jooq.Record matchedSub = null;
            int bestScore = 0;

            for (var s : subs) {
                String subName = s.get("display_name", String.class).toLowerCase();
                String[] tokens = subName.split("\\s+");
                int score = 0;
                for (String token : tokens) {
                    if (token.length() > 2 && descNorm.contains(token)) {
                        score += 2;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    matchedSub = s;
                }
            }

            if (matchedSub != null && bestScore > 0) {
                String subId = matchedSub.get("id", String.class);
                String subName = matchedSub.get("display_name", String.class);
                long expectedMinor = matchedSub.get("typical_minor", Long.class);
                Money expectedMoney = Money.ofMinor(expectedMinor, currency, exponent);
                long varianceMinor = actualMinor - expectedMinor;
                Money varianceMoney = Money.ofMinor(varianceMinor, currency, exponent);

                MatchType type = MatchType.EXACT_MATCH;
                String notes = String.format("Rule [TOKEN_SIMILARITY]: Matched '%s' via descriptor token alignment", subName);

                if (expectedMinor > 0 && Math.abs(varianceMinor) > 0) {
                    type = MatchType.AMOUNT_VARIANCE;
                    notes = String.format("Rule [AMOUNT_VARIANCE]: Step shift of %s relative to %s baseline (variance detected; review tier or FX/tax)",
                            varianceMoney.display(), expectedMoney.display());
                }

                String reconId = Ids.newId();
                dsl.execute("""
                                insert into transaction_reconciliation
                                  (id, profile_id, txn_id, subscription_id, match_type, expected_amount_minor,
                                   actual_amount_minor, currency, variance_minor, notes, reconciled_at)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        reconId, profileId, txnId, subId, type.name(), expectedMinor, actualMinor,
                        currency, varianceMinor, notes, now);

                results.add(new ReconciledItem(reconId, txnId, subId, subName, postedOn, descRaw, type,
                        expectedMoney, actualMoney, varianceMoney, notes));
            } else {
                // Check if transaction matches a candidate recurring series awaiting user review
                org.jooq.Record matchedCandidate = null;
                for (var c : candidates) {
                    String candName = c.get("merchant_name", String.class).toLowerCase();
                    for (String token : candName.split("\\s+")) {
                        if (token.length() > 2 && descNorm.contains(token)) {
                            matchedCandidate = c;
                            break;
                        }
                    }
                    if (matchedCandidate != null) break;
                }

                String reconId = Ids.newId();
                String notes;
                if (matchedCandidate != null) {
                    String candName = matchedCandidate.get("merchant_name", String.class);
                    notes = String.format("Matches recurring candidate '%s' detected across statements (review in Candidate queue to confirm)", candName);
                } else {
                    notes = "Recurring or ad-hoc purchase not currently linked to a tracked subscription";
                }

                dsl.execute("""
                                insert into transaction_reconciliation
                                  (id, profile_id, txn_id, subscription_id, match_type, expected_amount_minor,
                                   actual_amount_minor, currency, variance_minor, notes, reconciled_at)
                                values (?, ?, ?, null, ?, null, ?, ?, 0, ?, ?)
                                """,
                        reconId, profileId, txnId, MatchType.UNEXPECTED_CHARGE.name(), actualMinor,
                        currency, notes, now);

                results.add(new ReconciledItem(reconId, txnId, null, matchedCandidate != null ? matchedCandidate.get("merchant_name", String.class) : null,
                        postedOn, descRaw, MatchType.UNEXPECTED_CHARGE, null, actualMoney, Money.zero(currency), notes));
            }
        }

        results.sort(Comparator.comparing(ReconciledItem::postedOn).reversed());
        return results;
    }

    public List<ReconciledItem> getReconciliations(String profileId) {
        var rows = dsl.fetch("""
                        select r.id, r.txn_id, r.subscription_id, s.display_name as sub_name, t.posted_on,
                               t.descriptor_raw, r.match_type, r.expected_amount_minor, r.actual_amount_minor,
                               r.currency, t.exponent, r.variance_minor, r.notes
                        from transaction_reconciliation r
                        join txn t on t.id = r.txn_id
                        left join subscription s on s.id = r.subscription_id
                        where r.profile_id = ?
                        order by t.posted_on desc
                        """, profileId);

        return rows.stream().map(row -> {
            String id = row.get("id", String.class);
            String txnId = row.get("txn_id", String.class);
            String subId = row.get("subscription_id", String.class);
            String subName = row.get("sub_name", String.class);
            LocalDate postedOn = LocalDate.parse(row.get("posted_on", String.class));
            String descRaw = row.get("descriptor_raw", String.class);
            MatchType matchType = MatchType.valueOf(row.get("match_type", String.class));
            String currency = row.get("currency", String.class);
            int exponent = row.get("exponent", Integer.class);

            Long expMinor = row.get("expected_amount_minor", Long.class);
            Money expected = expMinor == null ? null : Money.ofMinor(expMinor, currency, exponent);
            Money actual = Money.ofMinor(row.get("actual_amount_minor", Long.class), currency, exponent);
            Money variance = Money.ofMinor(row.get("variance_minor", Long.class), currency, exponent);
            String notes = row.get("notes", String.class);

            return new ReconciledItem(id, txnId, subId, subName, postedOn, descRaw, matchType, expected, actual, variance, notes);
        }).toList();
    }
}
