package com.tara.nawagallery;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Holds the vault key in memory only while the vault is unlocked.
 *
 * The random file key is wrapped twice: first with a key derived from the PIN (PBKDF2), then with
 * a hardware-backed Android Keystore key. Copying the app data to another device is useless, and
 * on this device every guess still costs a full PBKDF2 run, so the PIN is the only way in.
 * There is no fingerprint path and no recovery: a forgotten PIN means the files stay locked.
 */
public final class VaultSession {
    public enum Status { OK, WRONG, LOCKED_OUT, ERROR }

    public static final long AUTO_LOCK_MS = 10_000L;
    public static final int MIN_PIN = 6;
    private static final String PREFS = "gx_vault_keys";
    private static final String ALIAS = "gx_vault_wrap_v1";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable AUTO_LOCK = VaultSession::lock;

    private static volatile SecretKey fileKey;
    private static int startedActivities;

    private VaultSession() {}

    // ---------------------------------------------------------------- state

    public static boolean isSetUp(Context context) {
        return prefs(context).contains("blob");
    }

    public static boolean isUnlocked() { return fileKey != null; }

    /** The key for VaultCrypto, or null when locked. */
    public static SecretKey key() { return fileKey; }

    public static void lock() {
        fileKey = null;
        VaultStore.forgetNames();
    }

    /** Milliseconds until another PIN attempt is allowed (0 = allowed now). */
    public static long lockoutRemaining(Context context) {
        long until = prefs(context).getLong("lock_until", 0L);
        long left = until - System.currentTimeMillis();
        return Math.max(0L, Math.min(left, 3_600_000L));
    }

    // ---------------------------------------------------------------- setup / unlock (call off the main thread)

    public static synchronized Status create(Context context, String pin) {
        try {
            byte[] salt = VaultKeyWrap.randomBytes(16);
            byte[] raw = VaultKeyWrap.randomBytes(32);
            byte[] inner = VaultKeyWrap.wrap(VaultKeyWrap.derive(pin, salt, VaultKeyWrap.ITERATIONS), raw);
            boolean hardware = false;
            byte[] blob = inner;
            try {
                blob = keystoreWrap(context, inner);
                hardware = true;
            } catch (Exception ignored) { }
            prefs(context).edit()
                    .putInt("v", 1)
                    .putString("salt", b64(salt))
                    .putInt("iter", VaultKeyWrap.ITERATIONS)
                    .putString("blob", b64(blob))
                    .putBoolean("hw", hardware)
                    .putInt("fails", 0)
                    .putLong("lock_until", 0L)
                    .commit();
            fileKey = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return Status.OK;
        } catch (Exception error) {
            return Status.ERROR;
        }
    }

    public static synchronized Status unlock(Context context, String pin) {
        SharedPreferences prefs = prefs(context);
        if (lockoutRemaining(context) > 0) return Status.LOCKED_OUT;
        try {
            byte[] salt = unb64(prefs.getString("salt", ""));
            int iterations = prefs.getInt("iter", VaultKeyWrap.ITERATIONS);
            byte[] blob = unb64(prefs.getString("blob", ""));
            byte[] inner = prefs.getBoolean("hw", false) ? keystoreUnwrap(blob) : blob;
            byte[] raw = VaultKeyWrap.unwrap(VaultKeyWrap.derive(pin, salt, iterations), inner);
            fileKey = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            prefs.edit().putInt("fails", 0).putLong("lock_until", 0L).commit();
            return Status.OK;
        } catch (AEADBadTagException wrongPin) {
            int fails = prefs.getInt("fails", 0) + 1;
            long wait = VaultKeyWrap.lockoutMillis(fails);
            prefs.edit().putInt("fails", fails)
                    .putLong("lock_until", wait > 0 ? System.currentTimeMillis() + wait : 0L).commit();
            return Status.WRONG;
        } catch (Exception error) {
            return Status.ERROR;
        }
    }

    /** Re-wraps the current key with a new PIN. The vault must be unlocked. */
    public static synchronized Status changePin(Context context, String newPin) {
        SecretKey current = fileKey;
        if (current == null) return Status.ERROR;
        try {
            byte[] salt = VaultKeyWrap.randomBytes(16);
            byte[] inner = VaultKeyWrap.wrap(VaultKeyWrap.derive(newPin, salt, VaultKeyWrap.ITERATIONS), current.getEncoded());
            boolean hardware = false;
            byte[] blob = inner;
            try {
                blob = keystoreWrap(context, inner);
                hardware = true;
            } catch (Exception ignored) { }
            prefs(context).edit().putString("salt", b64(salt)).putInt("iter", VaultKeyWrap.ITERATIONS)
                    .putString("blob", b64(blob)).putBoolean("hw", hardware).commit();
            return Status.OK;
        } catch (Exception error) {
            return Status.ERROR;
        }
    }

    // ---------------------------------------------------------------- auto lock when the whole app leaves the screen

    public static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                startedActivities++;
                MAIN.removeCallbacks(AUTO_LOCK);
            }
            @Override public void onActivityStopped(Activity activity) {
                startedActivities = Math.max(0, startedActivities - 1);
                if (startedActivities == 0 && !activity.isChangingConfigurations()) {
                    MAIN.removeCallbacks(AUTO_LOCK);
                    MAIN.postDelayed(AUTO_LOCK, AUTO_LOCK_MS);
                }
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    // ---------------------------------------------------------------- Android Keystore layer

    private static SecretKey keystoreKey(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new GeneralSecurityException("keystore key missing");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static byte[] keystoreWrap(Context context, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(true));
        byte[] iv = cipher.getIV();
        byte[] sealed = cipher.doFinal(data);
        byte[] out = new byte[1 + iv.length + sealed.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(sealed, 0, out, 1 + iv.length, sealed.length);
        return out;
    }

    private static byte[] keystoreUnwrap(byte[] blob) throws Exception {
        int ivLength = blob[0] & 0xff;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(false), new GCMParameterSpec(128, blob, 1, ivLength));
        try {
            return cipher.doFinal(blob, 1 + ivLength, blob.length - 1 - ivLength);
        } catch (AEADBadTagException damaged) {
            // A hardware-layer failure is never a wrong PIN; report it as an error, not a failed try.
            throw new GeneralSecurityException("keystore layer damaged", damaged);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String b64(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP); }
    private static byte[] unb64(String value) { return Base64.decode(value, Base64.NO_WRAP); }
}
