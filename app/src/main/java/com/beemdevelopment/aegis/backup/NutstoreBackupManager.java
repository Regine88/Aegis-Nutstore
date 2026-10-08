package com.beemdevelopment.aegis.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates Nutstore backups: turns vault saves into persistent upload
 * requests, wakes the upload worker and cancels work when the user changes the
 * configuration or turns the feature off.
 */
public class NutstoreBackupManager {
    public static final String UNIQUE_WORK_NAME = "nutstore-backup-upload";

    /**
     * Backoff of the upload worker. With six attempts the worker waits for
     * roughly half an hour in total, which covers the Nutstore request window
     * before it gives up and leaves the state to a manual retry.
     */
    public static final long BACKOFF_SECONDS = 60;

    private static final AtomicReference<WebDavClient> ACTIVE_CLIENT = new AtomicReference<>();

    private final Context _context;
    private final NutstoreCredentialStore _credentialStore;
    private final NutstoreBackupStore _backupStore;

    public NutstoreBackupManager(Context context) {
        this(context, new NutstoreCredentialStore(context), new NutstoreBackupStore(context));
    }

    /**
     * Test seam: allows unit tests to provide stores with an in-memory
     * Keystore replacement.
     */
    NutstoreBackupManager(Context context, NutstoreCredentialStore credentialStore,
                          NutstoreBackupStore backupStore) {
        _context = context.getApplicationContext();
        _credentialStore = credentialStore;
        _backupStore = backupStore;
    }

    /**
     * Called after a successful local save. This method never throws: a
     * problem in the cloud queue must not turn a successful local save into a
     * failure. Nothing is scheduled when automatic backups are disabled.
     */
    public void requestBackupIfEnabled() {
        try {
            NutstoreCredentialStore.Config config = _credentialStore.loadConfig();
            if (!config.isAutoBackupEnabled() || !isUsable(config)) {
                return;
            }
            _backupStore.requestBackup(config.getConfigVersion());
            enqueueUpload();
        } catch (NutstoreCredentialsException | NutstoreBackupException | RuntimeException e) {
            // The local save already succeeded; the next save will retry.
        }
    }

    /**
     * Called during application startup: reinstates a pending upload that was
     * interrupted by a process death and removes orphaned local snapshots.
     * This is best effort and never throws.
     */
    public void resumePendingWork() {
        try {
            NutstoreCredentialStore.Config config = _credentialStore.loadConfig();
            if (!config.isAutoBackupEnabled() || !isUsable(config)) {
                return;
            }
            NutstoreBackupStore.State state = _backupStore.loadState();
            _backupStore.pruneLocalSnapshots(NutstoreBackupStore.MAX_LOCAL_SNAPSHOTS);
            if (state.isUploadPending()) {
                enqueueUpload();
            }
        } catch (NutstoreCredentialsException | NutstoreBackupException | RuntimeException e) {
            // The settings screen reports problems; startup must not crash.
        }
    }

    /**
     * Records a backup request and wakes the upload worker. Used both by the
     * automatic hook and by the "back up now" action.
     */
    @NonNull
    public NutstoreBackupStore.State requestBackup()
            throws NutstoreCredentialsException, NutstoreBackupException {
        NutstoreCredentialStore.Config config = _credentialStore.loadConfig();
        if (!isUsable(config) || !_credentialStore.isConfigured()) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.CREDENTIALS_MISSING,
                    "The Nutstore account has not been configured completely");
        }

        NutstoreBackupStore.State state = _backupStore.requestBackup(config.getConfigVersion());
        enqueueUpload();
        return state;
    }

    public void enqueueUpload() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(NutstoreBackupWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build();

        // APPEND_OR_REPLACE keeps a single serial chain: a request that arrives
        // while an upload is running is handled by the next worker, and a
        // failed or canceled chain is replaced instead of being lost.
        WorkManager.getInstance(_context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    /**
     * Stops pending work and cancels a request that is currently in flight.
     */
    public void cancelWork() {
        WorkManager.getInstance(_context).cancelUniqueWork(UNIQUE_WORK_NAME);
        cancelActiveClient();
    }

    /**
     * Stops pending work and deletes snapshots that have not been uploaded.
     */
    public void stopAndClearPending() throws NutstoreBackupException {
        cancelWork();
        _backupStore.clearPending();
    }

    /**
     * Stops pending work and deletes the persistent task state and snapshots.
     */
    public void clearAll() throws NutstoreBackupException {
        cancelWork();
        _backupStore.clearAll();
    }

    @NonNull
    public NutstoreBackupStore.State loadState() throws NutstoreBackupException {
        return _backupStore.loadState();
    }

    @NonNull
    public NutstoreBackupStore.State resetForConfig(int configVersion) throws NutstoreBackupException {
        return _backupStore.resetForConfig(configVersion);
    }

    private static boolean isUsable(NutstoreCredentialStore.Config config) {
        return config.getAccount() != null
                && config.isPasswordStored()
                && config.getDeviceId() != null;
    }

    static void setActiveClient(WebDavClient client) {
        ACTIVE_CLIENT.set(client);
    }

    static void clearActiveClient(WebDavClient client) {
        ACTIVE_CLIENT.compareAndSet(client, null);
    }

    /**
     * Cancels the request that a worker is currently executing, if any.
     */
    public static void cancelActiveClient() {
        WebDavClient client = ACTIVE_CLIENT.getAndSet(null);
        if (client != null) {
            client.cancel();
        }
    }

    /**
     * Cancels every pending operation and removes all local traces of the
     * Nutstore backup: queued work, the running request, snapshots, the task
     * state and the stored credentials. Used by the panic trigger and when
     * vault encryption is turned off. Remote backups are not touched.
     */
    public static void wipe(Context context) {
        Context appContext = context.getApplicationContext();

        WebDavClient client = ACTIVE_CLIENT.getAndSet(null);
        if (client != null) {
            client.cancel();
        }
        try {
            WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME);
        } catch (RuntimeException ignored) {
            // WorkManager may not be available in restricted app processes.
        }
        try {
            new NutstoreBackupStore(appContext).clearAll();
        } catch (NutstoreBackupException ignored) {
            // Best effort: the vault itself is cleared afterwards.
        }
        try {
            new NutstoreCredentialStore(appContext).clearAll();
        } catch (NutstoreCredentialsException ignored) {
            // Best effort: the vault itself is cleared afterwards.
        }
    }
}
