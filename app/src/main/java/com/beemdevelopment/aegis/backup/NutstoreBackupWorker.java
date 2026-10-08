package com.beemdevelopment.aegis.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.beemdevelopment.aegis.Preferences;

/**
 * Persistent background upload. The worker only uses the configuration and
 * the snapshot on disk; it never touches the in-memory vault, so it also works
 * while the vault is locked or after the process was restarted.
 */
public class NutstoreBackupWorker extends Worker {
    /**
     * Maximum number of automatic attempts for one work request. After that
     * the failure is shown to the user and the upload can be retried manually.
     */
    public static final int MAX_ATTEMPTS = 6;

    public NutstoreBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        NutstoreUploader.Result result = new NutstoreUploader(getApplicationContext()).uploadOnce();
        switch (result.getOutcome()) {
            case SUCCESS:
                markBackupReminderSatisfied();
                return Result.success();
            case RETRY:
                return canRetry() ? Result.retry() : Result.failure();
            case ABORTED:
            case FATAL:
            default:
                return Result.failure();
        }
    }

    private boolean canRetry() {
        return getRunAttemptCount() < MAX_ATTEMPTS - 1;
    }

    /**
     * Clears the backup reminder only after the newest state has actually been
     * uploaded. A queued or running upload is not a successful backup.
     */
    private void markBackupReminderSatisfied() {
        try {
            NutstoreBackupStore.State state =
                    new NutstoreBackupStore(getApplicationContext()).loadState();
            if (state.getStatus() == NutstoreBackupStore.Status.SUCCESS
                    && !state.isUploadPending()) {
                new Preferences(getApplicationContext()).setIsBackupReminderNeeded(false);
            }
        } catch (NutstoreBackupException | RuntimeException ignored) {
            // A stale reminder is harmless compared to a failed upload.
        }
    }
}
