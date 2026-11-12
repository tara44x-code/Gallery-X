package com.tara.nawagallery;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/**
 * Vault backup file (.gxbackup), pure Java.
 *
 * <pre>
 * header : "GXB1"(4) | iterations int(4) | salt(16)                       = 24 bytes
 * check  : iv(12) | AES-GCM("GXB-OK")     AAD = header                    = 34 bytes
 * entry  : 0x01 | length long(8) | one complete VaultCrypto file
 * end    : 0x00 | entry count int(4)
 * </pre>
 *
 * The key comes from a separate backup password (PBKDF2, many rounds) — not from the vault PIN and
 * not from this phone's Keystore — so the file can be restored on any phone that knows the password.
 * Every entry is a normal VaultCrypto file: names, types and contents stay encrypted and authenticated.
 */
public final class VaultBackup {
    public static final int ITERATIONS = 310_000;
    public static final int MIN_PASSWORD = 8;
    public static final String EXTENSION = ".gxbackup";
    private static final byte[] MAGIC = {'G', 'X', 'B', '1'};
    private static final byte[] CHECK = "GXB-OK".getBytes(StandardCharsets.US_ASCII);

    private VaultBackup() {}

    /** Wrong backup password (or not a Gallery X backup). */
    public static final class BadPasswordException extends IOException {
        BadPasswordException(String message) { super(message); }
    }

    /** The file ended before its end marker: everything before that point was still restored. */
    public static final class TruncatedException extends IOException {
        public final int restored;
        TruncatedException(int restored) {
            super("backup is incomplete");
            this.restored = restored;
        }
    }

    public interface EntryHandler {
        void entry(VaultCrypto.Reader reader) throws IOException;
    }

    // ------------------------------------------------------------------ writing

    public static final class Writer implements Closeable {
        private final DataOutputStream out;
        private final SecretKey key;
        private final File tempDir;
        private final byte[] buffer = new byte[64 * 1024];
        private int count;
        private boolean finished;

        /** Slow (key derivation): call off the main thread. */
        public Writer(OutputStream output, String password, File tempDir) throws IOException {
            this(output, password, tempDir, ITERATIONS);
        }

        Writer(OutputStream output, String password, File tempDir, int iterations) throws IOException {
            this.out = new DataOutputStream(output);
            this.tempDir = tempDir;
            byte[] salt = VaultKeyWrap.randomBytes(16);
            byte[] header = ByteBuffer.allocate(24).put(MAGIC).putInt(iterations).put(salt).array();
            try {
                key = VaultKeyWrap.derive(password, salt, iterations);
                byte[] iv = VaultKeyWrap.randomBytes(12);
                out.write(header);
                out.write(iv);
                out.write(VaultCrypto.gcm(Cipher.ENCRYPT_MODE, key, iv, header, CHECK, CHECK.length));
            } catch (GeneralSecurityException error) {
                throw new IOException("key derivation failed", error);
            }
        }

        /** Adds one file; {@code plain} is read to the end but not closed. */
        public void add(InputStream plain, VaultCrypto.Meta meta) throws IOException {
            File temp = File.createTempFile("gxb", ".part", tempDir);
            try {
                VaultCrypto.encrypt(key, plain, temp, meta);
                out.writeByte(1);
                out.writeLong(temp.length());
                try (InputStream in = new FileInputStream(temp)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                }
                count++;
            } finally {
                temp.delete();
            }
        }

        public int count() { return count; }

        public void finish() throws IOException {
            if (finished) return;
            out.writeByte(0);
            out.writeInt(count);
            out.flush();
            finished = true;
        }

        @Override public void close() throws IOException {
            out.flush();
        }
    }

    // ------------------------------------------------------------------ reading

    /**
     * Reads a backup and hands each entry to {@code handler} as an open reader (closed afterwards).
     * Slow at the start (key derivation): call off the main thread. Returns the number of entries.
     */
    public static int read(InputStream input, String password, File tempDir, EntryHandler handler) throws IOException {
        return read(input, password, tempDir, handler, Integer.MAX_VALUE);
    }

    static int read(InputStream input, String password, File tempDir, EntryHandler handler, int maxIterations)
            throws IOException {
        DataInputStream in = new DataInputStream(input);
        byte[] header = new byte[24];
        byte[] check = new byte[12 + CHECK.length + 16];
        try {
            in.readFully(header);
            in.readFully(check);
        } catch (EOFException tooShort) {
            throw new BadPasswordException("not a backup");
        }
        if (!Arrays.equals(Arrays.copyOf(header, 4), MAGIC)) throw new BadPasswordException("not a backup");
        ByteBuffer fields = ByteBuffer.wrap(header, 4, 20);
        int iterations = fields.getInt();
        byte[] salt = new byte[16];
        fields.get(salt);
        if (iterations < 10_000 || iterations > Math.min(maxIterations, 10_000_000)) throw new BadPasswordException("bad header");
        SecretKey key;
        try {
            key = VaultKeyWrap.derive(password, salt, iterations);
            byte[] plain = VaultCrypto.gcm(Cipher.DECRYPT_MODE, key, Arrays.copyOf(check, 12), header,
                    Arrays.copyOfRange(check, 12, check.length), check.length - 12);
            if (!Arrays.equals(plain, CHECK)) throw new BadPasswordException("wrong password");
        } catch (GeneralSecurityException wrong) {
            throw new BadPasswordException("wrong password");
        }

        int restored = 0;
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            int tag;
            try {
                tag = in.readUnsignedByte();
            } catch (EOFException end) {
                throw new TruncatedException(restored);
            }
            if (tag == 0) {
                in.readInt(); // declared count; entries were already counted as they were read
                return restored;
            }
            if (tag != 1) throw new TruncatedException(restored);
            long length;
            try {
                length = in.readLong();
            } catch (EOFException end) {
                throw new TruncatedException(restored);
            }
            if (length <= 0 || length > (1L << 40)) throw new TruncatedException(restored);
            File temp = File.createTempFile("gxr", ".part", tempDir);
            try {
                try (OutputStream out = new FileOutputStream(temp)) {
                    long left = length;
                    while (left > 0) {
                        int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                        if (read < 0) throw new TruncatedException(restored);
                        out.write(buffer, 0, read);
                        left -= read;
                    }
                }
                try (VaultCrypto.Reader reader = new VaultCrypto.Reader(key, temp)) {
                    handler.entry(reader);
                }
                restored++;
            } finally {
                temp.delete();
            }
        }
    }
}
