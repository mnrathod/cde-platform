package com.cde.platform.upload;

/**
 * Raised when an upload request is refused on its own terms — a chunk index
 * outside the declared total, a chunk over the size limit, a file that would
 * exceed the maximum.
 *
 * <p>Its message is written for the caller and is safe to return: it describes
 * a limit they can see and act on, and names no path, no other tenant, and no
 * internal detail.
 */
public class UploadRejectedException extends RuntimeException {

    public UploadRejectedException(String message) {
        super(message);
    }
}
