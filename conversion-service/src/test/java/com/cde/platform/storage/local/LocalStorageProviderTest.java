package com.cde.platform.storage.local;

import com.cde.platform.storage.StorageProvider;
import com.cde.platform.storage.StorageProviderContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;

/**
 * The local provider against the shared contract.
 *
 * <p>Nothing is asserted here that is not asserted for every other provider.
 * That is the point: a backend either satisfies the same contract as the
 * others or feature code cannot be indifferent to which one is running.
 */
@DisplayName("local filesystem storage")
class LocalStorageProviderTest extends StorageProviderContract {

    private static final byte[] TEST_SIGNING_KEY =
        "a-test-signing-key-of-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private StorageProvider provider;

    @BeforeEach
    void createProvider() {
        provider = new LocalFilesystemStorageProvider(
            root,
            new SignedObjectToken(TEST_SIGNING_KEY),
            URI.create("https://files.example.invalid"));
    }

    @Override
    protected StorageProvider provider() {
        return provider;
    }

    @Override
    protected long tenantId() {
        // Any positive value; the contract only requires that two different
        // tenants stay separate, and it derives the second from this one.
        return 4242;
    }
}
