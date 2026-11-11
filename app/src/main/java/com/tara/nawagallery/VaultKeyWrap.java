package com.tara.nawagallery;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * PIN → key derivation and key wrapping (pure Java).
 * The vault's file key is random; it is stored only wrapped by a key derived from the PIN,
 * so without the PIN nobody — root included — can decrypt the files.
 */
public final class VaultKeyWrap {
    public static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultKeyWrap() {}

    public static byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        RANDOM.nextBytes(value);
        return value;
    }

    public static SecretKey derive(String pin, byte[] salt, int iterations) throws GeneralSecurityException {
        char[] chars = pin.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            SecretKey key = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return key;
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }

    /** Returns iv(12) + AES-GCM(secret). */
    public static byte[] wrap(SecretKey kek, byte[] secret) throws GeneralSecurityException {
        byte[] iv = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(128, iv));
        byte[] sealed = cipher.doFinal(secret);
        byte[] out = new byte[12 + sealed.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(sealed, 0, out, 12, sealed.length);
        return out;
    }

    /** Throws {@link javax.crypto.AEADBadTagException} when the key (PIN) is wrong. */
    public static byte[] unwrap(SecretKey kek, byte[] blob) throws GeneralSecurityException {
        if (blob == null || blob.length < 12 + 16) throw new GeneralSecurityException("bad blob");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(128, blob, 0, 12));
        return cipher.doFinal(blob, 12, blob.length - 12);
    }

    /** Lock-out after repeated wrong PINs: 5 free tries, then 30 s doubling up to one hour. */
    public static long lockoutMillis(int failures) {
        if (failures < 5) return 0L;
        int steps = Math.min(failures - 5, 7);
        return Math.min(30_000L << steps, 3_600_000L);
    }
}
