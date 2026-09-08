package com.cde.platform.storage;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Which storage backend is active and how to reach it.
 *
 * <p>The provider is chosen here and nowhere else. Feature code never learns
 * which one is running — that is what lets one artifact deploy to Azure, AWS,
 * GCP and an air-gapped rack unchanged.
 */
@ConfigurationProperties(prefix = "cde.storage")
@Validated
public class StorageProperties {

    /** Providers this build can actually construct. */
    public enum Provider {
        LOCAL,
        S3,
        AZURE,
        GCS
    }

    /**
     * Which backend to use.
     *
     * <p>Only {@link Provider#LOCAL} is implemented. The other three are
     * declared because the interface is designed for them and because naming
     * them here makes the gap explicit at configuration time: a deployment
     * that sets {@code s3} fails at startup with a message saying so, rather
     * than silently writing customer data to a local disk that nobody backs
     * up and that disappears with the container.
     */
    private Provider provider = Provider.LOCAL;

    /**
     * Environment name, forming the first segment of every key.
     *
     * <p>It keeps a staging deployment pointed at a shared bucket from writing
     * over production objects, which is a mistake that is cheap to prevent
     * here and expensive to discover later.
     */
    @NotBlank
    private String environment = "local";

    /** Filesystem root for the local provider. Ignored by the others. */
    @NotBlank
    private String localRoot = "./storage";

    /**
     * Key for signing the local provider's presigned URLs.
     *
     * <p>No default, and required whenever the local provider is active: a
     * built-in signing key is a published signing key, and anyone holding it
     * can mint a URL for any object in any tenant. Generate one with
     * {@code openssl rand -base64 32} and supply it as
     * {@code CDE_STORAGE_SIGNING_KEY}.
     */
    private String signingKey;

    /**
     * Origin the local provider's presigned URLs point at.
     *
     * <p>Should be a separate host from the application in production, so a
     * stored HTML or SVG payload cannot execute against the application's
     * origin.
     */
    @NotBlank
    private String downloadBaseUri = "http://localhost:8080";

    @AssertTrue(message = """
        cde.storage.signing-key is required when the local storage provider is \
        active, and has no default. A built-in key would be a published key, and \
        anyone holding it could mint a download URL for any object in any tenant. \
        Generate one with `openssl rand -base64 32` and supply it as the \
        CDE_STORAGE_SIGNING_KEY environment variable.""")
    boolean isSigningKeyPresentWhenLocal() {
        return provider != Provider.LOCAL || (signingKey != null && !signingKey.isBlank());
    }

    @AssertTrue(message = """
        Only the local storage provider is implemented in this build. The S3, \
        Azure and GCS adapters need cloud SDKs that are not yet approved as \
        dependencies, so selecting one would leave the application with no \
        working storage. See docs/adr/0011-storage-abstraction.md.""")
    boolean isSelectedProviderImplemented() {
        return provider == Provider.LOCAL;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(String localRoot) {
        this.localRoot = localRoot;
    }

    public String getSigningKey() {
        return signingKey;
    }

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }

    public String getDownloadBaseUri() {
        return downloadBaseUri;
    }

    public void setDownloadBaseUri(String downloadBaseUri) {
        this.downloadBaseUri = downloadBaseUri;
    }
}
