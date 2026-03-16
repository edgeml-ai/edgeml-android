package ai.octomil.manifest

import ai.octomil.generated.ModelCapability
import ai.octomil.generated.DeliveryMode
import ai.octomil.generated.RoutingPolicy

/**
 * Declarative manifest describing the models an app needs.
 *
 * The manifest captures **what** the app requires (capabilities, delivery mode,
 * routing policy) without encoding **how** the SDK satisfies those requirements.
 * Engine selection (GGUF, TFLite, llama.cpp, etc.) is a runtime concern handled
 * by [ModelCatalogService] and [ai.octomil.runtime.core.ModelRuntimeRegistry].
 *
 * ```kotlin
 * val manifest = AppManifest(
 *     models = listOf(
 *         AppModelEntry(
 *             id = "phi-4-mini",
 *             capability = ModelCapability.CHAT,
 *             delivery = DeliveryMode.MANAGED,
 *             routingPolicy = RoutingPolicy.LOCAL_FIRST,
 *         ),
 *         AppModelEntry(
 *             id = "whisper-small",
 *             capability = ModelCapability.TRANSCRIPTION,
 *             delivery = DeliveryMode.BUNDLED,
 *             bundledPath = "whisper-small.bin",
 *         ),
 *     ),
 * )
 * Octomil.configure(context, manifest)
 * ```
 */
data class AppManifest(
    val models: List<AppModelEntry> = emptyList(),
) {
    /** Find the first entry matching a given capability, or null. */
    fun entryFor(capability: ModelCapability): AppModelEntry? =
        models.firstOrNull { it.capability == capability }

    /** Find all entries matching a given capability. */
    fun entriesFor(capability: ModelCapability): List<AppModelEntry> =
        models.filter { it.capability == capability }
}

/**
 * A single model declaration inside an [AppManifest].
 *
 * @property id Logical model identifier (e.g. "phi-4-mini", "whisper-small").
 * @property capability The capability this model provides.
 * @property delivery How the model binary is obtained.
 * @property routingPolicy Optional routing policy override. When null,
 *   [effectiveRoutingPolicy] infers one from [delivery].
 * @property bundledPath Asset path for [DeliveryMode.BUNDLED] models.
 * @property required When true, [ModelCatalogService.bootstrap] will throw
 *   if this model cannot be made ready.
 */
data class AppModelEntry(
    val id: String,
    val capability: ModelCapability,
    val delivery: DeliveryMode,
    val routingPolicy: RoutingPolicy? = null,
    val bundledPath: String? = null,
    val required: Boolean = false,
) {
    /**
     * Effective routing policy, inferred from [delivery] when [routingPolicy] is null.
     *
     * - BUNDLED -> LOCAL_ONLY (the model is always on-device)
     * - MANAGED -> LOCAL_FIRST (download then run locally, cloud fallback)
     * - CLOUD -> CLOUD_ONLY
     */
    val effectiveRoutingPolicy: RoutingPolicy
        get() = routingPolicy ?: when (delivery) {
            DeliveryMode.BUNDLED -> RoutingPolicy.LOCAL_ONLY
            DeliveryMode.MANAGED -> RoutingPolicy.LOCAL_FIRST
            DeliveryMode.CLOUD -> RoutingPolicy.CLOUD_ONLY
        }
}

/**
 * Type-safe reference to a model — either by direct ID or by capability.
 *
 * Used in request types so callers can say "give me the chat model" without
 * knowing which concrete model ID is configured.
 */
sealed class ModelRef {
    data class Id(val value: String) : ModelRef()
    data class Capability(val value: ModelCapability) : ModelRef()
}
