# ProGuard settings for tools/build-apk.sh (the Gradle build uses R8 with app/proguard-rules.pro).
# Mirrors proguard-android-optimize.txt for what matters here.
-android
-optimizationpasses 3
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-allowaccessmodification
-repackageclasses ''
-keep public class * extends android.app.Activity
-dontnote **
-dontwarn android.**
# Keep newer-than-minSdk API calls out of the hot classes (no inlining of Compat), so those
# classes still verify (and get compiled) cleanly on old runtimes.
-keep,allowobfuscation class io.github.plpp555.zerolauncher.Compat { *; }
