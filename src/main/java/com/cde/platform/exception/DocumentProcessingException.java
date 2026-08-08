package com.cde.platform.exception;

/**
 * Raised when a document-processing step ran but could not produce a result —
 * an unsupported file, an empty region set, a converter-reported error.
 *
 * <p>Carries a message safe to show the user: it describes what they asked for
 * rather than any internal detail.
 */
public class DocumentProcessingException extends RuntimeException {

    public DocumentProcessingException(String message) {
        super(message);
    }

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
