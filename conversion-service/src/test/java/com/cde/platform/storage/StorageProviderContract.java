package com.cde.platform.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every storage provider must do, regardless of backend.
 *
 * <p>§11 requires that the same suite runs against all four providers, because
 * "no feature code knows which backend is active" is only true if the backends
 * genuinely behave the same. A provider that passes its own bespoke tests and
 * differs subtly from the others turns a cloud migration into a bug hunt.
 *
 * <p>Subclasses supply a provider and a tenant. Everything else is fixed here,
 * so a new backend cannot quietly weaken an assertion — it either passes this
 * suite or it is not shipped.
 */
public abstract class StorageProviderContract {

    /** The provider under test, freshly constructed per test class. */
    protected abstract StorageProvider provider();

    /** A tenant that exists for the duration of the test. */
    protected abstract long tenantId();

    protected StorageKey key(StorageCategory category) {
        return new StorageKey("test", tenantId(), category, UUID.randomUUID() + ".pdf");
    }

    protected static InputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    protected String readBack(StorageKey key) {
        try (InputStream stream = provider().retrieve(key)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new AssertionError("Could not read back " + key.path(), failure);
        }
    }

    private static final StorageMetadata PDF =
        StorageMetadata.forUpload("application/pdf", "drawing.pdf");

    @Nested
    @DisplayName("storing and retrieving")
    class RoundTrip {

        @Test
        @DisplayName("what was stored is what comes back")
        void contentSurvives() {
            StorageKey key = key(StorageCategory.DOCUMENT);
            provider().store(key, content("the quick brown fox"), PDF);

            assertThat(readBack(key)).isEqualTo("the quick brown fox");
        }

        @Test
        @DisplayName("the returned reference reports the size actually written")
        void reportsSize() {
            StorageKey key = key(StorageCategory.DOCUMENT);

            StorageObjectRef ref = provider().store(key, content("twelve chars"), PDF);

            assertThat(ref.sizeBytes()).isEqualTo(12);
        }

        @Test
        @DisplayName("the returned reference carries a SHA-256 of the content")
        void reportsDigest() {
            StorageKey key = key(StorageCategory.DOCUMENT);

            StorageObjectRef ref = provider().store(key, content("abc"), PDF);

            // The published SHA-256 of "abc". Computing the expected value with
            // the same code under test would assert only that the code agrees
            // with itself.
            assertThat(ref.sha256()).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        }

        @Test
        @DisplayName("an empty object round-trips")
        void emptyContent() {
            StorageKey key = key(StorageCategory.DOCUMENT);

            StorageObjectRef ref = provider().store(key, content(""), PDF);

            assertThat(ref.sizeBytes()).isZero();
            assertThat(readBack(key)).isEmpty();
        }

