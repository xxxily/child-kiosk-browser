# Room components
-keep class * extends androidx.room.RoomDatabase
-keep class * implements androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Keep entities and DAOs intact to allow reflection and code generation
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# WebView javascript interface keeps
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# General optimization settings
-repackageclasses ''
-allowaccessmodification
