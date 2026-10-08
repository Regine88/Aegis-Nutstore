package com.beemdevelopment.aegis.backup;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.AtomicFile;

import com.beemdevelopment.aegis.crypto.CryptParameters;
import com.beemdevelopment.aegis.crypto.CryptResult;
import com.beemdevelopment.aegis.crypto.CryptoUtils;
import com.beemdevelopment.aegis.encoding.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.util.Arrays;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Persists the Nutstore (Jianguoyun) WebDAV configuration and the encrypted
 * application password.
 *
 * The ciphertext is kept in a dedicated file inside {@code noBackupFilesDir}, so it
 * is not part of the regular SharedPreferences or of the Android system backup. The
 * AES key lives in a dedicated Android Keystore alias that does not require user
 * authentication; background workers can therefore read the password while the
 * vault itself is locked. The key of {@code KeyStoreHandle} is not reused because
 * it is bound to user authentication and because it is deleted whenever vault
 * encryption is turned off.
 */
public class NutstoreCredentialStore {
    public static final String SERVER_URL = "https://dav.jianguoyun.com/dav/";
    public static final String DEFAULT_ROOT_PATH = "Aegis";
    public static final int DEFAULT_RETENTION = 5;
    public static final int MIN_RETENTION = 1;
    public static final int MAX_RETENTION = 100;

    static final String KEYSTORE_ALIAS = "aegis_nutstore_credentials";

    static final String DIR_NAME = "nutstore-backup";
    private static final String FILENAME = "credentials.json";
    private static final int FILE_VERSION = 1;
    private static final int MAX_ACCOUNT_LENGTH = 320;
    private static final int MAX_PASSWORD_LENGTH = 512;
    private static final int MAX_PATH_LENGTH = 255;
    private static final int DEVICE_ID_BYTES = 8;

    private final Context _context;
    private final KeySource _keySource;

    public NutstoreCredentialStore(Context context) {
        this(context, new AndroidKeyStoreSource());
    }

    /**
     * Test seam: allows unit tests to replace the Android Keystore with an
     * in-memory key, because the real Keystore is not available off-device.
     */
    NutstoreCredentialStore(Context context, KeySource keySource) {
        _context = context;
        _keySource = keySource;
    }

