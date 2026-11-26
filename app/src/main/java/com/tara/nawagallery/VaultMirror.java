package com.tara.nawagallery;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Automatic vault backup into a folder the user picks (on the phone, an SD card or a cloud-synced folder).
 * The folder holds the vault files exactly as they are (already encrypted) plus a key file in which the
 * vault key is locked with the PIN only — not with this phone's Keystore — so after reinstalling the app,
 * or on a new phone, the whole vault comes back with the PIN. Without the PIN the folder is unreadable.
 */
final class VaultMirror {
    static final String KEY_FILE = "GalleryX-vault-key.json";
    private static final String PREFS = "gx_vault_mirror";
    private static final int ITERATIONS = 400_000; // slower than the vault itself: this copy can leave the phone
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable pending;

    interface Progress { void step(int done, int total); }

    /** Wrong PIN for that backup. */
    static final class WrongPinException extends Exception { }

    private VaultMirror() {}

    static Uri folder(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tree", null);
        return value == null ? null : Uri.parse(value);
    }

    static void disable(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("tree").apply();
    }

    /** Turns the mirror on: writes the PIN-locked key file, then copies every vault file. Vault must be unlocked. */
    static int enable(Context context, Uri tree, String pin, Progress progress) throws Exception {
        SecretKey key = VaultSession.key();
        if (key == null) throw new IllegalStateException("vault locked");
        byte[] salt = VaultKeyWrap.randomBytes(16);
        byte[] blob = VaultKeyWrap.wrap(VaultKeyWrap.derive(pin, salt, ITERATIONS), key.getEncoded());
        JSONObject json = new JSONObject();
        json.put("app", "Gallery X");
        json.put("v", 1);
        json.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        json.put("iter", ITERATIONS);
        json.put("key", Base64.encodeToString(blob, Base64.NO_WRAP));
        Map<String, Child> existing = list(context, tree);
        Child keyFile = existing.get(KEY_FILE);
        Uri keyUri = keyFile != null ? keyFile.uri : create(context, tree, KEY_FILE, "application/json");
        try (OutputStream out = context.getContentResolver().openOutputStream(keyUri, "wt")) {
            if (out == null) throw new java.io.IOException("cannot write");
            out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tree", tree.toString()).apply();
        return sync(context, progress);
    }

    /** Copies new vault files to the folder and removes the ones deleted from the vault. Needs no key. */
    static synchronized int sync(Context context, Progress progress) throws Exception {
        Uri tree = folder(context);
        if (tree == null) return 0;
        File[] files = VaultStore.directory(context).listFiles(f -> f.isFile() && f.getName().endsWith(".gxv"));
        if (files == null) files = new File[0];
        Map<String, Child> remote = list(context, tree);
        Map<String, File> local = new HashMap<>();
        for (File f : files) local.put(f.getName(), f);
        int changes = 0, done = 0;
        byte[] buffer = new byte[256 * 1024];
        for (File file : files) {
            Child copy = remote.get(file.getName());
            if (copy == null || copy.size != file.length()) { // missing, or a copy that was cut off
                if (copy != null) DocumentsContract.deleteDocument(context.getContentResolver(), copy.uri);
                Uri target = create(context, tree, file.getName(), "application/octet-stream");
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = context.getContentResolver().openOutputStream(target, "w")) {
                    if (out == null) throw new java.io.IOException("cannot write");
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                } catch (Exception error) {
                    try { DocumentsContract.deleteDocument(context.getContentResolver(), target); } catch (Exception ignored) { }
                    throw error;
                }
                changes++;
            }
            done++;
            if (progress != null) progress.step(done, files.length);
        }
        for (Map.Entry<String, Child> entry : remote.entrySet()) {
            if (entry.getKey().endsWith(".gxv") && !local.containsKey(entry.getKey())) {
                try { DocumentsContract.deleteDocument(context.getContentResolver(), entry.getValue().uri); changes++; }
                catch (Exception ignored) { }
            }
        }
        return changes;
    }

