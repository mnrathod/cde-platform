package com.cde.platform.storage;

/**
 * A storage backend failed.
 *
 * <p>Provider-specific exception types stop here. A caller that catches an
 * SDK's own exception class has bound itself to that provider, which defeats
 * the abstraction the interface exists to provide, so implementations
 * translate rather than propagate.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
