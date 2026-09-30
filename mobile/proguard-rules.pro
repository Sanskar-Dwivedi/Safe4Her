# Release shrinking rules for the phone app.

# Installing the watch app over Wi-Fi: libadb reaches into its own classes and the TLS provider by reflection
# (and WatchInstall reads AdbStream.mPendingClose), and the SPAKE2 pairing code is called from native code.
-keep class io.github.muntashirakon.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
# BouncyCastle builds the phone's adb certificate; its provider is wired up by class name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Optional logging and platform classes referenced by Ktor, Supabase and osmdroid but not used on Android.
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.osmdroid.**

# Crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
