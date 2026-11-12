package com.tara.nawagallery;

import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;

/**
 * Looks for a companion file (subtitles, lyrics) next to a media file, or inside the folders the
 * user allowed once ("subtitle folders"); Android 11+ hides such files from apps otherwise.
 */
final class FolderSearch {
    interface NameScore { int score(String fileName); } // -1 = no match, higher = better

    private FolderSearch() {}

    static File nextTo(String mediaPath, NameScore test) {
        if (mediaPath == null) return null;
        File folder = new File(mediaPath).getParentFile();
        File[] files;
        try { files = folder == null ? null : folder.listFiles(); } catch (Exception denied) { return null; }
        if (files == null) return null;
        File best = null;
        int bestScore = -1;
        for (File file : files) {
            int score = test.score(file.getName());
            if (score > bestScore) { best = file; bestScore = score; }
        }
        return best != null && best.canRead() ? best : null;
    }

    static Uri inAllowedFolders(Context context, NameScore test) {
        Uri bestUri = null;
        int bestScore = -1;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            Uri tree = permission.getUri();
            if (!permission.isReadPermission() || !DocumentsContract.isTreeUri(tree)) continue;
            ArrayDeque<String[]> queue = new ArrayDeque<>();
            queue.add(new String[]{DocumentsContract.getTreeDocumentId(tree), "0"});
            int visited = 0;
            while (!queue.isEmpty() && visited < 4000) {
                String[] next = queue.poll();
                int depth = Integer.parseInt(next[1]);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, next[0]);
                try (Cursor c = context.getContentResolver().query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        visited++;
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                            if (depth < 3) queue.add(new String[]{c.getString(0), String.valueOf(depth + 1)});
                            continue;
                        }
                        int score = test.score(c.getString(1));
                        if (score > bestScore) { bestScore = score; bestUri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)); }
                    }
                } catch (Exception ignored) { }
            }
        }
        return bestUri;
    }

    static String read(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) { return read(in); }
        catch (Exception e) { return null; }
    }

    static String read(File file) {
        try (InputStream in = new FileInputStream(file)) { return read(in); }
        catch (Exception e) { return null; }
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            bytes.write(buffer, 0, n);
            if (bytes.size() > 4 * 1024 * 1024) return null;
        }
        String text = SubtitleParser.decode(bytes.toByteArray());
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
