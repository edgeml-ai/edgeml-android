package ai.octomil.manifest

import ai.octomil.generated.ArtifactResourceKind
import ai.octomil.generated.DeliveryMode
import ai.octomil.generated.Modality
import ai.octomil.generated.ModelCapability
import ai.octomil.generated.RoutingPolicy

/**
 * Declarative manifest describing the models an app needs.
 *
 * The manifest captures **what** the app requires (capabilities, delivery mode,
 * routing policy, modalities) without encoding **how** the SDK satisfies those
 * requirements. Engine selection (GGUF, TFLite, llama.cpp, etc.) is a runtime
 * concern handled by [ModelCatalogService] and
 * [ai.octomil.runtime.core.ModelRuntimeRegistry].
 *
 * ```kotlin
 * val manifest = AppManifest(
 *     models = listOf(
 *         AppModelEntry(
 *             id = "phi-4-mini",
 *             capability = ModelCapability.CHAT,
 *             delivery = DeliveryMode.MANAGED,
 *             inputModalities = listOf(Modality.TEXT),
 *             outputModalities = listOf(Modality.TEXT),
 *             resources = listOf(
 *                 ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://phi-4-mini.gguf"),
 *             ),
 *             routingPolicy = RoutingPolicy.LOCAL_FIRST,
 *         ),
 *         AppModelEntry(
 *             id = "smolvlm2",
 *             capability = ModelCapability.CHAT,
 *             delivery = DeliveryMode.MANAGED,
 *             inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
 *             outputModalities = listOf(Modality.TEXT),
 *             resources = listOf(
 *                 ManifestResource(kind = ArtifactResourceKind.WEIGHTS, uri = "hf://smolvlm2.gguf"),
 *                 ManifestResource(kind = ArtifactResourceKind.PROJECTOR, uri = "hf://smolvlm2-mmproj.gguf"),
 *             ),
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
 * @property inputModalities Data modalities this model accepts as input. A VL
 *   model has `[TEXT, IMAGE]`. A text-only model has `[TEXT]`. Required.
 * @property outputModalities Data modalities this model produces as output. A
 *   generative text model has `[TEXT]`. A TTS model has `[AUDIO]`. Required.
 * @property resources Artifact resources in this package (weights, projector,
 *   tokenizer, etc.). Order matters — resources are loaded in list order.
 * @property engineConfig Executor-specific runtime hints. Shape is
 *   executor-defined. Example for llamacpp: `{n_gpu_layers=99, ctx_size=4096}`.
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
    val inputModalities: List<Modality>,
    val outputModalities: List<Modality>,
    val resources: List<ManifestResource> = emptyList(),
    val engineConfig: Map<String, String> = emptyMap(),
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

    /** Whether this model accepts image input (vision-language model). */
    val isVisionModel: Boolean
        get() = Modality.IMAGE in inputModalities

    /** Whether this model accepts audio input. */
    val isAudioModel: Boolean
        get() = Modality.AUDIO in inputModalities

    /** Whether this model is multimodal (accepts more than just text). */
    val isMultimodal: Boolean
        get() = inputModalities.size > 1 || inputModalities.any { it != Modality.TEXT }

    /** Look up the first resource of a given kind, or null. */
    fun resourceForKind(kind: ArtifactResourceKind): ManifestResource? =
        resources.firstOrNull { it.kind == kind }

    /** Look up all resources of a given kind. */
    fun resourcesForKind(kind: ArtifactResourceKind): List<ManifestResource> =
        resources.filter { it.kind == kind }
}

/**
 * A resource within a manifest package, binding an [ArtifactResourceKind]
 * to a URI and optional local path.
 *
 * @property kind The type of resource (weights, projector, tokenizer, etc.).
 * @property uri Download URI (s3://, hf://, https://, file://).
 * @property path Relative path within the extracted package on disk.
 * @property sizeBytes File size in bytes.
 * @property checksumSha256 SHA-256 checksum for integrity verification.
 * @property required Whether this resource must be present for the package to function.
 * @property loadOrder Ordering hint for loading resources within a package.
 */
data class ManifestResource(
    val kind: ArtifactResourceKind,
    val uri: String,
    val path: String? = null,
    val sizeBytes: Long? = null,
    val checksumSha256: String? = null,
    val required: Boolean = true,
    val loadOrder: Int? = null,
)

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
