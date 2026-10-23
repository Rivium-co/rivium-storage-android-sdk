# Consumer R8 / ProGuard rules for the RiviumStorage SDK (applied to apps automatically)

# Keep the SDK's classes: Gson reads and writes its models by reflection.
-keep class co.rivium.storage.** { *; }

# ---- Gson generic types ----
# Model fields such as `List<StorageFile>` and `Map<String, Any>`, and TypeToken, need the generic signatures; R8
# (full mode, the default since AGP 8) removes them, and release builds then get the wrong types or fail with
# "TypeToken must be created with a type argument".
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
