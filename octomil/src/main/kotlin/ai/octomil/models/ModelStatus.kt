package ai.octomil.models

/**
 * Status of a model in the local cache / runtime.
 *
 * Maps to the SDK Facade Contract `models.status()` return type.
 */
enum class ModelStatus {
    /** Model is not present in the local cache. */
    NOT_CACHED,

    /** Model download is queued. */
    QUEUED,

    /** Model is currently being downloaded. */
    DOWNLOADING,

    /** Model is cached locally and ready for inference. */
    READY,

    /** An error occurred during download or verification. */
    FAILED,
}
