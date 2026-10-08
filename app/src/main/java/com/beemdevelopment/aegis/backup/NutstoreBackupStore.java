package com.beemdevelopment.aegis.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.AtomicFile;

import com.beemdevelopment.aegis.encoding.Hex;
import com.beemdevelopment.aegis.util.IOUtils;
import com.beemdevelopment.aegis.vault.VaultFile;
import com.beemdevelopment.aegis.vault.VaultRepository;
import com.beemdevelopment.aegis.vault.VaultRepositoryException;
import com.beemdevelopment.aegis.vault.slots.PasswordSlot;
import com.beemdevelopment.aegis.vault.slots.SlotList;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Persists backup snapshots and the upload task state.
 *
 * A snapshot is an immutable copy of the standard, encrypted Aegis export
 * format, written atomically to {@code noBackupFilesDir}. Because the vault
 * ciphertext is reused, a snapshot can be created while the vault is locked
 * and a worker can upload the exact same bytes after the process was killed.
 */
public class NutstoreBackupStore {
    /**
     * Number of snapshot files kept on disk, including the current one. Older
     * snapshots are only removed after the current snapshot has been uploaded.
     */
    public static final int MAX_LOCAL_SNAPSHOTS = 3;

    /**
     * Remote files that this app is allowed to delete during cleanup. Any
     * other file in a device directory is left alone.
     */
    public static final Pattern MANAGED_FILE_PATTERN =
            Pattern.compile("^aegis-\\d{8}-\\d{6}-[0-9a-f]{8}\\.json$");

