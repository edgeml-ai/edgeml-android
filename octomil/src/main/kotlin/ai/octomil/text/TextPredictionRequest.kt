package ai.octomil.text

import ai.octomil.generated.ModelCapability
import ai.octomil.manifest.ModelRef

/**
 * Request for next-token text predictions.
 *
 * Specify the model either by ID ([ModelRef.Id]) or by capability
 * ([ModelRef.Capability]) — the SDK resolves capability to a model at runtime.
 *
 * ```kotlin
 * // By explicit model name:
 * TextPredictionRequest(model = ModelRef.Id("smollm2-135m"), input = "Hello")
 *
 * // By capability (resolved from AppManifest):
 * TextPredictionRequest(
 *     model = ModelRef.Capability(ModelCapability.KEYBOARD_PREDICTION),
 *     input = "Hello",
 * )
 * ```
 *
 * @property model Model reference — ID or capability.
 * @property input Context text to predict from.
 * @property n Number of filtered candidates to return.
 */
data class TextPredictionRequest(
    val model: ModelRef,
    val input: String,
    val n: Int = 3,
)
