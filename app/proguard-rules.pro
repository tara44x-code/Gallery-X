# Preserve Android manifest entry-point names while R8 shrinks, optimizes and
# obfuscates the rest of the application.
-keep class com.tara.nawagallery.MainActivity { public <init>(); }
-keep class com.tara.nawagallery.PhotoViewerActivity { public <init>(); }
-keep class com.tara.nawagallery.VideoPlayerActivity { public <init>(); }
-keep class com.tara.nawagallery.EditorActivity { public <init>(); }
-keep class com.tara.nawagallery.VaultContentProvider { public <init>(); }
-allowaccessmodification
-repackageclasses 'gx.secure'
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*
-keep class com.tara.nawagallery.GalleryApp { public <init>(); }
-keep class com.tara.nawagallery.MusicService { public <init>(); }
-keep class com.tara.nawagallery.MusicLibraryActivity { public <init>(); }
-keep class com.tara.nawagallery.MusicPlayerActivity { public <init>(); }
