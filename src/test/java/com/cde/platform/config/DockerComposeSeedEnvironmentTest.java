package com.cde.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a compose stack can be seeded at all, and whether it could ever seed
 * itself with a credential from this repository.
 *
 * <p>{@link SeedProperties} makes seeding configurable and refuses to invent a
 * password. None of that reaches a {@code docker compose up} unless the
 * compose file passes the variables through, and it did not: seeding was
 * settable in {@code application.yml}, documented in {@code .env.example}, and
 * silently inert for anyone running the stack the way the quick start
 * describes. A missing passthrough fails quietly — the application starts,
 * logs that seeding is off, and the operator concludes the feature is broken —
 * so it is asserted here rather than left to be rediscovered.
 *
 * <p>The second assertion is the more important one. The convenient fix for
 * "no default test account" is a password written into the compose file, which
 * is the default credential the seeder was changed to stop shipping, restored
 * by a different route.
 */
class DockerComposeSeedEnvironmentTest {

    private static final Path COMPOSE_FILE = Path.of("docker-compose.yml");

    private static final String APPLICATION_SERVICE = "cde-app";

    @Test
    @DisplayName("the compose stack passes the seeding settings through to the application")
    void passesSeedingSettingsThrough() throws IOException {
        Map<String, String> environment = applicationEnvironment();

        assertThat(environment)
            .containsKeys("CDE_SEED_ENABLED", "CDE_SEED_ADMIN_PASSWORD");
    }

    @Test
    @DisplayName("seeding is off, and has no password, unless the environment supplies both")
    void suppliesNoCredentialOfItsOwn() throws IOException {
        Map<String, String> environment = applicationEnvironment();

        // `${VAR:-}` and `${VAR:-false}` — a substitution with an empty or
        // non-credential fallback. Anything else here is a literal, and a
        // literal password in a committed file is a published one.
        assertThat(environment.get("CDE_SEED_ENABLED"))
            .isEqualTo("${CDE_SEED_ENABLED:-false}");
        assertThat(environment.get("CDE_SEED_ADMIN_PASSWORD"))
            .isEqualTo("${CDE_SEED_ADMIN_PASSWORD:-}");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> applicationEnvironment() throws IOException {
        assertThat(COMPOSE_FILE)
            .describedAs("compose file, resolved relative to the Gradle test working directory")
            .exists();

        Map<String, Object> compose;
        try (InputStream source = Files.newInputStream(COMPOSE_FILE)) {
            compose = new Yaml().load(source);
        }

        var services = (Map<String, Object>) compose.get("services");
        var application = (Map<String, Object>) services.get(APPLICATION_SERVICE);
        assertThat(application)
            .describedAs("service '%s' in %s", APPLICATION_SERVICE, COMPOSE_FILE)
            .isNotNull();

        // Values are read as strings so that `false` stays "${CDE_SEED_ENABLED:-false}"
        // rather than being coerced, and so a bare `true` would be visible as
        // the literal it is.
        var environment = (Map<String, Object>) application.get("environment");
        return environment.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, entry -> String.valueOf(entry.getValue())));
    }
}
