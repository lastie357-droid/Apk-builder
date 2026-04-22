-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-obfuscationdictionary ../app/obf-dict.txt
-classobfuscationdictionary ../app/obf-dict.txt
-packageobfuscationdictionary ../app/obf-dict.txt
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
-keep public class com.installer.drop.MainActivity { public <init>(); }
-keep class com.installer.drop.BuildConfig { *; }

# zip4j — needs reflection-safe internals
-keep class net.lingala.zip4j.** { *; }
-dontwarn net.lingala.zip4j.**
