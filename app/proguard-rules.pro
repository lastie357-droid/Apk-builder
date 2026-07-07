# ═══════════════════════════════════════════════════════════════════════════════
#  ProGuard / R8 rules
#  R8 full mode is enabled via android.enableR8.fullMode=true in gradle.properties
# ═══════════════════════════════════════════════════════════════════════════════

# ── Optimisation ─────────────────────────────────────────────────────────────
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# ── Package flattening ────────────────────────────────────────────────────────
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

# ── SecurityGuard / ChameleonIdentity JNI classes ────────────────────────────
# JNI symbol names are derived from the fully-qualified class name; renaming
# or repackaging these classes breaks native linkage at runtime.
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

# ── AndroidX — let R8 shrink via each library's bundled consumer rules ────────
# DO NOT add a blanket "keep class androidx.** { *; }" here — that defeats R8
# shrinking entirely (adds ~3-5 MB). Each AndroidX artifact ships its own
# consumer ProGuard rules that R8 applies automatically; only add explicit
# keeps below for classes that are loaded by name via reflection.
-dontwarn androidx.**

# WorkManager workers are instantiated by class name via reflection
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

# ── ML Kit / Play Services text recognition ───────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_latin.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

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
