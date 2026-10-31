# libmpv calls back into these from native code by name
-keep class dev.jdtech.mpv.** { *; }
-keepclassmembers class * implements dev.jdtech.mpv.MPVLib$EventObserver { *; }
-keepclassmembers class * implements dev.jdtech.mpv.MPVLib$LogObserver { *; }

# release: drop debug/verbose logging (playback position, debug state lines)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# smbj: reads classes by name; Kerberos and logging backends are not used on Android
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn javax.security.auth.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn net.engio.**
-dontwarn org.bouncycastle.**
