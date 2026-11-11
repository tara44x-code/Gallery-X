package com.tara.nawagallery;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.security.MessageDigest;

/** Rejects repackaged APKs that are not signed with the Gallery X signing key. */
final class AppIntegrity {
    private static final String TRUSTED = "7648a453ab9c456254ca3ff34630513400e02c548bd4e44db4813303bbb4c6d3";
    private AppIntegrity() {}

    static boolean verifyOrClose(Activity activity) {
        try {
            PackageManager manager = activity.getPackageManager();
            PackageInfo info;
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28) {
                info = manager.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            } else {
                info = manager.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if (signatures != null) for (Signature signature : signatures) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
                StringBuilder value = new StringBuilder();
                for (byte part : digest) value.append(String.format("%02x", part & 0xff));
                if (TRUSTED.equals(value.toString())) return true;
            }
        } catch (Exception ignored) { }
        activity.finishAndRemoveTask();
        return false;
    }
}
