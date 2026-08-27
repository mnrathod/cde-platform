package com.cde.platform.storage;

/**
 * What a presigned URL permits. One operation per URL, never a general
 * capability: a URL that allowed both reading and writing would let whoever
 * obtained a download link overwrite the object it points at.
 */
public enum StorageOperation {
    READ,
    WRITE
}
