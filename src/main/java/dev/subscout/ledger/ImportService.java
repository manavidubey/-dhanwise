package dev.subscout.ledger;

import dev.subscout.merchant.MerchantNormalizer;
import dev.subscout.merchant.MerchantRepository;
import dev.subscout.platform.Ids;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ImportService {

    public record Result(
            String importId,
            int totalRows,
            int imported,
            int duplicates,
            List<String> errors,
            boolean duplicateFile) {
    }

    private final LedgerRepository ledger;
    private final MerchantNormalizer normalizer;
    private final MerchantRepository merchants;
    private final CsvStatementParser parser = new CsvStatementParser();

    public ImportService(LedgerRepository ledger, MerchantNormalizer normalizer, MerchantRepository merchants) {
        this.ledger = ledger;
        this.normalizer = normalizer;
        this.merchants = merchants;
    }

    public Result importCsv(String profileId, String accountId, String fileName, byte[] bytes) {
        LedgerRepository.AccountRow account = ledger.account(profileId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountId));

        String sha256 = Ids.sha256(bytes);
        var alreadyImported = ledger.importByHash(profileId, accountId, sha256);
        if (alreadyImported.isPresent()) {
            return new Result(alreadyImported.get(), 0, 0, 0, List.of(), true);
        }

        CsvStatementParser.SignConvention convention = "card".equals(account.kind())
                ? CsvStatementParser.SignConvention.POSITIVE_IS_DEBIT
                : CsvStatementParser.SignConvention.NEGATIVE_IS_DEBIT;

        String content = new String(bytes, StandardCharsets.UTF_8);
        CsvStatementParser.ParseResult parsed = parser.parse(content, account.currency(), convention);

        String importId = Ids.newId();
        ledger.insertImport(importId, profileId, accountId, safeFileName(fileName), sha256, "csv-v1",
                parsed.hasErrors() ? "partial" : "parsed");

        int imported = 0;
        int duplicates = 0;
        for (CsvStatementParser.ParsedRow row : parsed.rows()) {
            MerchantNormalizer.Normalized normalized = normalizer.normalize(row.description());
            String merchantId = merchants.resolve(normalized.normalized(), normalized.displayHint());

            String dedupe = Ids.sha256(String.join("|",
                    accountId,
                    row.postedOn().toString(),
                    Long.toString(row.amount().minor()),
                    row.amount().currency(),
                    normalized.normalized(),
                    row.externalRef() == null ? "" : row.externalRef()));

            boolean inserted = ledger.insertTxn(new LedgerRepository.TxnInsert(
                    Ids.newId(),
                    profileId,
                    accountId,
                    importId,
                    row.postedOn(),
                    row.description(),
                    normalized.normalized(),
                    merchantId,
                    row.direction() == CsvStatementParser.Direction.DEBIT ? "debit" : "credit",
                    row.amount().minor(),
                    row.amount().currency(),
                    row.amount().exponent(),
                    kindOf(row.description(), row.direction()),
                    dedupe));
            if (inserted) {
                imported++;
            } else {
                duplicates++;
            }
        }

        ledger.updateImportCounts(importId, parsed.rows().size(), imported, duplicates, parsed.errors().size(),
                parsed.hasErrors() ? "partial" : "parsed");

        return new Result(importId, parsed.rows().size(), imported, duplicates, parsed.errors(), false);
    }

    /**
     * EMI conversions are the single largest source of false subscriptions in Indian card
     * data: one purchase becomes N identical monthly debits. They are recorded as a distinct
     * kind so detection never treats them as a subscription.
     */
    private static String kindOf(String description, CsvStatementParser.Direction direction) {
        String upper = description.toUpperCase(Locale.ROOT);
        if (upper.matches(".*\\bEMI\\b.*") || upper.contains("INSTALMENT") || upper.contains("INSTALLMENT")) {
            return "emi_installment";
        }
        return direction == CsvStatementParser.Direction.CREDIT ? "credit" : "purchase";
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "statement.csv";
        }
        String base = fileName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        return slash >= 0 ? base.substring(slash + 1) : base;
    }
}
