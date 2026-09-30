# Release shrinking rules for the watch app.

# Voice SOS: Vosk calls into its native library through JNA, which binds Java methods and structures by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Optional platform classes referenced by OkHttp but not used on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
