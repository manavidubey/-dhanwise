package dev.subscout.ledger;

import dev.subscout.platform.Money;
import dev.subscout.recurring.RecurrenceDetector;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * Ledger reads and writes.
 *
 * <p>Plain-SQL through jOOQ rather than generated table classes: the build stays free of
 * codegen steps, money columns keep their explicit types, and duplicate handling is the
 * SQLite {@code insert or ignore} that the dedupe index relies on.
 */
@Repository
public class LedgerRepository {

    private final DSLContext dsl;

    public LedgerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record AccountRow(String id, String profileId, String kind, String name, String currency, int fxMarkupBps) {
    }

    public record TxnInsert(
            String id,
            String profileId,
            String accountId,
            String importId,
            LocalDate postedOn,
            String descriptorRaw,
            String descriptorNormalized,
            String merchantId,
            String direction,
            long amountMinor,
            String currency,
            int exponent,
            String kind,
            String dedupeHash) {
    }

    public record ExportRow(LocalDate postedOn, String description, String direction, long amountMinor,
                            String currency, int exponent) {
    }

    public Optional<AccountRow> account(String profileId, String accountId) {
        return dsl.fetch("""
                        select id, profile_id, kind, name, currency, fx_markup_bps
                        from account where profile_id = ? and id = ?
                        """, profileId, accountId)
                .stream()
                .findFirst()
                .map(row -> new AccountRow(
                        text(row, "id"), text(row, "profile_id"), text(row, "kind"),
                        text(row, "name"), text(row, "currency"), number(row, "fx_markup_bps")));
    }

    public List<AccountRow> accounts(String profileId) {
        return dsl.fetch("select id, profile_id, kind, name, currency, fx_markup_bps from account "
                        + "where profile_id = ? order by created_at", profileId)
                .stream()
                .map(row -> new AccountRow(
                        text(row, "id"), text(row, "profile_id"), text(row, "kind"),
                        text(row, "name"), text(row, "currency"), number(row, "fx_markup_bps")))
                .toList();
    }

    public Optional<String> importByHash(String profileId, String accountId, String sha256) {
        return dsl.fetch("select id from statement_import "
                        + "where profile_id = ? and account_id = ? and file_sha256 = ?",
                        profileId, accountId, sha256)
                .stream().findFirst().map(row -> text(row, "id"));
    }

    public void insertImport(String id, String profileId, String accountId, String fileName,
                             String sha256, String parser, String status) {
        dsl.execute("""
                        insert into statement_import
                          (id, profile_id, account_id, file_name, file_sha256, parser, status, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """, id, profileId, accountId, fileName, sha256, parser, status, Instant.now().toString());
    }

    public void updateImportCounts(String id, int totalRows, int imported, int duplicates, int errors, String status) {
        dsl.execute("""
                        update statement_import
                        set total_rows = ?, imported_rows = ?, duplicate_rows = ?, error_count = ?, status = ?
                        where id = ?
                        """, totalRows, imported, duplicates, errors, status, id);
    }

    /** Returns true when the row was new; duplicate rows are ignored by the dedupe index. */
    public boolean insertTxn(TxnInsert txn) {
        int rows = dsl.execute("""
                        insert or ignore into txn
                          (id, profile_id, account_id, import_id, posted_on, descriptor_raw, descriptor_normalized,
                           merchant_id, direction, amount_minor, currency, exponent, kind, dedupe_hash, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                txn.id(), txn.profileId(), txn.accountId(), txn.importId(), txn.postedOn().toString(),
                txn.descriptorRaw(), txn.descriptorNormalized(), txn.merchantId(), txn.direction(),
                txn.amountMinor(), txn.currency(), txn.exponent(), txn.kind(), txn.dedupeHash(),
                Instant.now().toString());
        return rows > 0;
    }

    public int countTransactions(String profileId) {
        return dsl.fetch("select count(*) as c from txn where profile_id = ?", profileId)
                .stream().findFirst().map(row -> number(row, "c")).orElse(0);
    }

    public List<RecurrenceDetector.Observation> observations(String profileId) {
        return dsl.fetch("""
                        select t.id, t.merchant_id, coalesce(m.canonical_name, t.descriptor_normalized) as merchant_name,
                               t.posted_on, t.amount_minor, t.currency, t.exponent, t.kind
                        from txn t
                        left join merchant m on m.id = t.merchant_id
                        where t.profile_id = ? and t.direction = 'debit'
                        order by t.posted_on
                        """, profileId)
                .stream()
                .map(row -> new RecurrenceDetector.Observation(
                        text(row, "id"),
                        text(row, "merchant_id"),
                        text(row, "merchant_name"),
                        LocalDate.parse(text(row, "posted_on")),
                        Money.ofMinor(numberLong(row, "amount_minor"), text(row, "currency"), number(row, "exponent")),
                        text(row, "kind")))
                .toList();
    }

    public List<ExportRow> exportRows(String profileId) {
        return dsl.fetch("""
                        select posted_on, descriptor_raw, direction, amount_minor, currency, exponent
                        from txn where profile_id = ? order by posted_on
                        """, profileId)
                .stream()
                .map(row -> new ExportRow(
                        LocalDate.parse(text(row, "posted_on")),
                        text(row, "descriptor_raw"),
                        text(row, "direction"),
                        numberLong(row, "amount_minor"),
                        text(row, "currency"),
                        number(row, "exponent")))
                .toList();
    }

    public void deleteAll(String profileId) {
        dsl.execute("delete from savings_event where profile_id = ?", profileId);
        dsl.execute("delete from recommendation where profile_id = ?", profileId);
        dsl.execute("delete from subscription where profile_id = ?", profileId);
        dsl.execute("delete from recurring_series where profile_id = ?", profileId);
        dsl.execute("delete from txn where profile_id = ?", profileId);
        dsl.execute("delete from statement_import where profile_id = ?", profileId);
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
}
