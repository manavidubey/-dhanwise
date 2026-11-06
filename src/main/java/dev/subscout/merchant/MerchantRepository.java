package dev.subscout.merchant;

import dev.subscout.platform.Ids;
import java.time.Instant;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class MerchantRepository {

    private final DSLContext dsl;

    public MerchantRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<String> merchantForAlias(String alias) {
        return dsl.fetch("select merchant_id from merchant_alias where alias = ?", alias)
                .stream().findFirst().map(row -> row.get("merchant_id").toString());
    }

    /**
     * Resolves a normalised descriptor to a merchant, creating a provisional entry when
     * unseen. A user override is stored with source='user' and is never overwritten.
     */
    public String resolve(String normalized, String displayHint) {
        Optional<String> existing = merchantForAlias(normalized);
        if (existing.isPresent()) {
            return existing.get();
        }
        String merchantId = Ids.newId();
        dsl.execute("insert into merchant(id, canonical_name, category, created_at) values (?, ?, ?, ?)",
                merchantId, displayHint, null, Instant.now().toString());
        dsl.execute("insert or ignore into merchant_alias(alias, merchant_id, source, confidence, created_at) "
                        + "values (?, ?, 'auto', 0.6, ?)",
                normalized, merchantId, Instant.now().toString());
        return merchantId;
    }

    public void overrideAlias(String normalized, String merchantId) {
        dsl.execute("insert into merchant_alias(alias, merchant_id, source, confidence, created_at) "
                        + "values (?, ?, 'user', 1.0, ?) "
                        + "on conflict(alias) do update set merchant_id = excluded.merchant_id, source = 'user', confidence = 1.0",
                normalized, merchantId, Instant.now().toString());
    }

    public String canonicalName(String merchantId) {
        return dsl.fetch("select canonical_name from merchant where id = ?", merchantId)
                .stream().findFirst().map(row -> row.get("canonical_name").toString()).orElse("Unknown merchant");
    }
}
