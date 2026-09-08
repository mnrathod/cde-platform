package com.cde.platform.conversion;

/**
 * The system has no room to accept another conversion right now.
 *
 * <p>Answered as 429 with a {@code Retry-After}, not 500: nothing is broken,
 * the queue is full. Refusing is the honest answer — accepting work the system
 * cannot get to produces a job that sits at PENDING until a restart fails it,
 * which reads as a bug to whoever submitted it.
 */
public class ConversionQueueFullException extends RuntimeException {

    private final int retryAfterSeconds;

    public ConversionQueueFullException(int retryAfterSeconds) {
        super("The conversion queue is full. Try again shortly.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