    /**
     * Loads the persisted configuration. Returns defaults if nothing has been
     * stored yet, and throws if an existing file cannot be parsed.
     */
    @NonNull
    public Config loadConfig() throws NutstoreCredentialsException {
        JSONObject obj = readFile();
        if (obj == null) {
            return new Config(null, DEFAULT_ROOT_PATH, null, false, DEFAULT_RETENTION, 0, false);
        }

        try {
            if (obj.optInt("version", 0) > FILE_VERSION) {
                throw new NutstoreCredentialsException(
                        NutstoreCredentialsException.Reason.UNSUPPORTED_VERSION,
                        "The stored Nutstore configuration uses an unsupported version");
            }

            String rootPath;
            try {
                rootPath = normalizeRootPath(optString(obj, "rootPath"));
            } catch (IllegalArgumentException e) {
                throw new NutstoreCredentialsException(
                        NutstoreCredentialsException.Reason.STORAGE_CORRUPTED, e);
            }

            int retention = obj.optInt("retention", DEFAULT_RETENTION);
            if (retention < MIN_RETENTION || retention > MAX_RETENTION) {
                throw new NutstoreCredentialsException(
                        NutstoreCredentialsException.Reason.STORAGE_CORRUPTED,
                        "The stored retention count is out of range");
            }

            JSONObject credentials = obj.optJSONObject("credentials");
            return new Config(
                    optString(obj, "account"),
                    rootPath,
                    optString(obj, "deviceId"),
                    obj.optBoolean("autoBackup", false),
                    retention,
                    obj.optInt("configVersion", 0),
                    credentials != null);
        } catch (JSONException e) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.STORAGE_CORRUPTED, e);
        }
    }

    /**
     * Reports whether a full, usable configuration is stored: an account, a
     * password and a Keystore key that can actually decrypt that password.
     */
    public boolean isConfigured() {
        try {
            Config config = loadConfig();
            return config.getAccount() != null
                    && config.getRootPath() != null
                    && config.isPasswordStored()
                    && _keySource.hasKey();
        } catch (NutstoreCredentialsException e) {
            return false;
        }
    }

    /**
     * Decrypts and returns the stored application password. The caller is
     * responsible for clearing the returned array once it is no longer needed.
     */
    @NonNull
    public char[] readPassword() throws NutstoreCredentialsException {
        JSONObject obj = readFile();
        JSONObject credentials = obj == null ? null : obj.optJSONObject("credentials");
        if (credentials == null) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.CREDENTIALS_MISSING,
                    "No Nutstore application password has been stored");
        }

        SecretKey key = _keySource.getKey();
        if (key == null) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.KEY_MISSING,
                    "The Nutstore credentials key is missing");
        }

        return decryptPassword(credentials, key);
    }

    /**
     * Stores the given configuration. A null account clears the account field;
     * a null password keeps the currently stored password. Every change bumps
     * the configuration version so that pending uploads can detect that they
     * were created for an older account, directory or password.
     */
    @NonNull
    public Config save(@Nullable String account, @Nullable char[] password, String rootPath,
                       boolean autoBackupEnabled, int retention)
            throws NutstoreCredentialsException {
        if (retention < MIN_RETENTION || retention > MAX_RETENTION) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.INVALID_RETENTION,
                    String.format("The retention count must be between %d and %d", MIN_RETENTION, MAX_RETENTION));
        }

        String normalizedPath;
        try {
            normalizedPath = normalizeRootPath(rootPath);
        } catch (IllegalArgumentException e) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.INVALID_PATH, e);
        }

        String trimmedAccount = account == null ? null : account.trim();
        if (trimmedAccount != null && trimmedAccount.isEmpty()) {
            trimmedAccount = null;
        }
        validateAccount(trimmedAccount);

        try {
            JSONObject obj = readFile();
            if (obj == null) {
                obj = new JSONObject();
            }

            JSONObject credentials = obj.optJSONObject("credentials");
            if (password != null) {
                validatePassword(password);
                credentials = encryptPassword(password, _keySource.getOrCreateKey());
            }

            String deviceId = optString(obj, "deviceId");
            if (deviceId == null) {
                deviceId = generateDeviceId();
            }

            obj.put("version", FILE_VERSION);
            obj.put("configVersion", obj.optInt("configVersion", 0) + 1);
            if (trimmedAccount == null) {
                obj.remove("account");
            } else {
                obj.put("account", trimmedAccount);
            }
            obj.put("rootPath", normalizedPath);
            obj.put("deviceId", deviceId);
            obj.put("autoBackup", autoBackupEnabled);
            obj.put("retention", retention);
            if (credentials == null) {
                obj.remove("credentials");
            } else {
                obj.put("credentials", credentials);
            }

            writeFile(obj);
            return loadConfig();
        } catch (JSONException e) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.STORAGE_IO, e);
        }
    }

    /**
     * Revokes the stored authorization: removes the encrypted password and the
     * Keystore key, disables automatic backups and bumps the configuration
     * version. The account and root directory are kept for convenience.
     */
    public void clearCredentials() throws NutstoreCredentialsException {
        JSONObject obj = readFile();
        try {
            if (obj == null) {
                obj = new JSONObject();
            }
            obj.remove("credentials");
            obj.put("autoBackup", false);
            obj.put("configVersion", obj.optInt("configVersion", 0) + 1);
            obj.put("version", FILE_VERSION);
            writeFile(obj);
        } catch (JSONException e) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.STORAGE_IO, e);
        }

        _keySource.deleteKey();
    }

    /**
     * Removes every trace of the Nutstore configuration, including the Keystore
     * key. Used by the panic trigger and when vault encryption is turned off.
     */
    public void clearAll() throws NutstoreCredentialsException {
        File file = getFile();
        if (file.exists() && !file.delete()) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.STORAGE_IO,
                    "Unable to delete the Nutstore credentials file");
        }
        _keySource.deleteKey();
    }

    @NonNull
    File getFile() {
        return new File(new File(_context.getNoBackupFilesDir(), DIR_NAME), FILENAME);
    }

    @Nullable
    private JSONObject readFile() throws NutstoreCredentialsException {
        File file = getFile();
        if (!file.exists()) {
            return null;
        }

        try {
            byte[] bytes = new AtomicFile(file).readFully();
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.STORAGE_CORRUPTED, e);
        }
    }

    private void writeFile(JSONObject obj) throws NutstoreCredentialsException {
        File file = getFile();
        File dir = file.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.STORAGE_IO,
                    "Unable to create the Nutstore credentials directory");
        }

        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream outStream = null;
        try {
            outStream = atomicFile.startWrite();
            outStream.write(obj.toString(4).getBytes(StandardCharsets.UTF_8));
            atomicFile.finishWrite(outStream);
        } catch (IOException | JSONException e) {
            if (outStream != null) {
                atomicFile.failWrite(outStream);
            }
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.STORAGE_IO, e);
        }
    }

    /**
     * Normalizes a user-supplied remote root directory. Accepts relative paths
     * only; rejects parent traversal, URL-like input and characters that would
     * change the meaning of a WebDAV path.
     */
    @NonNull
    public static String normalizeRootPath(@Nullable String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) {
            return DEFAULT_ROOT_PATH;
        }

        if (value.contains("://") || value.startsWith("//")) {
            throw new IllegalArgumentException("The remote directory must be a relative path");
        }

        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        value = value.substring(start, end);
        if (value.isEmpty()) {
            return DEFAULT_ROOT_PATH;
        }

        if (value.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("The remote directory is too long");
        }

        StringBuilder normalized = new StringBuilder();
        for (String rawSegment : value.split("/", -1)) {
            String segment = rawSegment.trim();
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("The remote directory contains an empty segment");
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("The remote directory must not contain relative segments");
            }
            for (char c : segment.toCharArray()) {
                if (c < 0x20 || c == 0x7f || c == '\\' || c == ':' || c == '?' || c == '#') {
                    throw new IllegalArgumentException("The remote directory contains an invalid character");
                }
            }
            if (normalized.length() > 0) {
                normalized.append('/');
            }
            normalized.append(segment);
        }

        return normalized.toString();
    }

    static JSONObject encryptPassword(char[] password, SecretKey key)
            throws NutstoreCredentialsException {
        byte[] bytes = CryptoUtils.toBytes(password);
        try {
            CryptResult result = CryptoUtils.encrypt(bytes, CryptoUtils.createEncryptCipher(key));
            JSONObject obj = result.getParams().toJson();
            obj.put("data", Base64.encode(result.getData()));
            obj.put("version", FILE_VERSION);
            return obj;
        } catch (GeneralSecurityException | JSONException e) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.KEY_FAILURE, e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static char[] decryptPassword(JSONObject obj, SecretKey key)
            throws NutstoreCredentialsException {
        byte[] plain = null;
        try {
            byte[] data = Base64.decode(obj.getString("data"));
            CryptParameters params = CryptParameters.fromJson(obj);
            CryptResult result = CryptoUtils.decrypt(
                    data, CryptoUtils.createDecryptCipher(key, params.getNonce()), params);
            plain = result.getData();
            return new String(plain, StandardCharsets.UTF_8).toCharArray();
        } catch (JSONException | GeneralSecurityException | IOException e) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.DECRYPT_FAILED, e);
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    private static void validateAccount(@Nullable String account)
            throws NutstoreCredentialsException {
        if (account == null) {
            return;
        }
        if (account.length() > MAX_ACCOUNT_LENGTH) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.INVALID_ACCOUNT,
                    "The account name is too long");
        }
        for (char c : account.toCharArray()) {
            if (c < 0x20 || c == 0x7f || c == ':') {
                throw new NutstoreCredentialsException(
                        NutstoreCredentialsException.Reason.INVALID_ACCOUNT,
                        "The account name contains an invalid character");
            }
        }
    }

    private static void validatePassword(char[] password)
            throws NutstoreCredentialsException {
        if (password.length == 0 || password.length > MAX_PASSWORD_LENGTH) {
            throw new NutstoreCredentialsException(
                    NutstoreCredentialsException.Reason.INVALID_PASSWORD,
                    "The application password must not be empty and must not be too long");
        }
    }

    private static String generateDeviceId() {
        byte[] bytes = new byte[DEVICE_ID_BYTES];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    @Nullable
    private static String optString(JSONObject obj, String name) throws JSONException {
        if (!obj.has(name) || obj.isNull(name)) {
            return null;
        }
        String value = obj.getString(name).trim();
        return value.isEmpty() ? null : value;
    }

    public static class Config {
        private final String _account;
        private final String _rootPath;
        private final String _deviceId;
        private final boolean _autoBackupEnabled;
        private final int _retention;
        private final int _configVersion;
        private final boolean _passwordStored;

        Config(@Nullable String account, @NonNull String rootPath, @Nullable String deviceId,
               boolean autoBackupEnabled, int retention, int configVersion, boolean passwordStored) {
            _account = account;
            _rootPath = rootPath;
            _deviceId = deviceId;
            _autoBackupEnabled = autoBackupEnabled;
            _retention = retention;
            _configVersion = configVersion;
            _passwordStored = passwordStored;
        }

        @Nullable
        public String getAccount() {
            return _account;
        }

        @NonNull
        public String getRootPath() {
            return _rootPath;
        }

        @Nullable
        public String getDeviceId() {
            return _deviceId;
        }

        public boolean isAutoBackupEnabled() {
            return _autoBackupEnabled;
        }

        public int getRetention() {
            return _retention;
        }

        /**
         * Monotonically increasing revision of the configuration. Pending
         * uploads must not confirm or delete anything when it has changed.
         */
        public int getConfigVersion() {
            return _configVersion;
        }

        public boolean isPasswordStored() {
            return _passwordStored;
        }
    }

    /**
     * Abstraction over the Android Keystore so that unit tests can provide an
     * in-memory AES key instead of the device key store.
     */
    interface KeySource {
        @Nullable
        SecretKey getKey() throws NutstoreCredentialsException;

        @NonNull
        SecretKey getOrCreateKey() throws NutstoreCredentialsException;

        boolean hasKey();

        void deleteKey() throws NutstoreCredentialsException;
    }

    private static class AndroidKeyStoreSource implements KeySource {
        private KeyStore getKeyStore() throws NutstoreCredentialsException {
            try {
                KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                return keyStore;
            } catch (GeneralSecurityException | IOException e) {
                throw new NutstoreCredentialsException(
                        NutstoreCredentialsException.Reason.KEY_FAILURE, e);
            }
        }

        @Nullable
        @Override
        public SecretKey getKey() throws NutstoreCredentialsException {
            try {
                KeyStore keyStore = getKeyStore();
                if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                    return null;
                }
                return (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
            } catch (UnrecoverableKeyException e) {
                return null;
            } catch (GeneralSecurityException e) {
                throw new NutstoreCredentialsException(
                        NutstoreCredentialsException.Reason.KEY_FAILURE, e);
            }
        }

        @NonNull
        @Override
        public SecretKey getOrCreateKey() throws NutstoreCredentialsException {
            SecretKey key = getKey();
            if (key != null) {
                return key;
            }

            try {
                KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(CryptoUtils.CRYPTO_AEAD_KEY_SIZE * 8)
                        .setRandomizedEncryptionRequired(true)
                        .setUserAuthenticationRequired(false)
                        .build());
                return generator.generateKey();
            } catch (GeneralSecurityException e) {
                throw new NutstoreCredentialsException(
                        NutstoreCredentialsException.Reason.KEY_FAILURE, e);
            }
        }

        @Override
        public boolean hasKey() {
            try {
                return getKeyStore().containsAlias(KEYSTORE_ALIAS);
            } catch (NutstoreCredentialsException e) {
                return false;
            } catch (GeneralSecurityException e) {
                return false;
            }
        }

        @Override
        public void deleteKey() throws NutstoreCredentialsException {
            try {
                getKeyStore().deleteEntry(KEYSTORE_ALIAS);
            } catch (GeneralSecurityException e) {
                throw new NutstoreCredentialsException(
                        NutstoreCredentialsException.Reason.KEY_FAILURE, e);
            }
        }
    }
}
