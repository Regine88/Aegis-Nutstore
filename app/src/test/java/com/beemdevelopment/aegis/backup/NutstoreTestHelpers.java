package com.beemdevelopment.aegis.backup;

import android.content.Context;

import com.beemdevelopment.aegis.crypto.CryptoUtils;
import com.beemdevelopment.aegis.crypto.MasterKey;
import com.beemdevelopment.aegis.crypto.SCryptParameters;
import com.beemdevelopment.aegis.vault.Vault;
import com.beemdevelopment.aegis.vault.VaultFile;
import com.beemdevelopment.aegis.vault.VaultFileCredentials;
import com.beemdevelopment.aegis.vault.VaultRepository;
import com.beemdevelopment.aegis.vault.slots.PasswordSlot;
import com.beemdevelopment.aegis.vault.slots.SlotList;

import java.io.ByteArrayInputStream;

import javax.crypto.SecretKey;

/**
 * Shared fixtures for the Nutstore backup tests: an encrypted vault file and an
 * in-memory replacement for the Android Keystore.
 */
class NutstoreTestHelpers {
    static final char[] VAULT_PASSWORD = "vault-password".toCharArray();
    static final char[] APP_PASSWORD = "app-password".toCharArray();

    private NutstoreTestHelpers() {

    }

    /**
     * Writes a vault file to the app's files directory, optionally encrypted
     * and optionally with a password slot.
     */
    static void createVault(Context context, char[] password, boolean encrypted,
                            boolean withPasswordSlot) throws Exception {
        VaultFile file = new VaultFile();
        if (!encrypted) {
            file.setContent(new Vault().toJson());
        } else {
            MasterKey masterKey = MasterKey.generate();
            SlotList slots = new SlotList();
            if (withPasswordSlot) {
                SCryptParameters params = new SCryptParameters(
                        CryptoUtils.CRYPTO_SCRYPT_N, CryptoUtils.CRYPTO_SCRYPT_r,
                        CryptoUtils.CRYPTO_SCRYPT_p, CryptoUtils.generateSalt());
                PasswordSlot slot = new PasswordSlot();
                SecretKey key = slot.deriveKey(password, params);
                slot.setKey(masterKey, CryptoUtils.createEncryptCipher(key));
                slots.add(slot);
            }
            file.setContent(new Vault().toJson(), new VaultFileCredentials(masterKey, slots));
        }

        VaultRepository.writeToFile(context, new ByteArrayInputStream(file.toBytes()));
    }

    /**
     * The real Android Keystore is not available off-device, so unit tests use
     * an AES key that only lives in memory.
     */
    static class MemoryKeySource implements NutstoreCredentialStore.KeySource {
        private SecretKey _key;

        MemoryKeySource() {
            this(true);
        }

        MemoryKeySource(boolean generateKey) {
            _key = generateKey ? CryptoUtils.generateKey() : null;
        }

        @Override
        public SecretKey getKey() {
            return _key;
        }

        @Override
        public SecretKey getOrCreateKey() {
            if (_key == null) {
                _key = CryptoUtils.generateKey();
            }
            return _key;
        }

        @Override
        public boolean hasKey() {
            return _key != null;
        }

        @Override
        public void deleteKey() {
            _key = null;
        }
    }
}
