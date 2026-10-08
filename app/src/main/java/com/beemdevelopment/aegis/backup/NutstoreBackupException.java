package com.beemdevelopment.aegis.backup;

/**
 * Thrown when a backup snapshot or its persistent task state cannot be created,
 * read or validated.
 */
public class NutstoreBackupException extends Exception {
    private final Reason _reason;

    public NutstoreBackupException(Reason reason, String message) {
        super(message);
        _reason = reason;
    }

    public NutstoreBackupException(Reason reason, Throwable cause) {
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
        VAULT_MISSING,
        VAULT_NOT_ENCRYPTED,
        VAULT_NO_PASSWORD_SLOT,
        SNAPSHOT_MISSING,
        SNAPSHOT_CORRUPTED,
        SNAPSHOT_TOO_LARGE
    }
}
