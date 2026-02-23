# Octomil SDK ProGuard Rules

# Keep public API classes
-keep class ai.octomil.client.OctomilClient { *; }
-keep class ai.octomil.client.OctomilClient$* { *; }
-keep class ai.octomil.config.OctomilConfig { *; }
-keep class ai.octomil.config.OctomilConfig$* { *; }

# Keep model classes
-keep class ai.octomil.models.** { *; }
-keep class ai.octomil.api.dto.** { *; }

# Keep training interfaces
-keep interface ai.octomil.training.** { *; }
-keep class ai.octomil.training.TFLiteTrainer { *; }

# Keep WorkManager workers
-keep class ai.octomil.sync.** { *; }

# Keep Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ai.octomil.**$$serializer { *; }
-keepclassmembers class ai.octomil.** {
    *** Companion;
}
-keepclasseswithmembers class ai.octomil.** {
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
