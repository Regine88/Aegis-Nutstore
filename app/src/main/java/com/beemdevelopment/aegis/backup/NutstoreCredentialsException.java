package com.beemdevelopment.aegis.backup;

/**
 * Thrown when the Nutstore (Jianguoyun) configuration or credentials cannot be
 * read, written or decrypted. The reason allows callers to distinguish recoverable
 * states (e.g. credentials that need to be entered again) from storage errors.
 */
public class NutstoreCredentialsException extends Exception {
    private final Reason _reason;

    public NutstoreCredentialsException(Reason reason, String message) {
        super(message);
        _reason = reason;
    }

    public NutstoreCredentialsException(Reason reason, Throwable cause) {
        super(cause);
        _reason = reason;
    }

    public Reason getReason() {
        return _reason;
    }

    public enum Reason {
        STORAGE_CORRUPTED,
        STORAGE_IO,
        UNSUPPORTED_VERSION,
        INVALID_ACCOUNT,
        INVALID_PATH,
        INVALID_RETENTION,
        INVALID_PASSWORD,
        KEY_MISSING,
        KEY_FAILURE,
        CREDENTIALS_MISSING,
        DECRYPT_FAILED
    }
}
