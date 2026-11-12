package com.tara.nawagallery;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypted vault file format (pure Java, no Android dependencies).
 *
 * <pre>
 * header  : "GXV1"(4) | chunkSize int(4) | fileNonce(8) | metaLength int(4)   = 20 bytes
 * meta    : iv(12) | AES-GCM(metadata UTF-8)          AAD = "meta" + header
 * chunk i : AES-GCM(plain chunk)                      nonce = fileNonce(8) + i(4)
 *                                                     AAD   = header + i(4) + final(1)
 * </pre>
 *
 * Every chunk is authenticated on its own, so a video can be decrypted at any position
 * (seeking) while any tampering, reordering or truncation is detected. The last chunk carries
 * a "final" flag, so cutting whole chunks off the end is detected too.
 */
public final class VaultCrypto {
    static final byte[] MAGIC = {'G', 'X', 'V', '1'};
    static final int HEADER_SIZE = 20;
    static final int TAG_SIZE = 16;
    static final int DEFAULT_CHUNK = 64 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultCrypto() {}

    /** Name, MIME type and original date travel inside the encrypted file, never in clear text. */
    public static final class Meta {
        public final String name;
        public final String mime;
        public final long dateTaken;

        public Meta(String name, String mime, long dateTaken) {
            this.name = name == null ? "" : name;
            this.mime = mime == null ? "" : mime;
            this.dateTaken = dateTaken;
        }

        byte[] encode() {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            byte[] m = mime.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(16 + n.length + m.length);
            buffer.putInt(n.length).put(n).putInt(m.length).put(m).putLong(dateTaken);
            return buffer.array();
        }

        static Meta decode(byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            byte[] n = new byte[buffer.getInt()];
            buffer.get(n);
            byte[] m = new byte[buffer.getInt()];
            buffer.get(m);
            return new Meta(new String(n, StandardCharsets.UTF_8), new String(m, StandardCharsets.UTF_8), buffer.getLong());
        }
    }

    /** Encrypts {@code input} into {@code destination}; the caller closes the input stream. */
    public static void encrypt(SecretKey key, InputStream input, File destination, Meta meta) throws IOException {
        encrypt(key, input, destination, meta, DEFAULT_CHUNK);
    }

