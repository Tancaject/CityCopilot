package com.citycopilot.core.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Only creates and drops uniquely named databases on an explicitly supplied local test server. */
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_MIGRATION_TEST_URL", matches = "jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")
class DatabaseMigrationsIT {
    private static final String SERVER = System.getenv("CITYCOPILOT_MIGRATION_TEST_URL");
    private static final String USER = System.getenv().getOrDefault("CITYCOPILOT_MIGRATION_TEST_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("CITYCOPILOT_MIGRATION_TEST_PASSWORD", "");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();

    @ParameterizedTest
    @ValueSource(strings = {"core", "commerce", "agent"})
    void freshAndLegacyDatabasesConvergeWithoutLosingBusinessRows(String service) throws Exception {
        String expectedShape = null;
        for (String mode : new String[]{"fresh", "legacy-v1", "legacy-current"}) {
            String database = "cc_migration_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection admin = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD)) {
                execute(admin, "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                try (Connection connection = DriverManager.getConnection(SERVER + database + OPTIONS, USER, PASSWORD)) {
                    Flyway migration = migration(database, ROOT.resolve("deploy/mysql/" + service));
                    if (mode.equals("legacy-v1")) {
                        Flyway.configure().configuration(migration.getConfiguration()).target("1").load().migrate();
                        if (service.equals("commerce")) {
                            execute(connection, "ALTER TABLE commerce_order MODIFY idempotency_key VARCHAR(128) COLLATE utf8mb4_general_ci NOT NULL");
                            execute(connection, "ALTER TABLE commerce_order_recovery MODIFY idempotency_key VARCHAR(128) COLLATE utf8mb4_general_ci NULL");
                        }
                    } else {
                        migration.migrate();
                    }
                    insertBusinessRow(connection, service);
                    if (!mode.equals("fresh")) {
                        execute(connection, "DROP TABLE flyway_schema_history");
                        assertThrows(FlywayException.class, migration::migrate, "Existing databases must require explicit adoption");
                        migration.baseline();
                    }
                    migration.migrate();
                    assertEquals(0, migration.migrate().migrationsExecuted, "Repeated startup must not rerun SQL");
                    migration.validate();
                    if (service.equals("core")) {
                        assertEquals("4", scalar(connection, "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shop' AND CONSTRAINT_TYPE='CHECK' AND ENFORCED='YES'"));
                        assertEquals("0", scalar(connection, "SELECT fencing_token FROM shop_projection_lease WHERE lease_name='shop-projection-cutover'"));
                    }
                    if (mode.equals("fresh") && !service.equals("agent")) {
                        var seed = new ResourceDatabasePopulator(new FileSystemResource(ROOT.resolve("deploy/mysql/seed/" + service + ".sql")));
                        seed.setSqlScriptEncoding("UTF-8");
                        seed.populate(connection);
                    }
                    assertEquals("preserve-me", scalar(connection, switch (service) {
                        case "core" -> "SELECT nick_name FROM user_account WHERE id=900001";
                        case "commerce" -> "SELECT idempotency_key FROM commerce_order WHERE id=900001";
                        default -> "SELECT incident_key FROM ops_case WHERE id='migration-probe'";
                    }));
                    if (service.equals("commerce") && !mode.equals("fresh")) {
                        assertEquals("ORDER_CREATED", scalar(connection, "SELECT status FROM commerce_admission_ledger WHERE order_id=900001"));
                        assertTrue(Long.parseLong(scalar(connection, "SELECT next_value FROM commerce_id_segment WHERE namespace='order'")) >= 900001);
                    }
                    String shape = shape(connection);
                    if (expectedShape == null) expectedShape = shape;
                    else assertEquals(expectedShape, shape, "Fresh and adopted schemas must converge: " + mode);
                } finally {
                    execute(admin, "DROP DATABASE " + database);
                }
            }
        }
    }

    @Test
    void rejectsUnknownBaselineAndEditedAppliedMigration(@TempDir Path copies) throws Exception {
        String database = "cc_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD)) {
            execute(admin, "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try (Connection connection = DriverManager.getConnection(SERVER + database + OPTIONS, USER, PASSWORD)) {
                Flyway migration = migration(database, ROOT.resolve("deploy/mysql/core"));
                execute(connection, "CREATE TABLE unrelated_data(id INT PRIMARY KEY)");
                execute(connection, "INSERT INTO unrelated_data VALUES (7)");
                assertThrows(FlywayException.class, migration::baseline);
                assertEquals("7", scalar(connection, "SELECT id FROM unrelated_data"));
                execute(connection, "DROP TABLE unrelated_data");
                migration.migrate();
                try (var files = Files.list(ROOT.resolve("deploy/mysql/core"))) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                        Files.copy(file, copies.resolve(file.getFileName()));
                    }
                }
                Files.writeString(copies.resolve("V1__core_schema.sql"), "\nSELECT 1;\n", java.nio.file.StandardOpenOption.APPEND);
                assertThrows(FlywayException.class, () -> migration(database, copies).validate());
                execute(connection, "DROP TABLE flyway_schema_history");
                execute(connection, "ALTER TABLE processed_event DROP PRIMARY KEY");
                assertThrows(FlywayException.class, migration::baseline, "Inbox deduplication keys are required before adoption");
            } finally {
                execute(admin, "DROP DATABASE " + database);
            }
        }
    }

    private Flyway migration(String database, Path location) {
        return Flyway.configure().dataSource(SERVER + database + OPTIONS, USER, PASSWORD)
                .locations("filesystem:" + location).baselineVersion("1")
                .baselineOnMigrate(false).cleanDisabled(true).load();
    }

    private void insertBusinessRow(Connection connection, String service) throws Exception {
        switch (service) {
            case "core" -> execute(connection, "INSERT INTO user_account(id,phone,nick_name) VALUES (900001,'19900000001','preserve-me')");
            case "commerce" -> {
                execute(connection, "INSERT INTO commerce_voucher(id,shop_id,title,pay_value,actual_value) VALUES (900001,1,'migration',100,200)");
                execute(connection, "INSERT INTO commerce_order(id,user_id,voucher_id,shop_id,shop_name_snapshot,voucher_title_snapshot,pay_value_snapshot,actual_value_snapshot,status,idempotency_key) VALUES (900001,1,900001,1,'shop','voucher',100,200,1,'preserve-me')");
            }
            default -> execute(connection, "INSERT INTO ops_case(id,incident_key,event_id,case_type,status) VALUES ('migration-probe','preserve-me','migration-event','TEST','OPEN')");
        }
    }

    private String shape(Connection connection) throws Exception {
        StringBuilder result = new StringBuilder();
        String[] queries = {
                "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,'<null>'),EXTRA,COALESCE(COLLATION_NAME,'') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME,ORDINAL_POSITION",
                "SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,COLUMN_NAME,SEQ_IN_INDEX FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX",
                "SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,ENFORCED FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME,CONSTRAINT_NAME"
        };
        for (String query : queries) {
            try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
                while (rows.next()) {
                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) result.append(rows.getString(i)).append('|');
                    result.append('\n');
                }
            }
        }
        return result.toString();
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
