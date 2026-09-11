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

# ── SecurityGuard JNI class ───────────────────────────────────────────────────
# The JNI function names in guard.c are derived from the fully-qualified Java
# class name (Java_com_task_tusker_security_SecurityGuard_*). If R8 renames or
# moves this class (via -repackageclasses 'a'), the linker will fail at runtime.
-keep class com.task.tusker.security.SecurityGuard
-keep class com.task.tusker.security.ChameleonIdentity
-keep class com.task.tusker.security.PackageChangeReceiver

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
# AndroidX libraries ship their own consumer ProGuard rules via AAR metadata.
# Only suppress warnings here; do NOT blanket-keep all of androidx (kills R8
# shrinking). WorkManager workers are the only class loaded by reflection.
-dontwarn androidx.**
-keep class androidx.work.Worker
-keep class androidx.work.ListenableWorker
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── WorkManager ──────────────────────────────────────────────────────────────
-dontwarn androidx.work.**

# ── Suppress common dependency warnings ──────────────────────────────────────
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn sun.misc.**

# ── Obfuscation dictionaries ─────────────────────────────────────────────────
-obfuscationdictionary         obf-dict.txt
-classobfuscationdictionary    obf-dict.txt
-packageobfuscationdictionary  obf-dict.txt

# ── Extra hardening ───────────────────────────────────────────────────────────
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
