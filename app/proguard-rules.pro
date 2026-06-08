# Wear OS ProGuard rules — W22-FIX: Optimized rules to reduce APK size

# Keep the main application class
-keep class com.galaxy.wear.GalaxyWearApplication { *; }

# W22-FIX: Only keep used OkHttp classes (not entire library)
-dontwarn okhttp3.**
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.OkHttpClient$Builder { *; }
-keep class okhttp3.CertificatePinner { *; }
-keep class okhttp3.CertificatePinner$Builder { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Response { *; }

# Keep Ktor client engine interfaces
-dontwarn io.ktor.**

# W22-FIX: Use @Keep annotation approach for serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    <fields>;
    <init>(...);
}

# W22-FIX: Precise keep rules for AIP message types
-keep @kotlinx.serialization.Serializable class com.galaxy.wear.data.AIPMessage { *; }
-keep @kotlinx.serialization.Serializable class com.galaxy.wear.data.AIPMessage$* { *; }
-keep class com.galaxy.wear.data.AIPConnectionState { *; }

# General
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
