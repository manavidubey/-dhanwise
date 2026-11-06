package dev.subscout.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Applies {@code db/migration/V<n>__*.sql} once each, recording a checksum.
 *
 * <p>Flyway 12 moved SQLite behind a native-connector artifact, so this project keeps
 * Flyway-compatible migration files and a 60-line runner instead of taking that dependency.
 * Swapping in Flyway later only replaces this class.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);
    private static final Pattern VERSION = Pattern.compile("V(\\d+)__");

    private final DataSource dataSource;
    private final ResourcePatternResolver resources = new PathMatchingResourcePatternResolver();

    public SchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Resource> scripts = Arrays.stream(resources.getResources("classpath:db/migration/V*__*.sql"))
                .sorted(Comparator.comparingInt(r -> versionOf(r.getFilename())))
                .toList();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            createVersionTable(connection);
            for (Resource script : scripts) {
                apply(connection, script);
            }
            connection.commit();
        }
        verifyPragmas();
    }

    private void apply(Connection connection, Resource script) throws SQLException, IOException {
        String name = script.getFilename();
        int version = versionOf(name);
        byte[] bytes = script.getInputStream().readAllBytes();
        String checksum = Ids.sha256(bytes);

        String applied = appliedChecksum(connection, version);
        if (applied != null) {
            if (!applied.equals(checksum)) {
                throw new IllegalStateException(
                        "Migration " + name + " was modified after being applied; add a new migration instead");
            }
            return;
        }

        for (String statement : SqlScript.split(new String(bytes, StandardCharsets.UTF_8))) {
            try (Statement jdbc = connection.createStatement()) {
                jdbc.execute(statement);
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into schema_version(version, checksum, applied_at) values (?, ?, ?)")) {
            insert.setString(1, Integer.toString(version));
            insert.setString(2, checksum);
            insert.setString(3, Instant.now().toString());
            insert.executeUpdate();
        }
        log.info("Applied migration {} (v{})", name, version);
    }

    private void createVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists schema_version (
                      version text primary key,
                      checksum text not null,
                      applied_at text not null
                    )""");
        }
    }

    private String appliedChecksum(Connection connection, int version) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select checksum from schema_version where version = ?")) {
            query.setString(1, Integer.toString(version));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /** Guards the two pragmas whose absence corrupts data or silently disables integrity. */
    private void verifyPragmas() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("pragma foreign_keys")) {
                if (!rows.next() || rows.getInt(1) != 1) {
                    throw new IllegalStateException("SQLite foreign_keys pragma is off; refusing to run");
                }
            }
            try (ResultSet rows = statement.executeQuery("pragma journal_mode")) {
                if (rows.next() && !"wal".equalsIgnoreCase(rows.getString(1))) {
                    log.warn("SQLite journal_mode is {} rather than wal", rows.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to verify SQLite pragmas", e);
        }
    }

    private static int versionOf(String name) {
        Matcher matcher = VERSION.matcher(name == null ? "" : name);
        if (!matcher.find()) {
            throw new IllegalStateException("Unrecognised migration file name: " + name);
        }
        return Integer.parseInt(matcher.group(1));
    }
}
