package com.cde.platform.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migrations, run against a real PostgreSQL, with Hibernate validating the
 * result.
 *
 * <p>The rest of the suite runs on H2 with Hibernate generating the schema,
 * which is fast and proves nothing about the migrations — the two could drift
 * apart indefinitely and every test would still pass. This closes that gap.
 *
 * <p>With {@code ddl-auto: validate}, simply reaching {@code @Test} is most of
 * the assertion: the context only starts if Flyway produced a schema Hibernate
 * agrees with, column type by column type. A migration that forgot a column an
 * entity gained fails here rather than on a deployment.
 *
 * <p>Needs a working Docker daemon. Skipped rather than failed without one, so
 * a checkout still builds on a machine that has none — run with
 * {@code -Dcde.docker=true} in CI to make the skip visible.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("postgres-it")
@EnabledIfSystemProperty(
        named = "cde.testcontainers", matches = "true",
        disabledReason = "Set -Dcde.testcontainers=true (and have Docker) to run the migrations for real")
class FlywayMigrationIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                    .withDatabaseName("cdedb")
                    .withUsername("cde")
                    .withPassword("cde");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // Started here rather than in a static initialiser or with @Container.
        // A static initialiser runs when JUnit loads the class to read its
        // annotations — that is, before the @EnabledIfSystemProperty condition
        // is evaluated — so a machine without Docker would fail trying to
        // start a container for a test it was about to skip. This method runs
        // only for a test that is going ahead, and once per context rather
        // than once per test method.
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        // The point of the test: Hibernate checks the migrated schema rather
        // than building one of its own.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Every migration applied, and none of them failed")
    void migrationsApplyCleanly() {
        List<String> failed = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = false", String.class);
        assertTrue(failed.isEmpty(), () -> "failed migrations: " + failed);

        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertTrue(applied != null && applied >= 1, "no migrations were applied");
    }

    @Test
    @DisplayName("Every entity has a table")
    void allEntityTablesExist() {
        for (String table : List.of("users", "projects", "documents", "document_versions",
                                    "annotations", "annotation_replies", "document_signatures")) {
            Integer found = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertEquals(1, found, table + " is missing");
        }
    }

    @Test
    @DisplayName("Foreign key columns are indexed")
    void foreignKeysAreIndexed() {
        // PostgreSQL indexes primary keys and unique constraints on its own,
        // and foreign keys not at all. Without these, listing a document's
        // annotations scans the whole table — which looks fine on demo data
        // and does not on real data.
        List<String> unindexed = jdbc.queryForList("""
                SELECT c.conrelid::regclass || '.' || a.attname
                FROM pg_constraint c
                JOIN pg_attribute a
                  ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f'
                  AND array_length(c.conkey, 1) = 1
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_index i
                      WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1]
                  )
                """, String.class);
        assertTrue(unindexed.isEmpty(), () -> "foreign keys without an index: " + unindexed);
    }

    @Test
    @DisplayName("A document cannot hold two rows for the same version number")
    void versionNumbersAreUniquePerDocument() {
        Integer constraints = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint "
                        + "WHERE conname = 'uk_document_version' AND contype = 'u'",
                Integer.class);
        assertEquals(1, constraints, "uk_document_version is missing — 'restore version 3' would be ambiguous");
    }
}
