package com.cde.platform.storage;

/**
 * The key holds nothing.
 *
 * <p>Separate from {@link StorageException} because the two need different
 * responses: a missing object is usually a 404, while a backend failure is a
 * 503 and should page someone.
 */
public class ObjectNotFoundException extends StorageException {

    private final transient StorageKey key;

    public ObjectNotFoundException(StorageKey key) {
        super("No object at " + key.path());
        this.key = key;
    }

    public StorageKey key() {
        return key;
    }
}
