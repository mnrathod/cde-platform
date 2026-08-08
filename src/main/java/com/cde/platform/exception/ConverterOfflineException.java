package com.cde.platform.exception;

/**
 * Raised when the Python converter cannot be reached.
 *
 * <p>Distinguished from a processing failure because the remedy is different:
 * the request itself was fine and will succeed once the converter is back, so
 * it maps to 503 rather than 500 and the client can offer a retry.
 */
public class ConverterOfflineException extends RuntimeException {

    public ConverterOfflineException(String converterUrl) {
        super("Converter unreachable at " + converterUrl);
    }
}