    /** Runs a sync a few seconds after the vault changes (several changes in a row become one sync). */
    static void syncSoon(Context context) {
        if (folder(context) == null) return;
        Context app = context.getApplicationContext();
        if (pending != null) MAIN.removeCallbacks(pending);
        pending = () -> WORKER.execute(() -> { try { sync(app, null); } catch (Exception ignored) { } });
        MAIN.postDelayed(pending, 3000);
    }

    /**
     * Brings a mirrored vault back (after reinstalling, or from another phone) into the current vault.
     * {@code pin} is the PIN the vault had when the backup folder was set up.
     */
    static int restore(Context context, Uri tree, String pin, Progress progress) throws Exception {
        SecretKey current = VaultSession.key();
        if (current == null) throw new IllegalStateException("vault locked");
        Map<String, Child> remote = list(context, tree);
        Child keyFile = remote.get(KEY_FILE);
        if (keyFile == null) throw new java.io.FileNotFoundException("no key file");
        String text;
        try (InputStream in = context.getContentResolver().openInputStream(keyFile.uri)) {
            if (in == null) throw new java.io.IOException("cannot read");
            text = new String(readAll(in), StandardCharsets.UTF_8);
        }
        JSONObject json = new JSONObject(text);
        byte[] salt = Base64.decode(json.getString("salt"), Base64.NO_WRAP);
        byte[] blob = Base64.decode(json.getString("key"), Base64.NO_WRAP);
        SecretKey old;
        try {
            byte[] raw = VaultKeyWrap.unwrap(VaultKeyWrap.derive(pin, salt, json.getInt("iter")), blob);
            old = new SecretKeySpec(raw, "AES");
        } catch (javax.crypto.AEADBadTagException wrong) {
            throw new WrongPinException();
        }
        File vault = VaultStore.directory(context);
        int total = 0, restored = 0, done = 0;
        for (String name : remote.keySet()) if (name.endsWith(".gxv")) total++;
        File temp = new File(context.getCacheDir(), "gx-restore.part");
        for (Map.Entry<String, Child> entry : remote.entrySet()) {
            String name = entry.getKey();
            if (!name.endsWith(".gxv")) continue;
            done++;
            if (new File(vault, name).exists()) { if (progress != null) progress.step(done, total); continue; }
            try {
                try (InputStream in = context.getContentResolver().openInputStream(entry.getValue().uri);
                     OutputStream out = new FileOutputStream(temp)) {
                    if (in == null) continue;
                    byte[] buffer = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
                File partial = new File(vault, name + ".part");
                try (VaultCrypto.Reader reader = new VaultCrypto.Reader(old, temp)) {
                    VaultCrypto.encrypt(current, reader.stream(), partial, reader.meta); // re-lock with this phone's vault key
                }
                if (partial.renameTo(new File(vault, name))) restored++;
                else partial.delete();
            } catch (Exception skipped) {
                // a damaged copy is skipped; the rest still comes back
            } finally {
                temp.delete();
            }
            if (progress != null) progress.step(done, total);
        }
        return restored;
    }

    // ------------------------------------------------------------------ Storage Access Framework helpers

    private static final class Child {
        final Uri uri;
        final long size;
        Child(Uri uri, long size) { this.uri = uri; this.size = size; }
    }

    private static Map<String, Child> list(Context context, Uri tree) {
        Map<String, Child> out = new HashMap<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        ContentResolver resolver = context.getContentResolver();
        try (Cursor c = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                out.put(c.getString(1), new Child(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                        c.isNull(2) ? -1 : c.getLong(2)));
            }
        }
        return out;
    }

    private static Uri create(Context context, Uri tree, String name, String mime) throws Exception {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent, mime, name);
        if (created == null) throw new java.io.IOException("cannot create " + name);
        return created;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > 1_000_000) throw new java.io.IOException("too large");
        }
        return out.toByteArray();
    }
}
