package ai.octomil.chat

import ai.octomil.responses.ContentPart

/**
 * Converts multimodal content parts into a text prompt for the current runtime.
 *
 * Implementations handle runtime-specific conversion strategies:
 * - Text-only runtimes: extract/describe media, inject as text context
 * - Vision-capable runtimes: format parts per model's multimodal API
 *
 * Augmentation output is runtime-only -- never stored in [ThreadMessage].
 */
interface MultimodalAdapter {
    /** Convert multimodal content parts into a prompt string for inference. */
    suspend fun preparePrompt(parts: List<ContentPart>): String

    /** Input modalities this adapter can handle. */
    val supportedModalities: Set<String>
}