    static void encrypt(SecretKey key, InputStream input, File destination, Meta meta, int chunkSize) throws IOException {
        byte[] fileNonce = new byte[8];
        RANDOM.nextBytes(fileNonce);
        try (OutputStream out = new FileOutputStream(destination)) {
            byte[] metaIv = new byte[12];
            RANDOM.nextBytes(metaIv);
            byte[] plainMeta = meta.encode();
            int metaLength = 12 + plainMeta.length + TAG_SIZE;
            byte[] header = ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).putInt(chunkSize)
                    .put(fileNonce).putInt(metaLength).array();
            out.write(header);
            out.write(metaIv);
            out.write(gcm(Cipher.ENCRYPT_MODE, key, metaIv, metaAad(header), plainMeta, plainMeta.length));

            byte[] current = new byte[chunkSize];
            byte[] next = new byte[chunkSize];
            int currentLength = fill(input, current);
            int index = 0;
            while (true) {
                int nextLength = currentLength == chunkSize ? fill(input, next) : 0;
                boolean last = nextLength == 0;
                out.write(gcm(Cipher.ENCRYPT_MODE, key, chunkNonce(fileNonce, index),
                        chunkAad(header, index, last), current, currentLength));
                if (last) break;
                byte[] swap = current; current = next; next = swap;
                currentLength = nextLength;
                index++;
            }
            out.flush();
        } catch (GeneralSecurityException error) {
            throw new IOException("encryption failed", error);
        }
    }

    private static int fill(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    /** Random-access reader. Not thread-safe; use one per open descriptor. */
    public static final class Reader implements AutoCloseable {
        private final RandomAccessFile file;
        private final SecretKey key;
        private final byte[] header = new byte[HEADER_SIZE];
        private final byte[] fileNonce = new byte[8];
        private final int chunkSize;
        private final long dataStart;
        private final int chunkCount;
        private final long plainLength;
        public final Meta meta;
        private int cachedIndex = -1;
        private byte[] cached;

        public Reader(SecretKey key, File source) throws IOException {
            this.key = key;
            this.file = new RandomAccessFile(source, "r");
            try {
                file.readFully(header);
                ByteBuffer buffer = ByteBuffer.wrap(header);
                byte[] magic = new byte[4];
                buffer.get(magic);
                if (!Arrays.equals(magic, MAGIC)) throw new IOException("not a vault file");
                chunkSize = buffer.getInt();
                buffer.get(fileNonce);
                int metaLength = buffer.getInt();
                if (chunkSize <= 0 || chunkSize > 16 * 1024 * 1024 || metaLength < 12 + TAG_SIZE || metaLength > 64 * 1024)
                    throw new IOException("bad header");
                byte[] metaBlock = new byte[metaLength];
                file.readFully(metaBlock);
                byte[] iv = Arrays.copyOfRange(metaBlock, 0, 12);
                meta = Meta.decode(gcm(Cipher.DECRYPT_MODE, key, iv, metaAad(header),
                        Arrays.copyOfRange(metaBlock, 12, metaLength), metaLength - 12));
                dataStart = HEADER_SIZE + metaLength;
                long dataLength = file.length() - dataStart;
                long sealed = chunkSize + TAG_SIZE;
                if (dataLength < TAG_SIZE) throw new IOException("truncated");
                chunkCount = (int) ((dataLength + sealed - 1) / sealed);
                long lastSealed = dataLength - (long) (chunkCount - 1) * sealed;
                if (lastSealed < TAG_SIZE) throw new IOException("truncated");
                plainLength = (long) (chunkCount - 1) * chunkSize + (lastSealed - TAG_SIZE);
            } catch (GeneralSecurityException error) {
                file.close();
                throw new IOException("wrong key or damaged file", error);
            } catch (IOException | RuntimeException error) {
                file.close();
                throw error;
            }
        }

        public long length() { return plainLength; }

        /** Reads up to {@code size} plain bytes starting at {@code offset}; returns bytes read (0 at end). */
        public int read(long offset, int size, byte[] out, int outOffset) throws IOException {
            if (offset >= plainLength || size <= 0) return 0;
            int total = 0;
            while (total < size && offset < plainLength) {
                int index = (int) (offset / chunkSize);
                byte[] plain = chunk(index);
                int inChunk = (int) (offset - (long) index * chunkSize);
                int count = Math.min(size - total, plain.length - inChunk);
                if (count <= 0) break;
                System.arraycopy(plain, inChunk, out, outOffset + total, count);
                total += count;
                offset += count;
            }
            return total;
        }

        private byte[] chunk(int index) throws IOException {
            if (index == cachedIndex) return cached;
            long sealed = chunkSize + TAG_SIZE;
            long position = dataStart + index * sealed;
            int length = (int) Math.min(sealed, file.length() - position);
            byte[] data = new byte[length];
            file.seek(position);
            file.readFully(data);
            try {
                cached = gcm(Cipher.DECRYPT_MODE, key, chunkNonce(fileNonce, index),
                        chunkAad(header, index, index == chunkCount - 1), data, length);
            } catch (GeneralSecurityException error) {
                throw new IOException("damaged chunk " + index, error);
            }
            cachedIndex = index;
            return cached;
        }

        /** The plain content as a stream (decrypted chunk by chunk, never all in memory). */
        public InputStream stream() {
            return new InputStream() {
                private long position;
                private final byte[] one = new byte[1];

                @Override public int read() throws IOException {
                    int n = read(one, 0, 1);
                    return n <= 0 ? -1 : one[0] & 0xff;
                }

                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    int n = Reader.this.read(position, length, buffer, offset);
                    if (n <= 0) return -1;
                    position += n;
                    return n;
                }
            };
        }

        /** Streams all plain bytes into {@code out}. */
        public void copyTo(OutputStream out) throws IOException {
            for (int i = 0; i < chunkCount; i++) out.write(chunk(i));
        }

        @Override public void close() throws IOException {
            cached = null;
            file.close();
        }
    }

    /** Reads only the metadata; throws when the key is wrong. */
    public static Meta readMeta(SecretKey key, File source) throws IOException {
        try (Reader reader = new Reader(key, source)) { return reader.meta; }
    }

    public static byte[] readAll(SecretKey key, File source) throws IOException {
        try (Reader reader = new Reader(key, source)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(reader.length(), Integer.MAX_VALUE - 8));
            reader.copyTo(out);
            return out.toByteArray();
        }
    }

    private static byte[] chunkNonce(byte[] fileNonce, int index) {
        return ByteBuffer.allocate(12).put(fileNonce).putInt(index).array();
    }

    private static byte[] chunkAad(byte[] header, int index, boolean last) {
        return ByteBuffer.allocate(HEADER_SIZE + 5).put(header).putInt(index).put((byte) (last ? 1 : 0)).array();
    }

    private static byte[] metaAad(byte[] header) {
        return ByteBuffer.allocate(4 + HEADER_SIZE).put(new byte[]{'m', 'e', 't', 'a'}).put(header).array();
    }

    static byte[] gcm(int mode, SecretKey key, byte[] nonce, byte[] aad, byte[] data, int length)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_SIZE * 8, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(data, 0, length);
    }
}
