package com.cde.platform.support;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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

    /**
     * Pinned by digest, matching {@code docker-compose.yml} and
     * {@code k8s/postgres.yaml} so the suite tests the database the stack
     * actually runs.
     *
     * <p>{@code asCompatibleSubstituteFor} is required, not decorative.
     * Testcontainers recognises {@code PostgreSQLContainer}'s image by name,
     * and a name carrying both a tag and a digest does not match {@code
     * postgres} — so pinning the digest alone makes every container-backed
     * test fail at launcher startup, before any test runs, with a message
     * about image compatibility rather than about the pin. That is what
     * happened when the digests went in: the suite could not be run at the
     * time, so nothing caught it.
     */
    private static final DockerImageName IMAGE = DockerImageName
        .parse("postgres:17-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
        .asCompatibleSubstituteFor("postgres");

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
