package ai.edgeml.tryitout

import ai.edgeml.pairing.ui.EdgeMLPairingTheme
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import timber.log.Timber

/**
 * Activity that hosts the [TryItOutScreen] composable.
 *
 * Receives model metadata via intent extras and launches the modality-aware
 * "Try it out" screen. Similar pattern to [PairingActivity].
 *
 * ## Launch from PairingActivity
 *
 * ```kotlin
 * val intent = TryItOutActivity.createIntent(
 *     context = this,
 *     modelName = "phi-4-mini",
 *     modelVersion = "1.2",
 *     sizeBytes = 2_700_000_000L,
 *     runtime = "TFLite",
 *     modality = "text",
 * )
 * startActivity(intent)
 * ```
 *
 * ## Custom InferenceRunner
 *
 * By default, the activity uses a no-op [InferenceRunner] that returns a
 * zero-filled array. To use a real interpreter, subclass this activity and
 * override [createInferenceRunner], or register a custom factory via
 * [setInferenceRunnerFactory] before the activity is created.
 */
class TryItOutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val modelInfo = extractModelInfo(intent)
            ?: run {
                Timber.e("TryItOutActivity: missing required model info in intent extras")
                finish()
                return
            }

        Timber.i(
            "TryItOutActivity: launching for model=%s version=%s modality=%s",
            modelInfo.modelName,
            modelInfo.modelVersion,
            modelInfo.modality ?: "null",
        )

        val inferenceRunner = createInferenceRunner(modelInfo)
        val factory = TryItOutViewModel.Factory(
            modelInfo = modelInfo,
            inferenceRunner = inferenceRunner,
        )

        val viewModel = ViewModelProvider(this, factory)[TryItOutViewModel::class.java]

        setContent {
            EdgeMLPairingTheme {
                TryItOutScreen(viewModel = viewModel)
            }
        }
    }

    /**
     * Create the [InferenceRunner] for this activity.
     *
     * Override in subclasses to provide a real TFLite interpreter-based runner.
     * The default implementation returns a placeholder that produces zero-filled output.
     */
    protected open fun createInferenceRunner(modelInfo: ModelInfo): InferenceRunner {
        // Check if a global factory was registered
        inferenceRunnerFactory?.let { factory ->
            return factory.create(modelInfo)
        }

        // Default: placeholder runner
        return InferenceRunner { input ->
            FloatArray(input.size)
        }
    }

    companion object {

        internal const val EXTRA_MODEL_NAME = "ai.edgeml.tryitout.EXTRA_MODEL_NAME"
        internal const val EXTRA_MODEL_VERSION = "ai.edgeml.tryitout.EXTRA_MODEL_VERSION"
        internal const val EXTRA_SIZE_BYTES = "ai.edgeml.tryitout.EXTRA_SIZE_BYTES"
        internal const val EXTRA_RUNTIME = "ai.edgeml.tryitout.EXTRA_RUNTIME"
        internal const val EXTRA_MODALITY = "ai.edgeml.tryitout.EXTRA_MODALITY"

        /**
         * Optional factory for creating [InferenceRunner] instances. Set this
         * before launching the activity to provide custom inference behavior.
         */
        @Volatile
        var inferenceRunnerFactory: InferenceRunnerFactory? = null

        /**
         * Create an intent to launch [TryItOutActivity] with model metadata.
         *
         * @param context Android context.
         * @param modelName Human-readable model name.
         * @param modelVersion Model version string.
         * @param sizeBytes Model file size in bytes.
         * @param runtime Runtime string (e.g., "TFLite", "ONNX").
         * @param modality Model modality (e.g., "text", "vision", "audio", "classification").
         */
        fun createIntent(
            context: Context,
            modelName: String,
            modelVersion: String,
            sizeBytes: Long,
            runtime: String,
            modality: String? = null,
        ): Intent = Intent(context, TryItOutActivity::class.java).apply {
            putExtra(EXTRA_MODEL_NAME, modelName)
            putExtra(EXTRA_MODEL_VERSION, modelVersion)
            putExtra(EXTRA_SIZE_BYTES, sizeBytes)
            putExtra(EXTRA_RUNTIME, runtime)
            modality?.let { putExtra(EXTRA_MODALITY, it) }
        }

        /**
         * Extract [ModelInfo] from an intent.
         *
         * @return [ModelInfo] or null if required fields are missing.
         */
        internal fun extractModelInfo(intent: Intent): ModelInfo? {
            val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: return null
            val modelVersion = intent.getStringExtra(EXTRA_MODEL_VERSION) ?: return null
            val sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, -1L)
            val runtime = intent.getStringExtra(EXTRA_RUNTIME) ?: return null
            val modality = intent.getStringExtra(EXTRA_MODALITY)

            if (sizeBytes < 0) return null

            return ModelInfo(
                modelName = modelName,
                modelVersion = modelVersion,
                sizeBytes = sizeBytes,
                runtime = runtime,
                modality = modality,
            )
        }
    }

    /**
     * Factory interface for creating [InferenceRunner] instances.
     */
    fun interface InferenceRunnerFactory {
        fun create(modelInfo: ModelInfo): InferenceRunner
    }
}
