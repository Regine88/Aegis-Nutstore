package com.beemdevelopment.aegis.backup;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Executes a single upload attempt: make sure an immutable snapshot exists,
 * upload it, verify the remote content, confirm the upload and finally remove
 * old remote versions.
 *
 * The class is deliberately free of WorkManager types so that the whole
 * sequence can be tested against a simulated WebDAV service.
 */
class NutstoreUploader {
    enum Outcome {
        SUCCESS,
        RETRY,
        FATAL,
        ABORTED
    }

    static class Result {
        private final Outcome _outcome;
        private final NutstoreBackupStore.FailureKind _failure;

        Result(Outcome outcome, NutstoreBackupStore.FailureKind failure) {
            _outcome = outcome;
            _failure = failure;
        }

        static Result success() {
            return new Result(Outcome.SUCCESS, NutstoreBackupStore.FailureKind.NONE);
        }

        Outcome getOutcome() {
            return _outcome;
        }

        NutstoreBackupStore.FailureKind getFailure() {
            return _failure;
        }
    }

    interface ClientFactory {
        WebDavClient create(String account, char[] password, String rootPath) throws WebDavException;
    }

    private final NutstoreCredentialStore _credentials;
    private final NutstoreBackupStore _store;
    private final ClientFactory _clientFactory;

    NutstoreUploader(Context context) {
        this(new NutstoreCredentialStore(context), new NutstoreBackupStore(context),
                new DefaultClientFactory());
    }

    NutstoreUploader(NutstoreCredentialStore credentials, NutstoreBackupStore store,
                     ClientFactory clientFactory) {
        _credentials = credentials;
        _store = store;
        _clientFactory = clientFactory;
    }

    @NonNull
    Result uploadOnce() {
        NutstoreCredentialStore.Config config;
        try {
            config = _credentials.loadConfig();
        } catch (NutstoreCredentialsException e) {
            return new Result(Outcome.FATAL, NutstoreBackupStore.FailureKind.CONFIGURATION);
        }

        NutstoreBackupStore.State state;
        try {
            state = _store.loadState();
        } catch (NutstoreBackupException e) {
            return new Result(Outcome.FATAL, NutstoreBackupStore.FailureKind.STORAGE);
        }

        if (!state.isUploadPending()) {
            return Result.success();
        }

        // The configuration that created the request no longer matches the
        // current account, directory or password.
        if (state.getConfigVersion() != config.getConfigVersion()) {
            return resetForConfig(config.getConfigVersion());
        }

        if (config.getAccount() == null || config.getDeviceId() == null
                || !config.isPasswordStored() || !_credentials.isConfigured()) {
            return fail(NutstoreBackupStore.FailureKind.CREDENTIALS, 0);
        }

        // Respect a Retry-After window before touching the network again.
        long now = System.currentTimeMillis() / 1000;
        if (state.getLastFailure() == NutstoreBackupStore.FailureKind.RATE_LIMITED
                && state.getLastRetryAfterSeconds() > 0
                && now < state.getLastFailureAt() + state.getLastRetryAfterSeconds()) {
            return new Result(Outcome.RETRY, NutstoreBackupStore.FailureKind.RATE_LIMITED);
        }

        try {
            if (state.isSnapshotOutdated()) {
                _store.createSnapshot(config.getConfigVersion(), state.getRequestVersion());
                state = _store.loadState();
            }
        } catch (NutstoreBackupException e) {
            NutstoreBackupStore.FailureKind kind = mapSnapshotFailure(e.getReason());
            return fail(kind, 0);
        }

        NutstoreBackupStore.Snapshot snapshot = state.getSnapshot();
        if (snapshot == null) {
            return fail(NutstoreBackupStore.FailureKind.SNAPSHOT, 0);
        }

        WebDavClient client;
        char[] password = null;
        try {
            password = _credentials.readPassword();
            client = _clientFactory.create(config.getAccount(), password, config.getRootPath());
        } catch (NutstoreCredentialsException e) {
            return fail(NutstoreBackupStore.FailureKind.CREDENTIALS, 0);
        } catch (WebDavException e) {
            return fail(NutstoreBackupStore.FailureKind.CONFIGURATION, 0);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }

        NutstoreBackupManager.setActiveClient(client);
        try {
            String deviceDir = config.getRootPath() + "/" + config.getDeviceId();
            client.ensureDirectory(deviceDir);

            // The user may have changed the account or directory while the
            // snapshot was prepared. Never upload old content to a new target.
            NutstoreCredentialStore.Config current;
            try {
                current = _credentials.loadConfig();
            } catch (NutstoreCredentialsException e) {
                return fail(NutstoreBackupStore.FailureKind.CONFIGURATION, 0);
            }
            if (current.getConfigVersion() != config.getConfigVersion()) {
                return resetForConfig(current.getConfigVersion());
            }

            _store.markUploading();
            byte[] bytes = _store.readSnapshot(snapshot);
            String remotePath = deviceDir + "/" + snapshot.getRemoteName();
            client.upload(remotePath, bytes);

            byte[] downloaded = client.download(remotePath, WebDavClient.MAX_DOWNLOAD_BYTES);
            if (!NutstoreBackupStore.sha256(downloaded).equals(snapshot.getSha256())) {
                // The upload succeeded but the content could not be verified.
                // Keep the snapshot and the old remote versions for a retry.
                return fail(NutstoreBackupStore.FailureKind.SERVER, 0);
            }

            long confirmedAt = System.currentTimeMillis() / 1000;
            _store.confirmUpload(snapshot.getRequestVersion(), confirmedAt);

            try {
                pruneRemote(client, deviceDir, config.getRetention(), snapshot.getRemoteName());
            } catch (WebDavException e) {
                // Cleanup problems must not turn a confirmed backup into a
                // failure, but they are recorded separately.
                try {
                    _store.markCleanupFailure(confirmedAt);
                } catch (NutstoreBackupException ignored) {
                    // Nothing else can be done; the upload itself succeeded.
                }
            }
            return Result.success();
        } catch (WebDavException e) {
            if (e.getReason() == WebDavException.Reason.CANCELLED) {
                // The user stopped the work; the manager clears the pending
                // state, so nothing is recorded here.
                return new Result(Outcome.ABORTED, NutstoreBackupStore.FailureKind.NONE);
            }
            return fail(mapWebDavFailure(e.getReason()), e.getRetryAfterSeconds());
        } catch (NutstoreBackupException e) {
            return fail(NutstoreBackupStore.FailureKind.SNAPSHOT, 0);
        } finally {
            NutstoreBackupManager.clearActiveClient(client);
            client.close();
        }
    }

