# Default ProGuard / R8 rules for the GestureZoomCamera app.
# We do not enable minify in either build type, but keep these as a safety net
# for when release is later flagged `isMinifyEnabled = true`.

# Preserve CameraX / ML Kit reflective lookups — all of these libs already
# supply consumer rules, but listing them here keeps things explicit.
-keep class androidx.camera.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.odml.** { *; }

# Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