    private static final String STATE_FILENAME = "state.json";
    private static final String SNAPSHOTS_DIR = "snapshots";
    private static final int STATE_VERSION = 1;
    private static final Pattern SNAPSHOT_ID_PATTERN = Pattern.compile("^[0-9a-f]{8}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final DateTimeFormatter REMOTE_NAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).withZone(ZoneOffset.UTC);

    private static final Object LOCK = new Object();

    private final Context _context;
    private final File _dir;

    public NutstoreBackupStore(Context context) {
        _context = context;
        _dir = new File(context.getNoBackupFilesDir(), NutstoreCredentialStore.DIR_NAME);
    }

    /**
     * Loads the persisted state. Returns a default state if nothing has been
     * stored yet and throws if an existing state cannot be parsed. References
     * to missing or damaged snapshots are dropped so that they can never be
     * reported as successful.
     */
    @NonNull
    public State loadState() throws NutstoreBackupException {
        synchronized (LOCK) {
            cleanupIncompleteWrites();

            JSONObject obj = readStateJson();
            if (obj == null) {
                return new State();
            }

            try {
                if (obj.optInt("version", 0) > STATE_VERSION) {
                    throw new NutstoreBackupException(
                            NutstoreBackupException.Reason.UNSUPPORTED_VERSION,
                            "The stored backup state uses an unsupported version");
                }

                State state = new State();
                state.setConfigVersion(obj.optInt("configVersion", 0));
                state.setRequestVersion(obj.optInt("requestVersion", 0));
                state.setConfirmedVersion(obj.optInt("confirmedVersion", 0));
                state.setStatus(parseEnum(Status.class, obj.optString("status", Status.NONE.name())));
                state.setLastSuccessAt(obj.optLong("lastSuccessAt", 0));
                state.setLastFailure(parseEnum(FailureKind.class,
                        obj.optString("lastFailure", FailureKind.NONE.name())));
                state.setLastFailureAt(obj.optLong("lastFailureAt", 0));
                state.setLastRetryAfterSeconds(obj.optLong("lastRetryAfter", 0));
                state.setCleanupFailedAt(obj.optLong("cleanupFailedAt", 0));

                JSONObject snapshotObj = obj.optJSONObject("snapshot");
                if (snapshotObj != null) {
                    Snapshot snapshot = Snapshot.fromJson(snapshotObj);
                    if (isSnapshotUsable(snapshot)) {
                        state.setSnapshot(snapshot);
                    } else {
                        // The metadata survived but the file did not: the
                        // snapshot must be generated again instead of being
                        // treated as an uploaded backup.
                        state.setStatus(state.isUploadPending() ? Status.PENDING : state.getStatus());
                    }
                }

                if (state.getConfirmedVersion() > state.getRequestVersion()) {
                    throw new NutstoreBackupException(
                            NutstoreBackupException.Reason.STORAGE_CORRUPTED,
                            "The stored backup state is inconsistent");
                }
                return state;
            } catch (JSONException | IllegalArgumentException e) {
                throw new NutstoreBackupException(
                        NutstoreBackupException.Reason.STORAGE_CORRUPTED, e);
            }
        }
    }

    /**
     * Records that a new backup was requested. Repeated requests only move the
     * request version forward, so a burst of vault changes results in a single
     * pending upload of the newest state.
     */
    @NonNull
    public State requestBackup(int configVersion) throws NutstoreBackupException {
        synchronized (LOCK) {
            State state = loadState();
            if (state.getConfigVersion() != configVersion) {
                resetForConfig(state, configVersion);
            }

            state.setRequestVersion(state.getRequestVersion() + 1);
            if (state.getStatus() != Status.UPLOADING) {
                state.setStatus(Status.PENDING);
            }
            saveState(state);
            return state;
        }
    }

    /**
     * Invalidates the pending queue after the account, directory or password
     * changed. Old requests must never be uploaded to the new destination.
     */
    @NonNull
    public State resetForConfig(int configVersion) throws NutstoreBackupException {
        synchronized (LOCK) {
            State state = loadState();
            resetForConfig(state, configVersion);
            saveState(state);
            return state;
        }
    }

    /**
     * Drops everything that is still waiting to be uploaded, for example when
     * automatic backups are turned off. The last success timestamp is kept so
     * that the reminder logic still knows when the vault was last backed up.
     */
    @NonNull
    public State clearPending() throws NutstoreBackupException {
        synchronized (LOCK) {
            State state = loadState();
            Snapshot snapshot = state.getSnapshot();
            if (snapshot != null) {
                deleteSnapshotFile(snapshot);
            }
            state.setSnapshot(null);
            state.setRequestVersion(state.getConfirmedVersion());
            state.setStatus(Status.NONE);
            state.setLastFailure(FailureKind.NONE);
            state.setLastFailureAt(0);
            state.setLastRetryAfterSeconds(0);
            state.setCleanupFailedAt(0);
            saveState(state);
            IOUtils.clearDirectory(getSnapshotDir(), false);
            return state;
        }
    }

    @NonNull
    public State markUploading() throws NutstoreBackupException {
        synchronized (LOCK) {
            State state = loadState();
            state.setStatus(Status.UPLOADING);
            saveState(state);
            return state;
        }
    }

    /**
     * Records a fully verified upload. The snapshot that was uploaded is
     * removed, because its content is now confirmed to exist remotely.
     */
    @NonNull
    public State confirmUpload(int requestVersion, long confirmedAt) throws NutstoreBackupException {
        synchronized (LOCK) {
            State state = loadState();
            state.setConfirmedVersion(Math.max(state.getConfirmedVersion(), requestVersion));

            Snapshot snapshot = state.getSnapshot();
            if (snapshot != null && snapshot.getRequestVersion() <= requestVersion) {
                deleteSnapshotFile(snapshot);
                state.setSnapshot(null);
            }

            state.setStatus(requestVersion >= state.getRequestVersion() ? Status.SUCCESS : Status.PENDING);
            state.setLastSuccessAt(confirmedAt);
            state.setLastFailure(FailureKind.NONE);
            state.setLastFailureAt(0);
            state.setLastRetryAfterSeconds(0);
            saveState(state);
            return state;
        }
    }

    @NonNull
    public State markFailure(FailureKind kind, long failedAt) throws NutstoreBackupException {
        return markFailure(kind, failedAt, 0);
    }

    /**
     * Records a failed attempt. {@code retryAfterSeconds} carries the value of
     * a Retry-After header so that the next run can wait long enough before
     * contacting the server again.
     */
    @NonNull
    public State markFailure(FailureKind kind, long failedAt, long retryAfterSeconds)
            throws NutstoreBackupException {
        synchronized (LOCK) {
            State state = loadState();
            state.setStatus(Status.FAILED);
            state.setLastFailure(kind);
            state.setLastFailureAt(failedAt);
            state.setLastRetryAfterSeconds(retryAfterSeconds > 0 ? retryAfterSeconds : 0);
            saveState(state);
            return state;
        }
    }

    /**
     * Cleanup failures are recorded separately: the upload itself succeeded,
     * so the backup status must not be turned into a failure.
     */
    @NonNull
    public State markCleanupFailure(long failedAt) throws NutstoreBackupException {
        synchronized (LOCK) {
            State state = loadState();
            state.setCleanupFailedAt(failedAt);
            saveState(state);
            return state;
        }
    }

    /**
     * Creates an immutable snapshot of the current encrypted vault file.
     * Fails when the vault is not encrypted or when it has no password slot
     * that can be used to restore it.
     */
    @NonNull
    public Snapshot createSnapshot(int configVersion, int requestVersion)
            throws NutstoreBackupException {
        synchronized (LOCK) {
            State state = loadState();
            if (state.getConfigVersion() != configVersion) {
                resetForConfig(state, configVersion);
            }
            if (state.getSnapshot() != null && !state.isSnapshotOutdated()) {
                // The current snapshot already covers the latest request.
                return state.getSnapshot();
            }

            VaultFile vaultFile;
            try {
                vaultFile = VaultRepository.readExportableVaultFile(_context);
            } catch (VaultRepositoryException e) {
                throw new NutstoreBackupException(
                        NutstoreBackupException.Reason.VAULT_MISSING, e);
            }

            if (!vaultFile.isEncrypted()) {
                throw new NutstoreBackupException(
                        NutstoreBackupException.Reason.VAULT_NOT_ENCRYPTED,
                        "Cloud backups are only allowed for an encrypted vault");
            }

            SlotList slots = vaultFile.getHeader().getSlots();
            if (slots == null || slots.findAll(PasswordSlot.class).isEmpty()) {
                throw new NutstoreBackupException(
                        NutstoreBackupException.Reason.VAULT_NO_PASSWORD_SLOT,
                        "The vault has no password slot that can restore this backup");
            }

            byte[] bytes = vaultFile.toBytes();
            if (bytes.length > WebDavClient.MAX_UPLOAD_BYTES) {
                throw new NutstoreBackupException(
                        NutstoreBackupException.Reason.SNAPSHOT_TOO_LARGE,
                        String.format("The vault export is %d bytes; the limit is %d",
                                bytes.length, WebDavClient.MAX_UPLOAD_BYTES));
            }

            long now = System.currentTimeMillis() / 1000;
            Snapshot snapshot = new Snapshot(
                    randomHex(4),
                    buildRemoteName(now),
                    sha256(bytes),
                    bytes.length,
                    now,
                    configVersion,
                    requestVersion);

            writeSnapshotFile(snapshot, bytes);
            state.setSnapshot(snapshot);
            state.setStatus(Status.PENDING);
            state.setConfigVersion(configVersion);
            saveState(state);

            pruneLocalSnapshots(MAX_LOCAL_SNAPSHOTS);
            return snapshot;
        }
    }

    /**
     * Reads a snapshot and verifies its size and digest. A mismatch is
     * reported instead of uploading damaged data.
     */
    @NonNull
    public byte[] readSnapshot(Snapshot snapshot) throws NutstoreBackupException {
        synchronized (LOCK) {
            File file = getSnapshotFile(snapshot);
            if (!file.isFile()) {
                throw new NutstoreBackupException(
                        NutstoreBackupException.Reason.SNAPSHOT_MISSING,
                        "The snapshot file is missing");
            }

            try {
                byte[] bytes = new AtomicFile(file).readFully();
                if (bytes.length != snapshot.getSize()
                        || !snapshot.getSha256().equals(sha256(bytes))) {
                    throw new NutstoreBackupException(
                            NutstoreBackupException.Reason.SNAPSHOT_CORRUPTED,
                            "The snapshot content does not match its metadata");
                }
                return bytes;
            } catch (IOException e) {
                throw new NutstoreBackupException(
                        NutstoreBackupException.Reason.SNAPSHOT_MISSING, e);
            }
        }
    }

    public void deleteSnapshot(Snapshot snapshot) throws NutstoreBackupException {
        synchronized (LOCK) {
            deleteSnapshotFile(snapshot);
            State state = loadState();
            Snapshot current = state.getSnapshot();
            if (current != null && current.getId().equals(snapshot.getId())) {
                state.setSnapshot(null);
                saveState(state);
            }
        }
    }

    /**
     * Keeps at most {@code keep} snapshot files on disk. The snapshot that is
     * currently referenced by the state is never deleted.
     */
    public void pruneLocalSnapshots(int keep) {
        synchronized (LOCK) {
            File dir = getSnapshotDir();
            File[] files = dir.listFiles();
            if (files == null || files.length <= keep) {
                return;
            }

            List<File> sorted = new ArrayList<>(Arrays.asList(files));
            sorted.sort(Comparator.comparingLong(File::lastModified).reversed());

            String currentName = null;
            try {
                State state = loadState();
                if (state.getSnapshot() != null) {
                    currentName = getSnapshotFile(state.getSnapshot()).getName();
                }
            } catch (NutstoreBackupException ignored) {
                // Without a readable state no file may be deleted.
                return;
            }

            int remaining = sorted.size();
            for (File file : sorted) {
                if (remaining <= Math.max(keep, 1)) {
                    break;
                }
                if (currentName != null && currentName.equals(file.getName())) {
                    continue;
                }
                if (file.delete()) {
                    remaining--;
                }
            }
        }
    }

    /**
     * Removes the state file and every snapshot. Used by the panic trigger and
     * when vault encryption is turned off.
     */
    public void clearAll() throws NutstoreBackupException {
        synchronized (LOCK) {
            File stateFile = getStateFile();
            if (stateFile.exists() && !stateFile.delete()) {
                throw new NutstoreBackupException(
                        NutstoreBackupException.Reason.STORAGE_IO,
                        "Unable to delete the backup state file");
            }
            IOUtils.clearDirectory(getSnapshotDir(), true);
        }
    }

    @NonNull
    File getStateFile() {
        return new File(_dir, STATE_FILENAME);
    }

    @NonNull
    File getSnapshotFile(Snapshot snapshot) {
        return new File(getSnapshotDir(), snapshot.getId() + ".json");
    }

    @NonNull
    private File getSnapshotDir() {
        return new File(_dir, SNAPSHOTS_DIR);
    }

    private void resetForConfig(State state, int configVersion) {
        Snapshot snapshot = state.getSnapshot();
        if (snapshot != null) {
            deleteSnapshotFile(snapshot);
        }
        state.setSnapshot(null);
        state.setRequestVersion(0);
        state.setConfirmedVersion(0);
        state.setStatus(Status.NONE);
        state.setLastFailure(FailureKind.NONE);
        state.setLastFailureAt(0);
        state.setLastRetryAfterSeconds(0);
        state.setCleanupFailedAt(0);
        state.setConfigVersion(configVersion);
    }

    @Nullable
    private JSONObject readStateJson() throws NutstoreBackupException {
        File file = getStateFile();
        if (!file.exists()) {
            return null;
        }
        try {
            byte[] bytes = new AtomicFile(file).readFully();
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            throw new NutstoreBackupException(
                    NutstoreBackupException.Reason.STORAGE_CORRUPTED, e);
        }
    }

    private void saveState(State state) throws NutstoreBackupException {
        File file = getStateFile();
        File dir = file.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            throw new NutstoreBackupException(
                    NutstoreBackupException.Reason.STORAGE_IO,
                    "Unable to create the backup state directory");
        }

        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream outStream = null;
        try {
            outStream = atomicFile.startWrite();
            outStream.write(state.toJson().toString(4).getBytes(StandardCharsets.UTF_8));
            atomicFile.finishWrite(outStream);
        } catch (IOException | JSONException e) {
            if (outStream != null) {
                atomicFile.failWrite(outStream);
            }
            throw new NutstoreBackupException(
                    NutstoreBackupException.Reason.STORAGE_IO, e);
        }
    }

