package ai.octomil.chat

import ai.octomil.generated.Modality
import ai.octomil.responses.ContentPart

/**
 * EXPERIMENTAL: Temporary bridge for text-only LLM runtimes.
 *
 * Classifies images via a provided classifier function, injects labels as text context.
 * NOT a true multimodal understanding path -- bootstrap only.
 *
 * IMPORTANT: Augmentation is runtime-only. Stored ThreadMessage retains
 * original user text + original image reference unmodified.
 *
 * @param classifyImage function that takes base64 image data and returns top-K labels
 */
@RequiresOptIn(message = "Classifier fallback is experimental and will be replaced by real VLM support.")
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalClassifierApi

@ExperimentalClassifierApi
class ClassifierFallbackAdapter(
    private val classifyImage: suspend (data: String) -> List<Pair<String, Float>>,
) : MultimodalAdapter {

    override val supportedModalities: Set<Modality> = setOf(Modality.TEXT, Modality.IMAGE)

    override suspend fun preparePrompt(parts: List<ContentPart>): String {
        val segments = mutableListOf<String>()

        for (part in parts) {
            when (part) {
                is ContentPart.Text -> segments.add(part.text)
                is ContentPart.Image -> {
                    val imageData = part.data
                    if (imageData != null) {
                        val labels = classifyImage(imageData)
                        if (labels.isNotEmpty()) {
                            val labelText = labels.joinToString(", ") { (label, conf) ->
                                "$label (${(conf * 100).toInt()}%)"
                            }
                            segments.add("[Image context: $labelText]")
                        } else {
                            segments.add("[Image attached — no classification available]")
                        }
                    } else {
                        segments.add("[Image attached — data not available for local classification]")
                    }
                }
                is ContentPart.Audio -> segments.add("[Audio attached — not supported by this adapter]")
                is ContentPart.Video -> segments.add("[Video attached — not supported by this adapter]")
                is ContentPart.File -> {} // ignored in chat context
            }
        }

        return segments.joinToString("\n")
    }
}
