package com.cde.platform.fetch;

/**
 * A permitted URL was attempted and did not yield the content.
 *
 * <p>Distinct from {@link FetchNotPermittedException}, which means we refused
 * to try. The difference matters to whoever is integrating: one says "change
 * the link", the other says "the link is fine, the far end did not co-operate",
 * and collapsing them produces the error message that sends people looking in
 * the wrong place (§1.4).
 *
 * <p>Messages describe the far end and never this deployment's internals — no
 * resolved addresses, no internal host names, no stack detail.
 */
public class ContentFetchFailedException extends RuntimeException {

    public ContentFetchFailedException(String message) {
        super(message);
    }

    public ContentFetchFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
