package com.beemdevelopment.aegis.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.beemdevelopment.aegis.ui.tasks.PasswordSlotDecryptTask;
import com.beemdevelopment.aegis.vault.VaultFile;
import com.beemdevelopment.aegis.vault.VaultFileCredentials;
import com.beemdevelopment.aegis.vault.VaultRepository;
import com.beemdevelopment.aegis.vault.slots.PasswordSlot;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class NutstoreBackupStoreTest {
    private Context _context;
    private NutstoreBackupStore _store;

    @Before
    public void init() throws Exception {
        _context = ApplicationProvider.getApplicationContext();
        _store = new NutstoreBackupStore(_context);
        _store.clearAll();
        VaultRepository.deleteFile(_context);
    }

    @After
    public void tearDown() throws Exception {
        _store.clearAll();
        VaultRepository.deleteFile(_context);
    }

    @Test
    public void testCreateSnapshotAndRestore() throws Exception {
        createVault(true, true);

        int configVersion = 3;
        _store.requestBackup(configVersion);
        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(1, state.getRequestVersion());
        assertEquals(0, state.getConfirmedVersion());
        assertEquals(NutstoreBackupStore.Status.PENDING, state.getStatus());

        NutstoreBackupStore.Snapshot snapshot =
                _store.createSnapshot(configVersion, state.getRequestVersion());
        assertTrue(NutstoreBackupStore.MANAGED_FILE_PATTERN
                .matcher(snapshot.getRemoteName()).matches());
        assertEquals(configVersion, snapshot.getConfigVersion());
        assertEquals(1, snapshot.getRequestVersion());
        assertEquals(snapshot.getSize(), _store.getSnapshotFile(snapshot).length());

        // The snapshot is a standard, restorable Aegis export that can be
        // decrypted with the vault password like any other backup file.
        byte[] bytes = _store.readSnapshot(snapshot);
        VaultFile vaultFile = VaultFile.fromBytes(bytes);
        assertTrue(vaultFile.isEncrypted());
        List<PasswordSlot> slots = vaultFile.getHeader().getSlots().findAll(PasswordSlot.class);
        assertEquals(1, slots.size());

        PasswordSlotDecryptTask.Result result =
                PasswordSlotDecryptTask.decrypt(slots, NutstoreTestHelpers.VAULT_PASSWORD);
        assertNotNull(result);
        JSONObject content = vaultFile.getContent(
                new VaultFileCredentials(result.getKey(), vaultFile.getHeader().getSlots()));
        assertTrue(content.has("entries"));

        // No credential or password material may end up in the state file.
        String stateJson = new String(Files.readAllBytes(_store.getStateFile().toPath()),
                StandardCharsets.UTF_8);
        assertFalse(stateJson.contains(new String(NutstoreTestHelpers.VAULT_PASSWORD)));
        assertFalse(stateJson.contains("credentials"));

        // A second snapshot request for the same version reuses the file.
        NutstoreBackupStore.Snapshot again = _store.createSnapshot(configVersion, 1);
        assertEquals(snapshot.getId(), again.getId());
    }

    @Test
    public void testSnapshotRequiresEncryptedVault() throws Exception {
        createVault(false, false);
        _store.requestBackup(1);

        NutstoreBackupException e = assertThrows(NutstoreBackupException.class,
                () -> _store.createSnapshot(1, 1));
        assertEquals(NutstoreBackupException.Reason.VAULT_NOT_ENCRYPTED, e.getReason());
    }

    @Test
    public void testSnapshotRequiresPasswordSlot() throws Exception {
        createVault(true, false);
        _store.requestBackup(1);

        NutstoreBackupException e = assertThrows(NutstoreBackupException.class,
                () -> _store.createSnapshot(1, 1));
        assertEquals(NutstoreBackupException.Reason.VAULT_NO_PASSWORD_SLOT, e.getReason());
    }

    @Test
    public void testSnapshotRequiresVault() throws Exception {
        VaultRepository.deleteFile(_context);
        _store.requestBackup(1);

        NutstoreBackupException e = assertThrows(NutstoreBackupException.class,
                () -> _store.createSnapshot(1, 1));
        assertEquals(NutstoreBackupException.Reason.VAULT_MISSING, e.getReason());
    }

    @Test
    public void testRepeatedRequestsAreMerged() throws Exception {
        createVault(true, true);
        _store.requestBackup(1);
        _store.requestBackup(1);
        _store.requestBackup(1);

        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(3, state.getRequestVersion());
        assertTrue(state.isSnapshotOutdated());

        NutstoreBackupStore.Snapshot snapshot = _store.createSnapshot(1, 3);
        assertEquals(3, snapshot.getRequestVersion());
        assertFalse(_store.loadState().isSnapshotOutdated());
    }

    @Test
    public void testConfirmUpload() throws Exception {
        createVault(true, true);
        _store.requestBackup(1);
        NutstoreBackupStore.Snapshot snapshot = _store.createSnapshot(1, 1);

        _store.markUploading();
        assertEquals(NutstoreBackupStore.Status.UPLOADING, _store.loadState().getStatus());

        _store.confirmUpload(1, 1000L);
        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(1, state.getConfirmedVersion());
        assertEquals(NutstoreBackupStore.Status.SUCCESS, state.getStatus());
        assertEquals(1000L, state.getLastSuccessAt());
        assertEquals(NutstoreBackupStore.FailureKind.NONE, state.getLastFailure());
        assertNull(state.getSnapshot());
        assertFalse(_store.getSnapshotFile(snapshot).exists());
        assertFalse(state.isUploadPending());
        assertFalse(state.isSnapshotOutdated());
    }

    @Test
    public void testConfirmWithNewerRequestStaysPending() throws Exception {
        createVault(true, true);
        _store.requestBackup(1);
        _store.createSnapshot(1, 1);

        // A new save arrives while the first snapshot is being uploaded.
        _store.requestBackup(1);
        _store.confirmUpload(1, 2000L);

        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(1, state.getConfirmedVersion());
        assertEquals(2, state.getRequestVersion());
        assertEquals(NutstoreBackupStore.Status.PENDING, state.getStatus());
        assertNull(state.getSnapshot());
        assertTrue(state.isSnapshotOutdated());
        assertEquals(2000L, state.getLastSuccessAt());
    }

    @Test
    public void testConfigChangeInvalidatesQueue() throws Exception {
        createVault(true, true);
        _store.requestBackup(1);
        NutstoreBackupStore.Snapshot snapshot = _store.createSnapshot(1, 1);

        _store.requestBackup(2);
        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(2, state.getConfigVersion());
        assertEquals(1, state.getRequestVersion());
        assertEquals(0, state.getConfirmedVersion());
        assertNull(state.getSnapshot());
        assertFalse(_store.getSnapshotFile(snapshot).exists());
    }

    @Test
    public void testCorruptedSnapshotIsDetected() throws Exception {
        createVault(true, true);
        _store.requestBackup(1);
        NutstoreBackupStore.Snapshot snapshot = _store.createSnapshot(1, 1);

        File file = _store.getSnapshotFile(snapshot);
        byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file.toPath(), bytes);

        NutstoreBackupException e = assertThrows(NutstoreBackupException.class,
                () -> _store.readSnapshot(snapshot));
        assertEquals(NutstoreBackupException.Reason.SNAPSHOT_CORRUPTED, e.getReason());
    }

    @Test
    public void testMissingSnapshotFileIsDropped() throws Exception {
        createVault(true, true);
        _store.requestBackup(1);
        NutstoreBackupStore.Snapshot snapshot = _store.createSnapshot(1, 1);
        assertTrue(_store.getSnapshotFile(snapshot).delete());

        NutstoreBackupStore.State state = _store.loadState();
        assertNull(state.getSnapshot());
        assertEquals(NutstoreBackupStore.Status.PENDING, state.getStatus());
        assertTrue(state.isSnapshotOutdated());
    }

    @Test
    public void testCorruptedStateFileIsDetected() throws Exception {
        createVault(true, true);
        _store.requestBackup(1);
        Files.write(_store.getStateFile().toPath(), "not json".getBytes(StandardCharsets.UTF_8));

        NutstoreBackupException e = assertThrows(NutstoreBackupException.class,
                () -> _store.loadState());
        assertEquals(NutstoreBackupException.Reason.STORAGE_CORRUPTED, e.getReason());
    }

    @Test
    public void testPruneLocalSnapshots() throws Exception {
        createVault(true, true);
        for (int i = 1; i <= 4; i++) {
            _store.requestBackup(1);
            _store.createSnapshot(1, i);
        }

        _store.pruneLocalSnapshots(NutstoreBackupStore.MAX_LOCAL_SNAPSHOTS);
        File dir = new File(new File(_context.getNoBackupFilesDir(), "nutstore-backup"), "snapshots");
        File[] files = dir.listFiles();
        assertNotNull(files);
        assertEquals(NutstoreBackupStore.MAX_LOCAL_SNAPSHOTS, files.length);
        assertTrue(_store.getSnapshotFile(_store.loadState().getSnapshot()).isFile());
    }

    @Test
    public void testFailureRecording() throws Exception {
        createVault(true, true);
        _store.requestBackup(1);

        _store.markFailure(NutstoreBackupStore.FailureKind.AUTHENTICATION, 500L);
        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(NutstoreBackupStore.Status.FAILED, state.getStatus());
        assertEquals(NutstoreBackupStore.FailureKind.AUTHENTICATION, state.getLastFailure());
        assertEquals(500L, state.getLastFailureAt());

        _store.markCleanupFailure(600L);
        assertEquals(600L, _store.loadState().getCleanupFailedAt());
    }

    @Test
    public void testClearAll() throws Exception {
        createVault(true, true);
        _store.requestBackup(1);
        _store.createSnapshot(1, 1);
        assertTrue(_store.getStateFile().exists());

        _store.clearAll();
        assertFalse(_store.getStateFile().exists());
        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(0, state.getRequestVersion());
        assertNull(state.getSnapshot());
    }

    private void createVault(boolean encrypted, boolean withPasswordSlot) throws Exception {
        NutstoreTestHelpers.createVault(_context, NutstoreTestHelpers.VAULT_PASSWORD,
                encrypted, withPasswordSlot);
    }
}
