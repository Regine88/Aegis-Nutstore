package com.beemdevelopment.aegis.backup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.beemdevelopment.aegis.encoding.Base64;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class NutstoreCredentialStoreTest {
    private Context _context;
    private NutstoreTestHelpers.MemoryKeySource _keySource;
    private NutstoreCredentialStore _store;

    @Before
    public void init() throws Exception {
        _context = ApplicationProvider.getApplicationContext();
        _keySource = new NutstoreTestHelpers.MemoryKeySource();
        _store = new NutstoreCredentialStore(_context, _keySource);
        _store.clearAll();
    }

    @Test
    public void testDefaults() throws Exception {
        NutstoreCredentialStore.Config config = _store.loadConfig();
        assertNull(config.getAccount());
        assertEquals(NutstoreCredentialStore.DEFAULT_ROOT_PATH, config.getRootPath());
        assertNull(config.getDeviceId());
        assertFalse(config.isAutoBackupEnabled());
        assertEquals(NutstoreCredentialStore.DEFAULT_RETENTION, config.getRetention());
        assertEquals(0, config.getConfigVersion());
        assertFalse(config.isPasswordStored());
        assertFalse(_store.isConfigured());
    }

    @Test
    public void testSaveAndLoad() throws Exception {
        char[] password = "app-password".toCharArray();
        NutstoreCredentialStore.Config config = _store.save(
                "user@example.com", password, "Backups/Phone", true, 7);

        assertEquals("user@example.com", config.getAccount());
        assertEquals("Backups/Phone", config.getRootPath());
        assertNotNull(config.getDeviceId());
        assertTrue(config.isAutoBackupEnabled());
        assertEquals(7, config.getRetention());
        assertEquals(1, config.getConfigVersion());
        assertTrue(config.isPasswordStored());
        assertTrue(_store.isConfigured());
        assertArrayEquals(password, _store.readPassword());

        // A second save without a password keeps the stored password, keeps the
        // device id and bumps the configuration version.
        NutstoreCredentialStore.Config updated = _store.save(
                "user@example.com", null, "Backups/Phone", false, 7);
        assertEquals(2, updated.getConfigVersion());
        assertEquals(config.getDeviceId(), updated.getDeviceId());
        assertFalse(updated.isAutoBackupEnabled());
        assertArrayEquals(password, _store.readPassword());

        // Reloading from disk must produce the same values.
        NutstoreCredentialStore reloaded = new NutstoreCredentialStore(_context, _keySource);
        assertEquals(2, reloaded.loadConfig().getConfigVersion());
        assertArrayEquals(password, reloaded.readPassword());
    }

    @Test
    public void testRootPathNormalization() {
        assertEquals("Aegis", NutstoreCredentialStore.normalizeRootPath(null));
        assertEquals("Aegis", NutstoreCredentialStore.normalizeRootPath(""));
        assertEquals("Aegis", NutstoreCredentialStore.normalizeRootPath("   "));
        assertEquals("Aegis", NutstoreCredentialStore.normalizeRootPath("/"));
        assertEquals("Backups/Phone", NutstoreCredentialStore.normalizeRootPath("/Backups/Phone/"));
        assertEquals("a b/c", NutstoreCredentialStore.normalizeRootPath(" a b / c "));

        assertThrows(IllegalArgumentException.class,
                () -> NutstoreCredentialStore.normalizeRootPath("backups/../secret"));
        assertThrows(IllegalArgumentException.class,
                () -> NutstoreCredentialStore.normalizeRootPath("backups/./phone"));
        assertThrows(IllegalArgumentException.class,
                () -> NutstoreCredentialStore.normalizeRootPath("backups//phone"));
        assertThrows(IllegalArgumentException.class,
                () -> NutstoreCredentialStore.normalizeRootPath("https://example.com/dav"));
        assertThrows(IllegalArgumentException.class,
                () -> NutstoreCredentialStore.normalizeRootPath("backups\\phone"));
        assertThrows(IllegalArgumentException.class,
                () -> NutstoreCredentialStore.normalizeRootPath("backups?x=1"));
        assertThrows(IllegalArgumentException.class,
                () -> NutstoreCredentialStore.normalizeRootPath("backups#fragment"));
        assertThrows(IllegalArgumentException.class,
                () -> NutstoreCredentialStore.normalizeRootPath("backups\u0007bell"));
    }

    @Test
    public void testRetentionBounds() throws Exception {
        char[] password = "app-password".toCharArray();
        NutstoreCredentialsException low = assertThrows(NutstoreCredentialsException.class,
                () -> _store.save("user@example.com", password, "Aegis", false, 0));
        assertEquals(NutstoreCredentialsException.Reason.INVALID_RETENTION, low.getReason());

        NutstoreCredentialsException high = assertThrows(NutstoreCredentialsException.class,
                () -> _store.save("user@example.com", password, "Aegis", false, 101));
        assertEquals(NutstoreCredentialsException.Reason.INVALID_RETENTION, high.getReason());

        assertEquals(NutstoreCredentialStore.MIN_RETENTION,
                _store.save("user@example.com", password, "Aegis", false, 1).getRetention());
        assertEquals(NutstoreCredentialStore.MAX_RETENTION,
                _store.save("user@example.com", null, "Aegis", false, 100).getRetention());
    }

    @Test
    public void testValidateAccountAndPassword() throws Exception {
        NutstoreCredentialsException account = assertThrows(NutstoreCredentialsException.class,
                () -> _store.save("user:name", "app-password".toCharArray(), "Aegis", false, 5));
        assertEquals(NutstoreCredentialsException.Reason.INVALID_ACCOUNT, account.getReason());

        NutstoreCredentialsException password = assertThrows(NutstoreCredentialsException.class,
                () -> _store.save("user@example.com", new char[0], "Aegis", false, 5));
        assertEquals(NutstoreCredentialsException.Reason.INVALID_PASSWORD, password.getReason());
    }

    @Test
    public void testCorruptedFile() throws Exception {
        _store.save("user@example.com", "app-password".toCharArray(), "Aegis", true, 5);
        Files.write(_store.getFile().toPath(), "not json".getBytes(StandardCharsets.UTF_8));

        NutstoreCredentialsException e = assertThrows(NutstoreCredentialsException.class,
                () -> _store.loadConfig());
        assertEquals(NutstoreCredentialsException.Reason.STORAGE_CORRUPTED, e.getReason());
        assertFalse(_store.isConfigured());
    }

    @Test
    public void testTamperedCiphertext() throws Exception {
        _store.save("user@example.com", "app-password".toCharArray(), "Aegis", true, 5);

        JSONObject obj = new JSONObject(new String(
                Files.readAllBytes(_store.getFile().toPath()), StandardCharsets.UTF_8));
        JSONObject credentials = obj.getJSONObject("credentials");
        byte[] data = Base64.decode(credentials.getString("data"));
        data[0] ^= 0x01;
        credentials.put("data", Base64.encode(data));
        Files.write(_store.getFile().toPath(),
                obj.toString(4).getBytes(StandardCharsets.UTF_8));

        NutstoreCredentialsException e = assertThrows(NutstoreCredentialsException.class,
                () -> _store.readPassword());
        assertEquals(NutstoreCredentialsException.Reason.DECRYPT_FAILED, e.getReason());
    }

    @Test
    public void testKeyMissing() throws Exception {
        _store.save("user@example.com", "app-password".toCharArray(), "Aegis", true, 5);

        // Simulate a restore to a new device: the file is present, the Keystore
        // key is not. The password must not be silently replaced.
        NutstoreCredentialStore other = new NutstoreCredentialStore(_context,
                new NutstoreTestHelpers.MemoryKeySource(false));
        assertFalse(other.isConfigured());
        assertTrue(other.loadConfig().isPasswordStored());
        NutstoreCredentialsException e = assertThrows(NutstoreCredentialsException.class,
                other::readPassword);
        assertEquals(NutstoreCredentialsException.Reason.KEY_MISSING, e.getReason());

        // Saving a new password creates a new key and leaves the file usable.
        other.save("user@example.com", "new-password".toCharArray(), "Aegis", true, 5);
        assertTrue(other.isConfigured());
        assertArrayEquals("new-password".toCharArray(), other.readPassword());
    }

    @Test
    public void testMissingCredentials() throws Exception {
        _store.save("user@example.com", null, "Aegis", false, 5);
        NutstoreCredentialsException e = assertThrows(NutstoreCredentialsException.class,
                () -> _store.readPassword());
        assertEquals(NutstoreCredentialsException.Reason.CREDENTIALS_MISSING, e.getReason());
    }

    @Test
    public void testClearCredentials() throws Exception {
        _store.save("user@example.com", "app-password".toCharArray(), "Aegis", true, 5);
        _store.clearCredentials();

        NutstoreCredentialStore.Config config = _store.loadConfig();
        assertEquals("user@example.com", config.getAccount());
        assertFalse(config.isPasswordStored());
        assertFalse(config.isAutoBackupEnabled());
        assertEquals(2, config.getConfigVersion());
        assertFalse(_keySource.hasKey());
        assertFalse(_store.isConfigured());
    }

    @Test
    public void testClearAll() throws Exception {
        _store.save("user@example.com", "app-password".toCharArray(), "Aegis", true, 5);
        File file = _store.getFile();
        assertTrue(file.exists());

        _store.clearAll();
        assertFalse(file.exists());
        assertFalse(_keySource.hasKey());
        assertEquals(0, _store.loadConfig().getConfigVersion());
    }

}
