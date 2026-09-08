package com.cde.platform.storage;

import com.cde.platform.storage.local.LocalFilesystemStorageProvider;
import com.cde.platform.storage.local.SignedObjectToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Chooses the storage backend, once, from configuration.
 *
 * <p>This is the only place in the application that knows which provider is
 * running. Everything downstream depends on {@link StorageProvider}.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);

    @Bean
    public StorageProvider storageProvider(StorageProperties properties) {
        StorageProvider provider = switch (properties.getProvider()) {
            case LOCAL -> localProvider(properties);
            // Unreachable: StorageProperties refuses to bind an unimplemented
            // provider, so this never runs. It is here so that adding an enum
            // constant without an adapter is a compile-time exhaustiveness
            // error rather than a runtime surprise.
            case S3, AZURE, GCS -> throw new StorageException(
                properties.getProvider() + " is not implemented in this build. "
                + "See docs/adr/0011-storage-abstraction.md.");
        };

        // Fail at startup, not at the first upload. Configuration that parses
        // is not configuration that works: a read-only mount, a full volume and
        // a path owned by another user all bind cleanly and all fail later,
        // when a user is waiting and the failure looks like a bug.
        provider.verifyReachable();

        log.info("Storage provider '{}' ready, environment '{}'",
            provider.providerName(), properties.getEnvironment());
        return provider;
    }

    private StorageProvider localProvider(StorageProperties properties) {
        return new LocalFilesystemStorageProvider(
            Path.of(properties.getLocalRoot()),
            new SignedObjectToken(decodeSigningKey(properties.getSigningKey())),
            URI.create(properties.getDownloadBaseUri()));
    }

    /**
     * Reads the signing key, preferring base64 and falling back to raw bytes.
     *
     * <p>{@code openssl rand -base64 32} is what the error message tells an
     * operator to run, so base64 is tried first; but a key pasted as raw text
     * should work rather than being silently misread as base64 into a shorter,
     * weaker key.
     */
    private byte[] decodeSigningKey(String configured) {
        try {
            return Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException notBase64) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
    }
}
