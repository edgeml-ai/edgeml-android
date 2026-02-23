# Consumer ProGuard rules for Octomil SDK
# These rules are applied to apps that use this library

-keep class ai.octomil.client.OctomilClient { *; }
-keep class ai.octomil.config.OctomilConfig { *; }
-keep class ai.octomil.models.** { *; }
-keep class ai.octomil.training.** { *; }
