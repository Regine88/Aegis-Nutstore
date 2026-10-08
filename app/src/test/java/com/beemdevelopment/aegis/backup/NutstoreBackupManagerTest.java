package com.beemdevelopment.aegis.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.beemdevelopment.aegis.vault.VaultRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class NutstoreBackupManagerTest {
    private Context _context;
    private NutstoreCredentialStore _credentials;
    private NutstoreBackupStore _store;
    private NutstoreBackupManager _manager;
    private NutstoreCredentialStore.Config _config;

    @Before
    public void init() throws Exception {
        _context = ApplicationProvider.getApplicationContext();
        WorkManagerTestInitHelper.initializeTestWorkManager(_context);
        _credentials = new NutstoreCredentialStore(_context,
                new NutstoreTestHelpers.MemoryKeySource());
        _store = new NutstoreBackupStore(_context);
        _manager = new NutstoreBackupManager(_context, _credentials, _store);
        _store.clearAll();
        _credentials.clearAll();
        VaultRepository.deleteFile(_context);
    }

    @After
    public void tearDown() throws Exception {
        _store.clearAll();
        _credentials.clearAll();
        VaultRepository.deleteFile(_context);
    }

    @Test
    public void testRequestBackupIfEnabledSkipsDisabled() throws Exception {
        _credentials.save("user@example.com", NutstoreTestHelpers.APP_PASSWORD,
                "Aegis", false, 5);
        _manager.requestBackupIfEnabled();

        assertEquals(0, _store.loadState().getRequestVersion());
        assertTrue(getWorkInfos().isEmpty());
    }

    @Test
    public void testRequestBackupIfEnabledEnqueuesWork() throws Exception {
        createVault();
        _config = _credentials.save("user@example.com", NutstoreTestHelpers.APP_PASSWORD,
                "Aegis", true, 5);
        _manager.requestBackupIfEnabled();

        assertEquals(1, _store.loadState().getRequestVersion());
        List<WorkInfo> infos = getWorkInfos();
        assertEquals(1, infos.size());
        assertEquals(WorkInfo.State.ENQUEUED, infos.get(0).getState());
    }

    @Test
    public void testResumePendingWorkEnqueuesAgain() throws Exception {
        createVault();
        _config = _credentials.save("user@example.com", NutstoreTestHelpers.APP_PASSWORD,
                "Aegis", true, 5);
        _store.requestBackup(_config.getConfigVersion());

        _manager.resumePendingWork();

        assertEquals(1, getWorkInfos().size());
        assertEquals(1, _store.loadState().getRequestVersion());
    }

    @Test
    public void testStopAndClearPending() throws Exception {
        createVault();
        _config = _credentials.save("user@example.com", NutstoreTestHelpers.APP_PASSWORD,
                "Aegis", true, 5);
        _store.requestBackup(_config.getConfigVersion());
        NutstoreBackupStore.Snapshot snapshot =
                _store.createSnapshot(_config.getConfigVersion(), 1);

        _manager.stopAndClearPending();

        NutstoreBackupStore.State state = _store.loadState();
        assertFalse(state.isUploadPending());
        assertNull(state.getSnapshot());
        assertFalse(_store.getSnapshotFile(snapshot).exists());
        // The credentials are kept for manual restores.
        assertTrue(_credentials.isConfigured());
    }

    @Test
    public void testWipeRemovesLocalState() throws Exception {
        createVault();
        _config = _credentials.save("user@example.com", NutstoreTestHelpers.APP_PASSWORD,
                "Aegis", true, 5);
        _store.requestBackup(_config.getConfigVersion());
        _store.createSnapshot(_config.getConfigVersion(), 1);

        NutstoreBackupManager.wipe(_context);

        assertFalse(_store.getStateFile().exists());
        assertFalse(_credentials.getFile().exists());
        assertEquals(0, _store.loadState().getRequestVersion());
    }

    private void createVault() throws Exception {
        NutstoreTestHelpers.createVault(_context, NutstoreTestHelpers.VAULT_PASSWORD, true, true);
    }

    private List<WorkInfo> getWorkInfos() throws Exception {
        return WorkManager.getInstance(_context)
                .getWorkInfosForUniqueWork(NutstoreBackupManager.UNIQUE_WORK_NAME)
                .get(5, TimeUnit.SECONDS);
    }
}
