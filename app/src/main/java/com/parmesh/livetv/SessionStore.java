package com.parmesh.livetv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SessionStore {
    private static final String KEY = "jellyfin-live-session";
    private final SharedPreferences prefs;

    SessionStore(Context context) { prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE); }

    String device() {
        String device = prefs.getString("device", null);
        if (device == null) {
            device = UUID.randomUUID().toString();
            prefs.edit().putString("device", device).apply();
        }
        return device;
    }

    String address() { return prefs.getString("server", ""); }

    Jellyfin.Session read() {
        String token = prefs.getString("token", null);
        if (token == null) return null;
        try {
            byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            String value = new String(cipher.doFinal(Base64.decode(token, Base64.NO_WRAP)), StandardCharsets.UTF_8);
            return new Jellyfin.Session(address(), value, prefs.getString("user", ""), device());
        } catch (Exception unreadable) {
            clear();
            return null;
        }
    }

    void save(Jellyfin.Session session) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(session.token().getBytes(StandardCharsets.UTF_8));
        if (!prefs.edit().putString("server", session.server()).putString("user", session.user())
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            throw new java.io.IOException("Could not save sign-in.");
        }
    }

    void clear() { prefs.edit().remove("token").remove("iv").remove("user").apply(); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(KEY, null) instanceof SecretKey key) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
