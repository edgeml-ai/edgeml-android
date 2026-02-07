# EdgeML SDK ProGuard Rules

# Keep public API classes
-keep class ai.edgeml.client.EdgeMLClient { *; }
-keep class ai.edgeml.client.EdgeMLClient$* { *; }
-keep class ai.edgeml.config.EdgeMLConfig { *; }
-keep class ai.edgeml.config.EdgeMLConfig$* { *; }

# Keep model classes
-keep class ai.edgeml.models.** { *; }
-keep class ai.edgeml.api.dto.** { *; }

# Keep training interfaces
-keep interface ai.edgeml.training.** { *; }
-keep class ai.edgeml.training.TFLiteTrainer { *; }

# Keep WorkManager workers
-keep class ai.edgeml.sync.** { *; }

# Keep Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ai.edgeml.**$$serializer { *; }
-keepclassmembers class ai.edgeml.** {
    *** Companion;
}
-keepclasseswithmembers class ai.edgeml.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Retrofit
-keepattributes Signature, Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