        @Test
        @DisplayName("retrieving a key that holds nothing is not found, not empty")
        void missingIsNotFound() {
            // The distinction matters: returning an empty stream would let a
            // caller treat a missing document as an empty one and carry on.
            assertThatThrownBy(() -> provider().retrieve(key(StorageCategory.DOCUMENT)))
                .isInstanceOf(ObjectNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("existence and metadata")
    class Interrogation {

        @Test
        @DisplayName("a stored object exists and a fresh key does not")
        void existence() {
            StorageKey stored = key(StorageCategory.DOCUMENT);
            provider().store(stored, content("x"), PDF);

            assertThat(provider().exists(stored)).isTrue();
            assertThat(provider().exists(key(StorageCategory.DOCUMENT))).isFalse();
        }

        @Test
        @DisplayName("metadata reports the stored size without transferring content")
        void metadataSize() {
            StorageKey key = key(StorageCategory.DOCUMENT);
            provider().store(key, content("0123456789"), PDF);

            assertThat(provider().metadataOf(key))
                .isPresent()
                .get()
                .extracting(StorageMetadata::sizeBytes)
                .isEqualTo(10L);
        }

        @Test
        @DisplayName("metadata for a missing object is empty rather than an error")
        void metadataMissing() {
            assertThat(provider().metadataOf(key(StorageCategory.DOCUMENT))).isEmpty();
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("lists what this tenant stored in this category")
        void listsOwnCategory() {
            StorageKey first = key(StorageCategory.DOCUMENT);
            StorageKey second = key(StorageCategory.DOCUMENT);
            provider().store(first, content("a"), PDF);
            provider().store(second, content("b"), PDF);

            assertThat(provider().listCategory(first))
                .extracting(StorageKey::objectId)
                .contains(first.objectId(), second.objectId());
        }

        @Test
        @DisplayName("does not list another category's objects")
        void categoriesAreSeparate() {
            StorageKey document = key(StorageCategory.DOCUMENT);
            StorageKey export = key(StorageCategory.EXPORT);
            provider().store(document, content("a"), PDF);
            provider().store(export, content("b"), PDF);

            assertThat(provider().listCategory(document))
                .extracting(StorageKey::objectId)
                .doesNotContain(export.objectId());
        }

        @Test
        @DisplayName("does not list another tenant's objects")
        void tenantsAreSeparate() {
            StorageKey ours = key(StorageCategory.DOCUMENT);
            provider().store(ours, content("ours"), PDF);

            StorageKey theirs = new StorageKey(
                "test", tenantId() + 1, StorageCategory.DOCUMENT, UUID.randomUUID() + ".pdf");
            provider().store(theirs, content("theirs"), PDF);

            // The headline property of the whole abstraction. If this ever
            // fails, it is a cross-tenant leak and not a listing bug.
            assertThat(provider().listCategory(ours))
                .extracting(StorageKey::objectId)
                .doesNotContain(theirs.objectId());
        }

        @Test
        @DisplayName("an empty category lists nothing rather than failing")
        void emptyCategory() {
            assertThat(provider().listCategory(key(StorageCategory.EXPORT))).isEmpty();
        }
    }

    @Nested
    @DisplayName("copy, move and delete")
    class Movement {

        @Test
        @DisplayName("a copy leaves the source in place")
        void copyKeepsSource() {
            StorageKey from = key(StorageCategory.QUARANTINE);
            StorageKey to = key(StorageCategory.DOCUMENT);
            provider().store(from, content("scanned clean"), PDF);

            provider().copy(from, to);

            assertThat(provider().exists(from)).isTrue();
            assertThat(readBack(to)).isEqualTo("scanned clean");
        }

        @Test
        @DisplayName("a move removes the source")
        void moveRemovesSource() {
            StorageKey from = key(StorageCategory.QUARANTINE);
            StorageKey to = key(StorageCategory.DOCUMENT);
            provider().store(from, content("promoted"), PDF);

            provider().move(from, to);

            assertThat(provider().exists(from)).isFalse();
            assertThat(readBack(to)).isEqualTo("promoted");
        }

        @Test
        @DisplayName("copying something that is not there is not found")
        void copyMissing() {
            assertThatThrownBy(() ->
                provider().copy(key(StorageCategory.DOCUMENT), key(StorageCategory.DOCUMENT)))
                .isInstanceOf(ObjectNotFoundException.class);
        }

        @Test
        @DisplayName("deleting is idempotent")
        void deleteIsIdempotent() {
            StorageKey key = key(StorageCategory.DOCUMENT);
            provider().store(key, content("x"), PDF);

            provider().delete(key);
            // A retry after a partial failure must not turn into an error;
            // the caller's intent is "this should not exist", and it doesn't.
            provider().delete(key);

            assertThat(provider().exists(key)).isFalse();
        }
    }

    @Nested
    @DisplayName("chunked upload")
    class Multipart {

        @Test
        @DisplayName("parts are assembled in the order they were appended")
        void assemblesInOrder() {
            StorageKey key = key(StorageCategory.DOCUMENT);
            MultipartUpload upload = provider().initiateMultipartUpload(key, PDF);

            upload.appendPart(content("one "));
            upload.appendPart(content("two "));
            upload.appendPart(content("three"));
            provider().completeMultipartUpload(upload);

            assertThat(readBack(key)).isEqualTo("one two three");
        }

        @Test
        @DisplayName("nothing is visible at the key until the upload completes")
        void invisibleUntilComplete() {
            StorageKey key = key(StorageCategory.DOCUMENT);
            MultipartUpload upload = provider().initiateMultipartUpload(key, PDF);
            upload.appendPart(content("half a document"));

            // A half-written file that became readable would be served to
            // users and, worse, scanned as though it were complete — and a
            // scanner shown a fragment reports the fragment clean.
            assertThat(provider().exists(key)).isFalse();

            provider().completeMultipartUpload(upload);
            assertThat(provider().exists(key)).isTrue();
        }

        @Test
        @DisplayName("progress is reported as parts arrive")
        void reportsProgress() {
            MultipartUpload upload =
                provider().initiateMultipartUpload(key(StorageCategory.DOCUMENT), PDF);

            upload.appendPart(content("12345"));
            assertThat(upload.bytesWritten()).isEqualTo(5);

            upload.appendPart(content("678"));
            assertThat(upload.bytesWritten()).isEqualTo(8);
        }

        @Test
        @DisplayName("an aborted upload leaves nothing behind")
        void abortLeavesNothing() {
            StorageKey key = key(StorageCategory.DOCUMENT);
            MultipartUpload upload = provider().initiateMultipartUpload(key, PDF);
            upload.appendPart(content("abandoned"));

            provider().abortMultipartUpload(upload);

            assertThat(provider().exists(key)).isFalse();
            assertThat(provider().listCategory(key)).isEmpty();
        }

        @Test
        @DisplayName("the completed reference digests the assembled whole")
        void digestsTheWhole() {
            StorageKey key = key(StorageCategory.DOCUMENT);
            MultipartUpload upload = provider().initiateMultipartUpload(key, PDF);
            upload.appendPart(content("a"));
            upload.appendPart(content("b"));
            upload.appendPart(content("c"));

            StorageObjectRef ref = provider().completeMultipartUpload(upload);

            // Same expected digest as the single-shot "abc" case: how the bytes
            // arrived must not change what they hash to.
            assertThat(ref.sha256()).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThat(ref.sizeBytes()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("presigned URLs")
    class Presigned {

        @Test
        @DisplayName("a URL is issued for a stored object")
        void issuesUrl() {
            StorageKey key = key(StorageCategory.DOCUMENT);
            provider().store(key, content("x"), PDF);

            assertThat(provider().presignedUrl(key, Duration.ofMinutes(5), StorageOperation.READ))
                .asString()
                .contains(key.objectId());
        }

        @Test
        @DisplayName("read and write URLs differ")
        void operationsDiffer() {
            StorageKey key = key(StorageCategory.DOCUMENT);
            provider().store(key, content("x"), PDF);

            // If they were the same, handing someone a download link would hand
            // them the ability to overwrite what it points at.
            assertThat(provider().presignedUrl(key, Duration.ofMinutes(5), StorageOperation.READ))
                .isNotEqualTo(
                    provider().presignedUrl(key, Duration.ofMinutes(5), StorageOperation.WRITE));
        }

        @Test
        @DisplayName("a request for longer than the cap still yields a URL")
        void capsRatherThanRefuses() {
            StorageKey key = key(StorageCategory.DOCUMENT);
            provider().store(key, content("x"), PDF);

            // Capping keeps callers using presigned URLs; refusing pushes them
            // toward streaming everything through the application instead.
            assertThat(provider().presignedUrl(key, Duration.ofDays(1), StorageOperation.READ))
                .isNotNull();
        }
    }

    @Nested
    @DisplayName("self-test")
    class SelfTest {

        @Test
        @DisplayName("a reachable backend passes")
        void reachable() {
            provider().verifyReachable();
        }

        @Test
        @DisplayName("the provider names itself")
        void namesItself() {
            assertThat(provider().providerName()).isNotBlank();
        }
    }
}