    /**
     * Keeps only the newest {@code retention} managed files in this device
     * directory. Files that do not match the app naming scheme and files of
     * other devices are never touched. An incomplete listing disables the
     * cleanup for this run.
     */
    private static void pruneRemote(WebDavClient client, String deviceDir, int retention,
                                    String currentRemoteName) throws WebDavException {
        WebDavClient.ListResult list = client.list(deviceDir);
        if (!list.isComplete()) {
            throw new WebDavException(WebDavException.Reason.PROTOCOL,
                    "The server directory listing is incomplete; keeping all remote versions");
        }

        List<WebDavClient.RemoteEntry> managed = new ArrayList<>();
        for (WebDavClient.RemoteEntry entry : list.getEntries()) {
            if (!entry.isDirectory()
                    && NutstoreBackupStore.MANAGED_FILE_PATTERN.matcher(entry.getName()).matches()) {
                managed.add(entry);
            }
        }

        // File names embed a UTC timestamp, so the lexicographic order is also
        // the chronological order.
        managed.sort((a, b) -> b.getName().compareTo(a.getName()));
        for (int i = retention; i < managed.size(); i++) {
            // The version that was just confirmed is never deleted, even if
            // the device clock moved backwards and it sorts as the oldest one.
            if (!managed.get(i).getName().equals(currentRemoteName)) {
                client.delete(managed.get(i).getPath());
            }
        }
    }

    private Result resetForConfig(int configVersion) {
        try {
            _store.resetForConfig(configVersion);
        } catch (NutstoreBackupException e) {
            return new Result(Outcome.FATAL, NutstoreBackupStore.FailureKind.STORAGE);
        }
        return Result.success();
    }

    private Result fail(NutstoreBackupStore.FailureKind kind, long retryAfterSeconds) {
        try {
            _store.markFailure(kind, System.currentTimeMillis() / 1000, retryAfterSeconds);
        } catch (NutstoreBackupException ignored) {
            // The failure itself could not be persisted; it is still reported.
        }

        switch (kind) {
            case NETWORK:
            case TIMEOUT:
            case RATE_LIMITED:
            case SERVER:
                return new Result(Outcome.RETRY, kind);
            default:
                return new Result(Outcome.FATAL, kind);
        }
    }

    private static NutstoreBackupStore.FailureKind mapSnapshotFailure(
            NutstoreBackupException.Reason reason) {
        switch (reason) {
            case VAULT_MISSING:
            case VAULT_NOT_ENCRYPTED:
            case VAULT_NO_PASSWORD_SLOT:
                return NutstoreBackupStore.FailureKind.SNAPSHOT;
            default:
                return NutstoreBackupStore.FailureKind.STORAGE;
        }
    }

    private static NutstoreBackupStore.FailureKind mapWebDavFailure(WebDavException.Reason reason) {
        switch (reason) {
            case NETWORK:
                return NutstoreBackupStore.FailureKind.NETWORK;
            case TIMEOUT:
                return NutstoreBackupStore.FailureKind.TIMEOUT;
            case AUTHENTICATION:
                return NutstoreBackupStore.FailureKind.AUTHENTICATION;
            case PERMISSION:
                return NutstoreBackupStore.FailureKind.PERMISSION;
            case NOT_FOUND:
                return NutstoreBackupStore.FailureKind.NOT_FOUND;
            case CONFLICT:
                return NutstoreBackupStore.FailureKind.CONFLICT;
            case QUOTA:
                return NutstoreBackupStore.FailureKind.QUOTA;
            case RATE_LIMITED:
                return NutstoreBackupStore.FailureKind.RATE_LIMITED;
            case SERVER:
                return NutstoreBackupStore.FailureKind.SERVER;
            case CONFIGURATION:
                return NutstoreBackupStore.FailureKind.CONFIGURATION;
            default:
                return NutstoreBackupStore.FailureKind.UNKNOWN;
        }
    }

    private static class DefaultClientFactory implements ClientFactory {
        @Override
        public WebDavClient create(String account, char[] password, String rootPath)
                throws WebDavException {
            return new WebDavClient(account, password, rootPath);
        }
    }
}
