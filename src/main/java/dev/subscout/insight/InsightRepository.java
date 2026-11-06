package dev.subscout.insight;

import dev.subscout.platform.Ids;
import dev.subscout.recurring.Cadence;
import dev.subscout.recurring.RecurrenceDetector;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class InsightRepository {

    private final DSLContext dsl;

    public InsightRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record SeriesRow(
            String id,
            String profileId,
            String merchantId,
            String merchantName,
            String cadence,
            long typicalMinor,
            String currency,
            int exponent,
            String status,
            double confidence,
            boolean variableAmount,
            LocalDate nextExpected,
            Long previousMinor,
            Long currentMinor,
            Double changePct) {
    }

    public record SubscriptionRow(
            String id,
            String profileId,
            String seriesId,
            String displayName,
            String state,
            String cancellationStage,
            String cadence,
            long typicalMinor,
            String currency,
            int exponent,
            LocalDate nextExpected,
            double confidence,
            boolean variableAmount,
            String capability,
            String planOfferId,
            LocalDate trialEndsOn) {
    }

    public record RecommendationRow(
            String id,
            String kind,
            String title,
            String detail,
            String savingsClass,
            Long amountMinor,
            String currency,
            Integer exponent,
            double confidence,
            String evidence,
            String assumptions) {
    }

    public record SavingsRow(String kind, long amountMinor, String currency, int exponent) {
    }

    public Map<String, String> nonCandidateStatuses(String profileId) {
        Map<String, String> statuses = new LinkedHashMap<>();
        dsl.fetch("select id, status from recurring_series where profile_id = ? and status <> 'candidate'", profileId)
                .forEach(row -> statuses.put(text(row, "id"), text(row, "status")));
        return statuses;
    }

    public void deleteCandidates(String profileId) {
        dsl.execute("delete from recurring_series where profile_id = ? and status = 'candidate'", profileId);
    }

    public void upsertSeries(String id, String profileId, RecurrenceDetector.Series series, String status) {
        String changedOn = series.priceChange() != null ? series.priceChange().changedOn().toString() : null;
        Long prevMinor = series.priceChange() != null ? series.priceChange().previous().minor() : null;
        Long currMinor = series.priceChange() != null ? series.priceChange().current().minor() : null;
        Double changePct = series.priceChange() != null && series.priceChange().previous().minor() > 0
                ? ((double) (currMinor - prevMinor) / prevMinor) * 100.0 : null;

        int updated = dsl.execute("""
                        update recurring_series
                        set typical_amount_minor = ?, low_amount_minor = ?, high_amount_minor = ?, variable_amount = ?,
                            occurrences = ?, first_seen_on = ?, last_seen_on = ?, next_expected_on = ?, confidence = ?,
                            price_changed_on = ?, previous_amount_minor = ?, current_amount_minor = ?, price_change_pct = ?
                        where id = ?
                        """,
                series.typical().minor(), series.low().minor(), series.high().minor(),
                series.variableAmount() ? 1 : 0, series.occurrences(), series.firstSeen().toString(),
                series.lastSeen().toString(),
                series.nextExpected() == null ? null : series.nextExpected().toString(),
                series.confidence(), changedOn, prevMinor, currMinor, changePct, id);
        if (updated == 0) {
            dsl.execute("""
                            insert into recurring_series
                              (id, profile_id, merchant_id, cadence, currency, exponent, typical_amount_minor,
                               low_amount_minor, high_amount_minor, variable_amount, occurrences, first_seen_on,
                               last_seen_on, next_expected_on, confidence, status, created_at,
                               price_changed_on, previous_amount_minor, current_amount_minor, price_change_pct)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    id, profileId, series.merchantId(), series.cadence().wire(), series.typical().currency(),
                    series.typical().exponent(), series.typical().minor(), series.low().minor(), series.high().minor(),
                    series.variableAmount() ? 1 : 0, series.occurrences(), series.firstSeen().toString(),
                    series.lastSeen().toString(),
                    series.nextExpected() == null ? null : series.nextExpected().toString(),
                    series.confidence(), status, Instant.now().toString(),
                    changedOn, prevMinor, currMinor, changePct);
        }
    }

    public List<SeriesRow> series(String profileId, String status) {
        return dsl.fetch("""
                        select rs.id, rs.profile_id, rs.merchant_id, coalesce(m.canonical_name, 'Unknown') as merchant_name,
                               rs.cadence, rs.typical_amount_minor, rs.currency, rs.exponent, rs.status,
                               rs.confidence, rs.variable_amount, rs.next_expected_on,
                               rs.previous_amount_minor, rs.current_amount_minor, rs.price_change_pct
                        from recurring_series rs
                        left join merchant m on m.id = rs.merchant_id
                        where rs.profile_id = ? and rs.status = ?
                        order by rs.confidence desc
                        """, profileId, status)
                .stream()
                .map(row -> new SeriesRow(
                        text(row, "id"), text(row, "profile_id"), text(row, "merchant_id"), text(row, "merchant_name"),
                        text(row, "cadence"), numberLong(row, "typical_amount_minor"), text(row, "currency"),
                        number(row, "exponent"), text(row, "status"), decimal(row, "confidence"),
                        number(row, "variable_amount") == 1,
                        date(row, "next_expected_on"),
                        row.get("previous_amount_minor") == null ? null : ((Number) row.get("previous_amount_minor")).longValue(),
                        row.get("current_amount_minor") == null ? null : ((Number) row.get("current_amount_minor")).longValue(),
                        row.get("price_change_pct") == null ? null : ((Number) row.get("price_change_pct")).doubleValue()))
                .toList();
    }

    public Optional<SeriesRow> series(String id) {
        return dsl.fetch("""
                        select rs.id, rs.profile_id, rs.merchant_id, coalesce(m.canonical_name, 'Unknown') as merchant_name,
                               rs.cadence, rs.typical_amount_minor, rs.currency, rs.exponent, rs.status,
                               rs.confidence, rs.variable_amount, rs.next_expected_on,
                               rs.previous_amount_minor, rs.current_amount_minor, rs.price_change_pct
                        from recurring_series rs
                        left join merchant m on m.id = rs.merchant_id
                        where rs.id = ?
                        """, id)
                .stream().findFirst()
                .map(row -> new SeriesRow(
                        text(row, "id"), text(row, "profile_id"), text(row, "merchant_id"), text(row, "merchant_name"),
                        text(row, "cadence"), numberLong(row, "typical_amount_minor"), text(row, "currency"),
                        number(row, "exponent"), text(row, "status"), decimal(row, "confidence"),
                        number(row, "variable_amount") == 1, date(row, "next_expected_on"),
                        row.get("previous_amount_minor") == null ? null : ((Number) row.get("previous_amount_minor")).longValue(),
                        row.get("current_amount_minor") == null ? null : ((Number) row.get("current_amount_minor")).longValue(),
                        row.get("price_change_pct") == null ? null : ((Number) row.get("price_change_pct")).doubleValue()));
    }

    public void setSeriesStatus(String id, String status) {
        dsl.execute("update recurring_series set status = ? where id = ?", status, id);
    }

    public boolean insertSubscriptionIfAbsent(String id, String profileId, String seriesId, String planOfferId,
                                              String displayName, String state, LocalDate nextExpected) {
        boolean exists = dsl.fetch("select id from subscription where series_id = ?", seriesId).isNotEmpty();
        if (exists) {
            return false;
        }
        dsl.execute("""
                        insert into subscription
                          (id, profile_id, series_id, plan_offer_id, display_name, state, started_on,
                           trial_ends_on, current_period_end, cancel_at, seats, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, null, ?, null, 1, ?)
                        """,
                id, profileId, seriesId, planOfferId, displayName, state,
                LocalDate.now().toString(),
                nextExpected == null ? null : nextExpected.toString(),
                Instant.now().toString());
        return true;
    }

    public List<SubscriptionRow> subscriptions(String profileId) {
        return dsl.fetch("""
                        select s.id, s.profile_id, s.series_id, s.display_name, s.state,
                               coalesce(s.cancellation_stage, 'NONE') as cancellation_stage,
                               coalesce(rs.cadence, 'variable') as cadence,
                               coalesce(rs.typical_amount_minor, 0) as typical_minor,
                               coalesce(rs.currency, 'INR') as currency,
                               coalesce(rs.exponent, 2) as exponent,
                               rs.next_expected_on, coalesce(rs.confidence, 0) as confidence,
                               coalesce(rs.variable_amount, 0) as variable_amount,
                               pl.capability, s.plan_offer_id, s.trial_ends_on
                        from subscription s
                        left join recurring_series rs on rs.id = s.series_id
                        left join plan_offer o on o.id = s.plan_offer_id
                        left join catalogue_plan pl on pl.id = o.plan_id
                        where s.profile_id = ?
                        order by rs.next_expected_on nulls last, s.display_name
                        """, profileId)
                .stream()
                .map(row -> new SubscriptionRow(
                        text(row, "id"), text(row, "profile_id"), text(row, "series_id"),
                        text(row, "display_name"), text(row, "state"), text(row, "cancellation_stage"),
                        text(row, "cadence"), numberLong(row, "typical_minor"), text(row, "currency"), number(row, "exponent"),
                        date(row, "next_expected_on"), decimal(row, "confidence"),
                        number(row, "variable_amount") == 1, text(row, "capability"),
                        text(row, "plan_offer_id"), date(row, "trial_ends_on")))
                .toList();
    }

    public Optional<SubscriptionRow> subscription(String id) {
        return dsl.fetch("""
                        select s.id, s.profile_id, s.series_id, s.display_name, s.state,
                               coalesce(s.cancellation_stage, 'NONE') as cancellation_stage,
                               coalesce(rs.cadence, 'variable') as cadence,
                               coalesce(rs.typical_amount_minor, 0) as typical_minor,
                               coalesce(rs.currency, 'INR') as currency,
                               coalesce(rs.exponent, 2) as exponent,
                               rs.next_expected_on, coalesce(rs.confidence, 0) as confidence,
                               coalesce(rs.variable_amount, 0) as variable_amount,
                               pl.capability, s.plan_offer_id, s.trial_ends_on
                        from subscription s
                        left join recurring_series rs on rs.id = s.series_id
                        left join plan_offer o on o.id = s.plan_offer_id
                        left join catalogue_plan pl on pl.id = o.plan_id
                        where s.id = ?
                        """, id)
                .stream().findFirst()
                .map(row -> new SubscriptionRow(
                        text(row, "id"), text(row, "profile_id"), text(row, "series_id"),
                        text(row, "display_name"), text(row, "state"), text(row, "cancellation_stage"),
                        text(row, "cadence"), numberLong(row, "typical_minor"), text(row, "currency"), number(row, "exponent"),
                        date(row, "next_expected_on"), decimal(row, "confidence"),
                        number(row, "variable_amount") == 1, text(row, "capability"),
                        text(row, "plan_offer_id"), date(row, "trial_ends_on")));
    }

    public void setSubscriptionState(String id, String state) {
        dsl.execute("update subscription set state = ? where id = ?", state, id);
    }

    public void updateCancellationStage(String id, String stage) {
        String now = LocalDate.now().toString();
        if ("CANCELLATION_REQUESTED".equals(stage)) {
            dsl.execute("update subscription set state = 'active', cancellation_stage = ?, cancellation_requested_at = ? where id = ?", stage, now, id);
        } else if ("USER_CONFIRMED_CANCELLED".equals(stage)) {
            dsl.execute("update subscription set state = 'cancelled', cancellation_stage = ?, confirmed_cancelled_at = ? where id = ?", stage, now, id);
        } else if ("REALIZED_SAVINGS".equals(stage)) {
            dsl.execute("update subscription set state = 'cancelled', cancellation_stage = ?, stopped_verified_at = ? where id = ?", stage, now, id);
        } else {
            dsl.execute("update subscription set cancellation_stage = ? where id = ?", stage, id);
        }
    }

    public void createManualSubscription(String id, String profileId, String displayName, String cadence,
                                         long typicalMinor, String currency, int exponent,
                                         LocalDate nextExpected, String capability) {
        String now = Instant.now().toString();
        // create a dedicated merchant entry if not present
        String merchantId = "manual-" + Ids.sha256(displayName.toLowerCase()).substring(0, 16);
        dsl.execute("insert or ignore into merchant(id, canonical_name, category, created_at) values (?, ?, ?, ?)",
                merchantId, displayName, capability != null ? capability : "other", now);
        // create recurring series entry
        String seriesId = "series-manual-" + id;
        dsl.execute("""
                insert or replace into recurring_series
                  (id, profile_id, merchant_id, cadence, currency, exponent, typical_amount_minor,
                   low_amount_minor, high_amount_minor, variable_amount, occurrences, first_seen_on,
                   last_seen_on, next_expected_on, confidence, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 12, ?, ?, ?, 1.0, 'confirmed', ?)
                """,
                seriesId, profileId, merchantId, cadence, currency, exponent, typicalMinor,
                typicalMinor, typicalMinor, LocalDate.now().minusMonths(1).toString(),
                LocalDate.now().toString(), nextExpected != null ? nextExpected.toString() : null, now);
        // create subscription
        dsl.execute("""
                insert or replace into subscription
                  (id, profile_id, series_id, plan_offer_id, display_name, state, started_on,
                   trial_ends_on, current_period_end, cancel_at, seats, cancellation_stage, created_at)
                values (?, ?, ?, null, ?, 'active', ?, null, ?, null, 1, 'NONE', ?)
                """,
                id, profileId, seriesId, displayName, LocalDate.now().toString(),
                nextExpected != null ? nextExpected.toString() : null, now);
    }

    public void deleteSubscription(String id) {
        dsl.execute("delete from subscription where id = ?", id);
    }

    public void updateRecommendationStatus(String id, String status, String actionTaken, Integer snoozeDays) {
        String now = Instant.now().toString();
        String snoozedUntil = snoozeDays != null && snoozeDays > 0 ? LocalDate.now().plusDays(snoozeDays).toString() : null;
        // The V1 table check constraint allows: 'open', 'accepted', 'dismissed'.
        // For snoozed or irrelevant, we store status='dismissed' with action_taken and snoozed_until.
        String dbStatus = ("open".equalsIgnoreCase(status) || "accepted".equalsIgnoreCase(status)) ? status.toLowerCase() : "dismissed";
        dsl.execute("""
                update recommendation
                set status = ?, action_taken = ?, action_taken_at = ?, snoozed_until = ?
                where id = ?
                """, dbStatus, actionTaken != null ? actionTaken : status, now, snoozedUntil, id);
    }

    public int countSubscriptions(String profileId) {
        return dsl.fetch("select count(*) as c from subscription where profile_id = ? and state <> 'cancelled'", profileId)
                .stream().findFirst().map(row -> number(row, "c")).orElse(0);
    }

    public int countCandidates(String profileId) {
        return dsl.fetch("select count(*) as c from recurring_series where profile_id = ? and status = 'candidate'", profileId)
                .stream().findFirst().map(row -> number(row, "c")).orElse(0);
    }

    public void deleteOpenRecommendations(String profileId) {
        dsl.execute("delete from recommendation where profile_id = ? and status = 'open'", profileId);
    }

    public void insertRecommendation(String id, String profileId, String kind, String title, String detail,
                                     String savingsClass, Long amountMinor, String currency, Integer exponent,
                                     double confidence, String evidence, String assumptions) {
        dsl.execute("""
                        insert into recommendation
                          (id, profile_id, kind, title, detail, savings_class, amount_minor, currency, exponent,
                           confidence, evidence_json, assumptions_json, catalogue_snapshot, status, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?)
                        """,
                id, profileId, kind, title, detail, savingsClass, amountMinor, currency, exponent,
                confidence, evidence, assumptions, "2026.11-seed", Instant.now().toString());
    }

    public List<RecommendationRow> recommendations(String profileId) {
        String today = LocalDate.now().toString();
        return dsl.fetch("""
                        select id, kind, title, detail, savings_class, amount_minor, currency, exponent,
                               confidence, evidence_json, assumptions_json
                        from recommendation
                        where profile_id = ?
                          and (status = 'open' or (status = 'snoozed' and snoozed_until is not null and snoozed_until <= ?))
                        order by confidence desc, title
                        """, profileId, today)
                .stream()
                .map(row -> new RecommendationRow(
                        text(row, "id"), text(row, "kind"), text(row, "title"), text(row, "detail"),
                        text(row, "savings_class"),
                        row.get("amount_minor") == null ? null : numberLong(row, "amount_minor"),
                        text(row, "currency"),
                        row.get("exponent") == null ? null : number(row, "exponent"),
                        decimal(row, "confidence"), text(row, "evidence_json"), text(row, "assumptions_json")))
                .toList();
    }

    public void insertSavingsEvent(String id, String profileId, String kind, long amountMinor, String currency,
                                   int exponent, String periodStart, String periodEnd, String evidence) {
        dsl.execute("""
                        insert into savings_event
                          (id, profile_id, kind, amount_minor, currency, exponent, period_start, period_end,
                           evidence_json, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, profileId, kind, amountMinor, currency, exponent, periodStart, periodEnd, evidence,
                Instant.now().toString());
    }

    public List<SavingsRow> savings(String profileId) {
        return dsl.fetch("select kind, amount_minor, currency, exponent from savings_event where profile_id = ?", profileId)
                .stream()
                .map(row -> new SavingsRow(text(row, "kind"), numberLong(row, "amount_minor"),
                        text(row, "currency"), number(row, "exponent")))
                .toList();
    }

    public static int occurrences(String cadence) {
        return Cadence.fromWire(cadence).occurrencesPerYear();
    }

    static String text(Record row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }

    static int number(Record row, String column) {
        Object value = row.get(column);
        return value == null ? 0 : ((Number) value).intValue();
    }

    static long numberLong(Record row, String column) {
        Object value = row.get(column);
        return value == null ? 0L : ((Number) value).longValue();
    }

    static double decimal(Record row, String column) {
        Object value = row.get(column);
        return value == null ? 0d : ((Number) value).doubleValue();
    }

    static LocalDate date(Record row, String column) {
        Object value = row.get(column);
        return value == null ? null : LocalDate.parse(value.toString());
    }
}
