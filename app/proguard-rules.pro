# Add project specific ProGuard rules here.

# Keep AirPlay protocol classes
-keep class com.airreceiver.protocol.** { *; }
-keep class com.airreceiver.server.** { *; }
-keep class com.airreceiver.crypto.** { *; }

# Keep Netty classes
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# Keep JmDNS classes
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep plist parser
-keep class com.dd.plist.** { *; }
-dontwarn com.dd.plist.**

# Keep ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Timber
-dontwarn org.jetbrains.annotations.**
