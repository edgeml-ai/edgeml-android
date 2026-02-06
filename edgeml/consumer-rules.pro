# Consumer ProGuard rules for EdgeML SDK
# These rules are applied to apps that use this library

-keep class ai.edgeml.client.EdgeMLClient { *; }
-keep class ai.edgeml.config.EdgeMLConfig { *; }
-keep class ai.edgeml.models.** { *; }
-keep class ai.edgeml.training.** { *; }
