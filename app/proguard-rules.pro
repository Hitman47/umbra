# Nyxara R8 rules: the code is shrunk and optimized (Compose is much faster so,
# above all on a TV), names are kept so that crash reports stay readable.
-dontobfuscate

# libmpv: the native library calls these Java methods by name (JNI).
-keep class dev.jdtech.mpv.** { *; }
-keep class * implements dev.jdtech.mpv.MPVLib$EventObserver { *; }
-keep class * implements dev.jdtech.mpv.MPVLib$LogObserver { *; }

# SMB (smbj, its share listing, its event bus found by annotations), NFS,
# the local HTTP server: libraries that look things up by reflection, kept whole.
-keep class com.hierynomus.** { *; }
-keep class com.rapid7.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.emc.ecs.nfsclient.** { *; }
-keep class fi.iki.elonen.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler *;
}

# The app's view models, built by the default factory from their constructor.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# Optional dependencies of those libraries, absent on Android.
-dontwarn javax.**
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn com.sun.**
-dontwarn sun.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.**
-dontwarn io.netty.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jetbrains.annotations.**
-dontwarn edu.umd.cs.findbugs.annotations.**
