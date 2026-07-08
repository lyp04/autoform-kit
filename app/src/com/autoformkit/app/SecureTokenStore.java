package com.autoformkit.app;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureTokenStore {
    private static final String KEY_ALIAS = "autoform_token_key";
    private static final String PREF_CIPHER = "tokenCipher";
    private static final String PREF_IV = "tokenIv";
    private static final String PREF_PWD_CIPHER = "pwdCipher";
    private static final String PREF_PWD_IV = "pwdIv";
    private static final String LEGACY_TOKEN = "token";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private SecureTokenStore() {
    }

    static String get(SharedPreferences prefs) {
        String legacy = prefs.getString(LEGACY_TOKEN, "");
        if (legacy != null && !legacy.trim().isEmpty()) {
            put(prefs, legacy.trim());
            return legacy.trim();
        }
        String cipherText = prefs.getString(PREF_CIPHER, "");
        String ivText = prefs.getString(PREF_IV, "");
        if (cipherText == null || cipherText.isEmpty() || ivText == null || ivText.isEmpty()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception exc) {
            return "";
        }
    }

    static void put(SharedPreferences prefs, String token) {
        if (token == null || token.trim().isEmpty()) {
            clear(prefs);
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(token.trim().getBytes(StandardCharsets.UTF_8));
            prefs.edit()
                .putString(PREF_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .remove(LEGACY_TOKEN)
                .apply();
        } catch (Exception exc) {
            prefs.edit().putString(LEGACY_TOKEN, token.trim()).apply();
        }
    }

    static void clear(SharedPreferences prefs) {
        prefs.edit().remove(PREF_CIPHER).remove(PREF_IV).remove(LEGACY_TOKEN).apply();
    }

    // Company password: remembered across sign-ins so the user only re-enters it if the keystore is
    // unavailable. Same AndroidKeyStore key as the token; never falls back to plaintext (unlike the
    // token's legacy path) — if encryption fails we simply don't persist it.
    static String getPassword(SharedPreferences prefs) {
        String cipherText = prefs.getString(PREF_PWD_CIPHER, "");
        String ivText = prefs.getString(PREF_PWD_IV, "");
        if (cipherText == null || cipherText.isEmpty() || ivText == null || ivText.isEmpty()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception exc) {
            return "";
        }
    }

    static void putPassword(SharedPreferences prefs, String password) {
        if (password == null || password.isEmpty()) {
            clearPassword(prefs);
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            prefs.edit()
                .putString(PREF_PWD_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_PWD_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
        } catch (Exception exc) {
            clearPassword(prefs);
        }
    }

    static void clearPassword(SharedPreferences prefs) {
        prefs.edit().remove(PREF_PWD_CIPHER).remove(PREF_PWD_IV).apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
