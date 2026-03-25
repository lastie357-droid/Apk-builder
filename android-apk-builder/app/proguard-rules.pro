# Add project specific ProGuard rules here.
-keep class io.socket.** { *; }
-keep class com.google.gson.** { *; }
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn io.socket.**
