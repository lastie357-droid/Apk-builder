# ═══════════════════════════════════════════════════════════════════════════════
#  Heavy ProGuard / R8 protection rules
#  Obfuscates, shrinks, and optimises the release APK.
#  R8 full mode is enabled via android.enableR8.fullMode=true in gradle.properties
# ═══════════════════════════════════════════════════════════════════════════════

# ── Optimisation ─────────────────────────────────────────────────────────────
# 5 passes is the sweet spot: more passes rarely yield extra savings and can
# cause R8 to time out or produce unstable bytecode on complex apps.
-optimizationpasses 5

# Enable safe, well-tested optimisations only. The excluded ones (arithmetic
# simplification, cast simplification, field opts, class merging) have known
# edge cases with Android reflection and can break runtime behaviour.
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# ── Package flattening ────────────────────────────────────────────────────────
# -repackageclasses supersedes -flattenpackagehierarchy; only one should be set.
# Using -repackageclasses moves everything into a single flat package 'a',
# which is the stronger of the two options.
-repackageclasses 'a'
-allowaccessmodification

# ── Remove all logging ────────────────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}

# Remove System.out / err leakage
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# ── Android entry-points ──────────────────────────────────────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.view.View

# ── Parcelable ────────────────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ── Serializable ──────────────────────────────────────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── Native methods ────────────────────────────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Enum values ───────────────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Attributes required at runtime ───────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── R class ───────────────────────────────────────────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ── AndroidX ──────────────────────────────────────────────────────────────────
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# ── OkHttp / Retrofit ────────────────────────────────────────────────────────
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-dontwarn retrofit2.**

# ── Socket.IO client ─────────────────────────────────────────────────────────
-keep class io.socket.** { *; }
-keep interface io.socket.** { *; }
-dontwarn io.socket.**

# ── Gson ─────────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-dontwarn sun.misc.**
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── WorkManager ──────────────────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# ── Dexter (permissions) ─────────────────────────────────────────────────────
-keep class com.karumi.dexter.** { *; }
-dontwarn com.karumi.dexter.**

# ── Suppress common dependency warnings ──────────────────────────────────────
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ── Obfuscation dictionaries ─────────────────────────────────────────────────
-obfuscationdictionary         obf-dict.txt
-classobfuscationdictionary    obf-dict.txt
-packageobfuscationdictionary  obf-dict.txt

# ── Extra hardening ───────────────────────────────────────────────────────────
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
