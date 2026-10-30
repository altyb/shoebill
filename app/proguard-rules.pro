# libmpv calls back into these from native code by name
-keep class dev.jdtech.mpv.** { *; }
-keepclassmembers class * implements dev.jdtech.mpv.MPVLib$EventObserver { *; }
-keepclassmembers class * implements dev.jdtech.mpv.MPVLib$LogObserver { *; }

# release: drop debug/verbose logging (playback position, debug state lines)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
