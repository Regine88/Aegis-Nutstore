package com.beemdevelopment.aegis.backup;

import androidx.annotation.Nullable;

/**
 * Classified failure of a WebDAV request. Messages never contain credentials,
 * request contents or server response bodies.
 */
public class WebDavException extends Exception {
    private final Reason _reason;
    private final int _httpCode;
    private final long _retryAfterSeconds;

    public WebDavException(Reason reason, String message) {
        this(reason, message, 0, -1, null);
    }

    public WebDavException(Reason reason, String message, Throwable cause) {
        this(reason, message, 0, -1, cause);
    }

    public WebDavException(Reason reason, String message, int httpCode, long retryAfterSeconds) {
        this(reason, message, httpCode, retryAfterSeconds, null);
    }

    public WebDavException(Reason reason, String message, int httpCode,
                           long retryAfterSeconds, @Nullable Throwable cause) {
        super(message, cause);
        _reason = reason;
        _httpCode = httpCode;
        _retryAfterSeconds = retryAfterSeconds;
    }

    public Reason getReason() {
        return _reason;
    }

    public int getHttpCode() {
        return _httpCode;
    }

    /**
     * Value of the Retry-After header in seconds, or -1 if absent or not a
     * plain number of seconds.
     */
    public long getRetryAfterSeconds() {
        return _retryAfterSeconds;
    }

    public boolean isRetryable() {
        switch (_reason) {
            case NETWORK:
            case TIMEOUT:
            case RATE_LIMITED:
            case SERVER:
                return true;
            default:
                return false;
        }
    }

    public enum Reason {
        CONFIGURATION,
        NETWORK,
        TIMEOUT,
        CANCELLED,
        AUTHENTICATION,
        PERMISSION,
        NOT_FOUND,
        CONFLICT,
        QUOTA,
        RATE_LIMITED,
        SERVER,
        PROTOCOL,
        TOO_LARGE,
        CLEANUP,
        UNKNOWN
    }
}
