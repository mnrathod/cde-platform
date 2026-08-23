package com.cde.platform.support;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts one PostgreSQL container for the whole test run and points the test
 * profile at it.
 *
 * <p>The suite used to run on H2 in PostgreSQL compatibility mode, which was
 * fast and needed no Docker. That is no longer viable, and not merely as a
 * matter of policy: Row-Level Security is the platform's highest-severity
 * control and H2 has no such feature at all. Tests against it would have
 * happily reported isolation working while the mechanism under test did not
 * exist — the most expensive kind of green.
 *
 * <p>A {@code LauncherSessionListener} rather than a per-class container or an
 * abstract base test: it starts once per JVM, before any test, so every
 * {@code @SpringBootTest} shares it without a single class having to remember
 * to opt in. A test added later cannot silently fall back to something weaker,
 * because there is nothing else to fall back to.
 */
public class PostgresTestSessionListener implements LauncherSessionListener {

    private static final String IMAGE = "postgres:17-alpine";

    private static PostgreSQLContainer<?> container;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (container != null) {
            return;
        }
        container = new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName("cdetest")
            .withUsername("cde")
            .withPassword("cde")
            // Reused across every context in the run; Ryuk stops it at JVM exit.
            .withReuse(false);
        container.start();

        System.setProperty("test.datasource.url", container.getJdbcUrl());
        System.setProperty("test.datasource.username", container.getUsername());
        System.setProperty("test.datasource.password", container.getPassword());
    }
}
