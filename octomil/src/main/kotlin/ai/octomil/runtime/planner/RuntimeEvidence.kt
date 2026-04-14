package ai.octomil.runtime.planner

/**
 * Well-known metadata keys for runtime evidence attached to [InstalledRuntime].
 *
 * When an engine has a concrete local artifact loaded (not just framework
 * availability), these keys declare which models and capabilities the engine
 * can actually serve. The planner's `supportsLocalDefault` uses these keys
 * to gate no-plan local selection -- plain engine availability is not enough.
 *
 * Keys match the cross-SDK contract so that server-side analytics can parse
 * the same metadata shape from any platform.
 */
object RuntimeEvidenceMetadataKeys {
    /** Comma-separated model IDs this runtime can serve. Use "*" for wildcard. */
    const val MODELS = "models"

    /** Comma-separated capabilities (e.g. "text", "audio_transcription", "embeddings"). */
    const val CAPABILITIES = "capabilities"

    /** Content-addressable digest of the loaded artifact (e.g. "sha256:abc123"). */
    const val ARTIFACT_DIGEST = "artifact_digest"

    /** Format of the loaded artifact (e.g. "gguf", "tflite", "onnx"). */
    const val ARTIFACT_FORMAT = "artifact_format"
}

/**
 * Create model-capable runtime evidence for a concrete local artifact.
 *
 * Unlike the generic classpath-based detection in [DeviceRuntimeProfileCollector],
 * this produces an [InstalledRuntime] that explicitly declares the model and
 * capability it can serve. The planner will select this engine for no-plan
 * local resolution without requiring a server plan or benchmark cache hit.
 *
 * Engine IDs are canonicalized via [RuntimeEngineIds.canonical].
 *
 * @param engine Raw engine identifier (aliases accepted, e.g. "llamacpp").
 * @param model Model identifier the artifact can serve.
 * @param capability The capability this artifact supports.
 * @param artifactDigest Optional content-addressable digest of the artifact.
 * @param artifactFormat Optional artifact format (e.g. "gguf", "tflite").
 * @param accelerator Optional accelerator hint (e.g. "cpu", "gpu", "npu").
 * @param version Optional engine version string.
 * @return An [InstalledRuntime] with model-capable evidence in its metadata.
 */
fun InstalledRuntime.Companion.modelCapable(
    engine: String,
    model: String,
    capability: String,
    artifactDigest: String? = null,
    artifactFormat: String? = null,
    accelerator: String? = null,
    version: String? = null,
): InstalledRuntime {
    val metadata = mutableMapOf(
        RuntimeEvidenceMetadataKeys.MODELS to model,
        RuntimeEvidenceMetadataKeys.CAPABILITIES to capability,
    )
    if (artifactDigest != null) {
        metadata[RuntimeEvidenceMetadataKeys.ARTIFACT_DIGEST] = artifactDigest
    }
    if (artifactFormat != null) {
        metadata[RuntimeEvidenceMetadataKeys.ARTIFACT_FORMAT] = artifactFormat
    }

    return InstalledRuntime(
        engine = RuntimeEngineIds.canonical(engine),
        version = version,
        available = true,
        accelerator = accelerator ?: "cpu",
        metadata = metadata,
    )
}

