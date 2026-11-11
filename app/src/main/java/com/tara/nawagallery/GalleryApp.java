package com.tara.nawagallery;

import android.app.Application;

public class GalleryApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        VaultSession.install(this);
    }
}
