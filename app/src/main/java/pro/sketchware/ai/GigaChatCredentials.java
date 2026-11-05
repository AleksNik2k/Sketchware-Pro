package pro.sketchware.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class GigaChatCredentials {
    public static final String PERSONAL_SCOPE = "GIGACHAT_API_PERS";
    public static final String BUSINESS_SCOPE = "GIGACHAT_API_B2B";
    public static final String CORPORATE_SCOPE = "GIGACHAT_API_CORP";

    private static final String KEY_ALIAS = "sketchware_gigachat_credentials";
    private static final String PREFS_NAME = "gigachat_agent";
    private static final String CREDENTIALS_KEY = "credentials";
    private static final String SCOPE_KEY = "scope";

    private GigaChatCredentials() {
    }

    public static void save(Context context, String credentials, String scope)
            throws GeneralSecurityException, IOException {
        byte[] encrypted = encrypt(credentials.trim().getBytes(StandardCharsets.UTF_8));
        preferences(context).edit()
                .putString(CREDENTIALS_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(SCOPE_KEY, normalizeScope(scope))
                .apply();
    }

    public static String getCredentials(Context context) {
        String encoded = preferences(context).getString(CREDENTIALS_KEY, null);
        if (encoded == null) return null;

        try {
            byte[] encrypted = Base64.decode(encoded, Base64.NO_WRAP);
            return new String(decrypt(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            clear(context);
            return null;
        }
    }

    public static String getScope(Context context) {
        return normalizeScope(preferences(context).getString(SCOPE_KEY, PERSONAL_SCOPE));
    }

    public static void clear(Context context) {
        preferences(context).edit().remove(CREDENTIALS_KEY).remove(SCOPE_KEY).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String normalizeScope(String scope) {
        if (BUSINESS_SCOPE.equals(scope) || CORPORATE_SCOPE.equals(scope)) return scope;
        return PERSONAL_SCOPE;
    }

    private static byte[] encrypt(byte[] plaintext) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext);
        ByteBuffer buffer = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
        buffer.put((byte) iv.length).put(iv).put(ciphertext);
        return buffer.array();
    }

    private static byte[] decrypt(byte[] encrypted) throws GeneralSecurityException, IOException {
        ByteBuffer buffer = ByteBuffer.wrap(encrypted);
        int ivLength = Byte.toUnsignedInt(buffer.get());
        if (ivLength == 0 || ivLength > buffer.remaining()) {
            throw new GeneralSecurityException("Invalid encrypted credential data");
        }
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        keyGenerator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return keyGenerator.generateKey();
    }
}