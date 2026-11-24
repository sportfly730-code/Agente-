# Codex Agent — regras ProGuard (minify desativado por padrão; regras de segurança mesmo assim)

-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontwarn org.jetbrains.annotations.**

# kotlinx.serialization
-keep,includedescriptorclasses class com.codex.agent.**$$serializer { *; }
-keepclassmembers class com.codex.agent.** { *** Companion; }
-keepclasseswithmembers class com.codex.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Shizuku
-keep class moe.shizuku.** { *; }
-keep class rikka.** { *; }

# Coil
-dontwarn coil.**