    private void writeSnapshotFile(Snapshot snapshot, byte[] bytes) throws NutstoreBackupException {
        File dir = getSnapshotDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new NutstoreBackupException(
                    NutstoreBackupException.Reason.STORAGE_IO,
                    "Unable to create the snapshot directory");
        }

        AtomicFile atomicFile = new AtomicFile(getSnapshotFile(snapshot));
        FileOutputStream outStream = null;
        try {
            outStream = atomicFile.startWrite();
            outStream.write(bytes);
            atomicFile.finishWrite(outStream);
        } catch (IOException e) {
            if (outStream != null) {
                atomicFile.failWrite(outStream);
            }
            throw new NutstoreBackupException(
                    NutstoreBackupException.Reason.STORAGE_IO, e);
        }
    }

    private void deleteSnapshotFile(Snapshot snapshot) {
        File file = getSnapshotFile(snapshot);
        if (file.exists()) {
            file.delete();
        }
    }

    private boolean isSnapshotUsable(@Nullable Snapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if (!SNAPSHOT_ID_PATTERN.matcher(snapshot.getId()).matches()
                || !SHA256_PATTERN.matcher(snapshot.getSha256()).matches()
                || !MANAGED_FILE_PATTERN.matcher(snapshot.getRemoteName()).matches()
                || snapshot.getSize() < 0
                || snapshot.getSize() > WebDavClient.MAX_UPLOAD_BYTES) {
            return false;
        }
        File file = getSnapshotFile(snapshot);
        return file.isFile() && file.length() == snapshot.getSize();
    }

    /**
     * Deletes AtomicFile leftovers from interrupted writes. A partially
     * written snapshot is never referenced by the state, because the state is
     * only updated after the snapshot file was finished.
     */
    private void cleanupIncompleteWrites() {
        File stateNew = new File(_dir, STATE_FILENAME + ".new");
        if (stateNew.exists()) {
            stateNew.delete();
        }

        File[] files = getSnapshotDir().listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".new")) {
                    file.delete();
                }
            }
        }
    }

    private static String buildRemoteName(long epochSeconds) {
        return "aegis-" + REMOTE_NAME_FORMAT.format(Instant.ofEpochSecond(epochSeconds))
                + "-" + randomHex(4) + ".json";
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        new SecureRandom().nextBytes(bytes);
        return Hex.encode(bytes);
    }

    static String sha256(byte[] data) throws NutstoreBackupException {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new NutstoreBackupException(
                    NutstoreBackupException.Reason.STORAGE_IO, e);
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String name) {
        return Enum.valueOf(type, name);
    }

    public enum Status {
        NONE,
        PENDING,
        UPLOADING,
        SUCCESS,
        FAILED
    }

    /**
     * Classified reason for the last failure, so that state files never
     * contain exception messages or response contents.
     */
    public enum FailureKind {
        NONE,
        NETWORK,
        TIMEOUT,
        AUTHENTICATION,
        PERMISSION,
        NOT_FOUND,
        CONFLICT,
        QUOTA,
        RATE_LIMITED,
        SERVER,
        CONFIGURATION,
        CREDENTIALS,
        STORAGE,
        SNAPSHOT,
        UNKNOWN
    }

    public static class State {
        private int _configVersion;
        private int _requestVersion;
        private int _confirmedVersion;
        private Status _status = Status.NONE;
        private long _lastSuccessAt;
        private FailureKind _lastFailure = FailureKind.NONE;
        private long _lastFailureAt;
        private long _lastRetryAfterSeconds;
        private long _cleanupFailedAt;
        private Snapshot _snapshot;

        public int getConfigVersion() {
            return _configVersion;
        }

        public void setConfigVersion(int configVersion) {
            _configVersion = configVersion;
        }

        public int getRequestVersion() {
            return _requestVersion;
        }

        public void setRequestVersion(int requestVersion) {
            _requestVersion = requestVersion;
        }

        public int getConfirmedVersion() {
            return _confirmedVersion;
        }

        public void setConfirmedVersion(int confirmedVersion) {
            _confirmedVersion = confirmedVersion;
        }

        @NonNull
        public Status getStatus() {
            return _status;
        }

        public void setStatus(Status status) {
            _status = status;
        }

        public long getLastSuccessAt() {
            return _lastSuccessAt;
        }

        public void setLastSuccessAt(long lastSuccessAt) {
            _lastSuccessAt = lastSuccessAt;
        }

        @NonNull
        public FailureKind getLastFailure() {
            return _lastFailure;
        }

        public void setLastFailure(FailureKind lastFailure) {
            _lastFailure = lastFailure;
        }

        public long getLastFailureAt() {
            return _lastFailureAt;
        }

        public void setLastFailureAt(long lastFailureAt) {
            _lastFailureAt = lastFailureAt;
        }

        /**
         * Retry-After value of the last failure, in seconds. While
         * {@code lastFailureAt + lastRetryAfterSeconds} lies in the future the
         * worker must not contact the server again.
         */
        public long getLastRetryAfterSeconds() {
            return _lastRetryAfterSeconds;
        }

        public void setLastRetryAfterSeconds(long lastRetryAfterSeconds) {
            _lastRetryAfterSeconds = lastRetryAfterSeconds;
        }

        public long getCleanupFailedAt() {
            return _cleanupFailedAt;
        }

        public void setCleanupFailedAt(long cleanupFailedAt) {
            _cleanupFailedAt = cleanupFailedAt;
        }

        @Nullable
        public Snapshot getSnapshot() {
            return _snapshot;
        }

        public void setSnapshot(@Nullable Snapshot snapshot) {
            _snapshot = snapshot;
        }

        public boolean isUploadPending() {
            return _requestVersion > _confirmedVersion;
        }

        /**
         * True when the current snapshot does not cover the latest request and
         * a new one must be generated before uploading.
         */
        public boolean isSnapshotOutdated() {
            return isUploadPending()
                    && (_snapshot == null || _snapshot.getRequestVersion() < _requestVersion);
        }

        private JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("version", STATE_VERSION);
            obj.put("configVersion", _configVersion);
            obj.put("requestVersion", _requestVersion);
            obj.put("confirmedVersion", _confirmedVersion);
            obj.put("status", _status.name());
            obj.put("lastSuccessAt", _lastSuccessAt);
            obj.put("lastFailure", _lastFailure.name());
            obj.put("lastFailureAt", _lastFailureAt);
            obj.put("lastRetryAfter", _lastRetryAfterSeconds);
            obj.put("cleanupFailedAt", _cleanupFailedAt);
            if (_snapshot != null) {
                obj.put("snapshot", _snapshot.toJson());
            }
            return obj;
        }
    }

    public static class Snapshot {
        private final String _id;
        private final String _remoteName;
        private final String _sha256;
        private final long _size;
        private final long _createdAt;
        private final int _configVersion;
        private final int _requestVersion;

        Snapshot(String id, String remoteName, String sha256, long size, long createdAt,
                 int configVersion, int requestVersion) {
            _id = id;
            _remoteName = remoteName;
            _sha256 = sha256;
            _size = size;
            _createdAt = createdAt;
            _configVersion = configVersion;
            _requestVersion = requestVersion;
        }

        @NonNull
        public String getId() {
            return _id;
        }

        /**
         * Remote file name inside the device directory. Reused verbatim on
         * retries so that an interrupted upload stays idempotent.
         */
        @NonNull
        public String getRemoteName() {
            return _remoteName;
        }

        @NonNull
        public String getSha256() {
            return _sha256;
        }

        public long getSize() {
            return _size;
        }

        public long getCreatedAt() {
            return _createdAt;
        }

        public int getConfigVersion() {
            return _configVersion;
        }

        public int getRequestVersion() {
            return _requestVersion;
        }

        private JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", _id);
            obj.put("remoteName", _remoteName);
            obj.put("sha256", _sha256);
            obj.put("size", _size);
            obj.put("createdAt", _createdAt);
            obj.put("configVersion", _configVersion);
            obj.put("requestVersion", _requestVersion);
            return obj;
        }

        private static Snapshot fromJson(JSONObject obj) throws JSONException {
            return new Snapshot(
                    obj.getString("id"),
                    obj.getString("remoteName"),
                    obj.getString("sha256"),
                    obj.getLong("size"),
                    obj.optLong("createdAt", 0),
                    obj.optInt("configVersion", 0),
                    obj.optInt("requestVersion", 0));
        }
    }
}
