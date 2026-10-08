package com.beemdevelopment.aegis.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.beemdevelopment.aegis.AegisTest;
import com.beemdevelopment.aegis.ui.tasks.PasswordSlotDecryptTask;
import com.beemdevelopment.aegis.vault.VaultFile;
import com.beemdevelopment.aegis.vault.VaultFileCredentials;
import com.beemdevelopment.aegis.vault.slots.PasswordSlot;

import org.junit.Test;

import java.util.List;

import dagger.hilt.android.testing.HiltAndroidTest;

/**
 * Opt-in live integration test against a real Nutstore account.
 *
 * The test is skipped unless the credentials are passed explicitly:
 *
 * <pre>
 * .\gradlew.bat :app:connectedDebugAndroidTest \
 *     "-Pandroid.testInstrumentationRunnerArguments.class=com.beemdevelopment.aegis.backup.NutstoreLiveIntegrationTest" \
 *     "-Pandroid.testInstrumentationRunnerArguments.nutstoreAccount=..." \
 *     "-Pandroid.testInstrumentationRunnerArguments.nutstorePassword=..."
 * </pre>
 *
 * It creates an encrypted vault, stores the credentials through the production
 * Keystore-backed credential store, runs one real upload and verifies that the
 * uploaded backup can be downloaded and decrypted with the vault password
 * again. Remote and local test artifacts are removed afterwards.
 */
@HiltAndroidTest
public class NutstoreLiveIntegrationTest extends AegisTest {
    private static final String ROOT_PATH = "Aegis-IntegrationTest";

    @Test
    public void testLiveUploadAndRestore() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String account = arguments.getString("nutstoreAccount");
        String password = arguments.getString("nutstorePassword");
        assumeTrue("Nutstore credentials were not provided", account != null && password != null);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Fresh encrypted vault so that a snapshot can be created.
        initEmptyEncryptedVault();

        NutstoreCredentialStore credentials = new NutstoreCredentialStore(context);
        NutstoreBackupStore store = new NutstoreBackupStore(context);
        try {
            credentials.clearAll();
            store.clearAll();

            NutstoreCredentialStore.Config config = credentials.save(account,
                    password.toCharArray(), ROOT_PATH, true, 2);

            store.requestBackup(config.getConfigVersion());
            NutstoreUploader.Result result = new NutstoreUploader(context).uploadOnce();
            assertEquals(NutstoreUploader.Outcome.SUCCESS, result.getOutcome());

            NutstoreBackupStore.State state = store.loadState();
            assertEquals(NutstoreBackupStore.Status.SUCCESS, state.getStatus());
            assertEquals(state.getRequestVersion(), state.getConfirmedVersion());
            assertNull(state.getSnapshot());

            // Verify the remote backup can be downloaded and decrypted again.
            char[] appPassword = password.toCharArray();
            try (WebDavClient client = new WebDavClient(account, appPassword, ROOT_PATH)) {
                String deviceDir = ROOT_PATH + "/" + config.getDeviceId();
                WebDavClient.ListResult listing = client.list(deviceDir);
                assertEquals(1, listing.getEntries().size());

                WebDavClient.RemoteEntry entry = listing.getEntries().get(0);
                byte[] bytes = client.download(entry.getPath(), WebDavClient.MAX_DOWNLOAD_BYTES);
                VaultFile vaultFile = VaultFile.fromBytes(bytes);
                assertTrue(vaultFile.isEncrypted());

                List<PasswordSlot> slots =
                        vaultFile.getHeader().getSlots().findAll(PasswordSlot.class);
                PasswordSlotDecryptTask.Result decrypted =
                        PasswordSlotDecryptTask.decrypt(slots, VAULT_PASSWORD.toCharArray());
                assertNotNull(decrypted);
                VaultFileCredentials creds = new VaultFileCredentials(
                        decrypted.getKey(), vaultFile.getHeader().getSlots());
                assertTrue(vaultFile.getContent(creds).has("entries"));

                // Remove the remote test artifact.
                client.delete(entry.getPath());
                client.delete(deviceDir);
            }
        } finally {
            store.clearAll();
            credentials.clearAll();
        }
    }
}
