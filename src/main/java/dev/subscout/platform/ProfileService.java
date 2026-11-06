package dev.subscout.platform;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {

    public static final String DEFAULT_PROFILE_ID = "default";
    public static final String DEFAULT_ACCOUNT_ID = "default-card";

    private final DSLContext dsl;

    public ProfileService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public String ensureDefaultProfile() {
        int profiles = dsl.fetch("select count(*) as c from profile")
                .stream().findFirst().map(ProfileService::count).orElse(0);
        if (profiles == 0) {
            String now = Instant.now().toString();
            dsl.execute("insert into profile(id, display_name, created_at) values (?, ?, ?)",
                    DEFAULT_PROFILE_ID, "Household", now);
            dsl.execute("""
                            insert into account
                              (id, profile_id, kind, name, institution, currency, last4, fx_markup_bps, created_at)
                            values (?, ?, 'card', 'Primary card', null, 'INR', null, 300, ?)
                            """, DEFAULT_ACCOUNT_ID, DEFAULT_PROFILE_ID, now);
        }
        return DEFAULT_PROFILE_ID;
    }

    public String displayName(String profileId) {
        return dsl.fetch("select display_name from profile where id = ?", profileId)
                .stream().findFirst().map(row -> text(row, "display_name")).orElse("Household");
    }

    public void updateProfile(String profileId, String displayName, String currency) {
        if (displayName != null && !displayName.isBlank()) {
            dsl.execute("update profile set display_name = ? where id = ?", displayName.trim(), profileId);
        }
        if (currency != null && !currency.isBlank()) {
            dsl.execute("update account set currency = ? where profile_id = ?", currency.trim().toUpperCase(), profileId);
        }
    }

    public String defaultAccountId(String profileId) {
        return dsl.fetch("select id from account where profile_id = ? order by created_at limit 1", profileId)
                .stream().findFirst().map(row -> text(row, "id"))
                .orElseThrow(() -> new IllegalStateException("No account for profile " + profileId));
    }

    public String currency(String profileId) {
        return dsl.fetch("select currency from account where profile_id = ? order by created_at limit 1", profileId)
                .stream().findFirst().map(row -> text(row, "currency")).orElse("INR");
    }

    public byte[] sampleStatement() {
        try (InputStream input = getClass().getResourceAsStream("/samples/statement.csv")) {
            if (input == null) {
                throw new IllegalStateException("Bundled sample statement is missing");
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read bundled sample statement", e);
        }
    }

    private static int count(Record row) {
        Object value = row.get("c");
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static String text(Record row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }
}
