# ═══════════════════════════════════════════════════════════════════════════════
#  R8 / ProGuard protection rules
#  Paired with the smali_obfuscate_apk() pass in build.sh, which renames every
#  class/method name that R8 was forced to keep due to aapt2-generated rules.
# ═══════════════════════════════════════════════════════════════════════════════

# ── Optimisation ─────────────────────────────────────────────────────────────
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# ── Package flattening ────────────────────────────────────────────────────────
-repackageclasses 'a'
-allowaccessmodification

# ── Rename source file attributes → opaque token ─────────────────────────────
# Replaces "DataSyncService.java" in stack traces with "SourceFile".
-renamesourcefileattribute SourceFile

# ── Strip debug / reflection-leaking attributes ───────────────────────────────
# DO NOT keep Signature, InnerClasses, EnclosingMethod — those attributes expose
# the full class hierarchy, generic type parameters, and anonymous-class
# relationships to any static-analysis tool (jadx, MobSF, Ghidra).
# DO NOT keep LineNumberTable / LocalVariableTable — method structure leakage.
# Keep *Annotation* only (required for @WorkerThread, @NonNull, etc. at runtime).
# Keep Exceptions so checked-exception declarations are preserved.
-keepattributes *Annotation*
-keepattributes Exceptions

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
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# ── Android entry-points ──────────────────────────────────────────────────────
# IMPORTANT: use -keepclassmembers NOT -keep so R8 is free to rename the CLASS
# itself. Aapt2 auto-generates "-keep class com.task.tusker.DataSyncService"
# for every manifest-declared component; the smali_obfuscate_apk() post-build
# pass in build.sh renames those leftover names and patches the binary
# AndroidManifest.xml to match. Without that second pass, the names R8 was
# forced to keep are still readable in jadx / MobSF / apktool.
-keepclassmembers public class * extends android.app.Activity { *; }
-keepclassmembers public class * extends android.app.Application { *; }
-keepclassmembers public class * extends android.app.Service { *; }
-keepclassmembers public class * extends android.content.BroadcastReceiver { *; }
-keepclassmembers public class * extends android.content.ContentProvider { *; }
-keepclassmembers public class * extends android.accessibilityservice.AccessibilityService { *; }
-keepclassmembers public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}
-keepclassmembers public class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

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

# ── R class ───────────────────────────────────────────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ── AndroidX — targeted keeps only ───────────────────────────────────────────
# The old blanket "-keep class androidx.** { *; }" exposed the entire AndroidX
# namespace to static analysis and prevented R8 from renaming any class that
# transitively referenced AndroidX. Keep only what is actually loaded by
# reflection or class-name lookup at runtime.
-keepclassmembers class androidx.core.app.NotificationCompat { *; }
-keepclassmembers class androidx.core.app.NotificationCompat$Builder { *; }
-keepclassmembers class androidx.work.WorkManager { *; }
-keepclassmembers class androidx.work.PeriodicWorkRequest$Builder { *; }
-keepclassmembers class androidx.work.Constraints$Builder { *; }
-keepclassmembers class androidx.work.impl.** { *; }
-dontwarn androidx.**

# ── OkHttp / Retrofit ────────────────────────────────────────────────────────
# Drop blanket -keep so R8 can rename OkHttp/Retrofit internals.
# Keep only the public surface Retrofit accesses via reflection.
-keepclassmembers class okhttp3.OkHttpClient { *; }
-keepclassmembers class okhttp3.OkHttpClient$Builder { *; }
-keepclassmembers class okhttp3.Request { *; }
-keepclassmembers class okhttp3.Request$Builder { *; }
-keepclassmembers class okhttp3.Response { *; }
-keepclassmembers class retrofit2.Retrofit { *; }
-keepclassmembers class retrofit2.Retrofit$Builder { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# ── Socket.IO client ─────────────────────────────────────────────────────────
# Socket.IO uses reflection to dispatch listener callbacks; keep public members.
-keepclassmembers class io.socket.** { public *; }
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

# ── Dexter (permissions) ─────────────────────────────────────────────────────
-keepclassmembers class com.karumi.dexter.** { *; }
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
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
# Mixed-case names are intentionally ALLOWED (not -dontusemixedcaseclassnames):
# they maximise entropy in the obfuscated namespace.
