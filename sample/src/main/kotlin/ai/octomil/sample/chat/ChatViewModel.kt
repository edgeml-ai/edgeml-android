package ai.octomil.sample.chat

import ai.octomil.ModelResolver
import ai.octomil.chat.GenerateConfig
import ai.octomil.chat.LLMRuntime
import ai.octomil.chat.LLMRuntimeRegistry
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel driving the chat screen.
 *
 * Lifecycle:
 * 1. Created with model name — immediately starts model preload.
 * 2. Once loaded, user can send messages.
 * 3. Streaming generation can be cancelled mid-flight.
 * 4. Runtime is closed in [onCleared].
 *
 * Uses [ModelResolver.paired] to locate the model file on disk —
 * decoupled from the pairing flow.
 */
class ChatViewModel(
    application: Application,
    private val modelName: String,
) : AndroidViewModel(application) {

    // -- State --

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private var runtime: LLMRuntime? = null
    private var generationJob: Job? = null

    init {
        preloadModel()
    }

    // -- Model loading --

    private fun preloadModel() {
        viewModelScope.launch {
            _uiState.value = ChatUiState.Loading
            try {
                val context = getApplication<Application>()
                val modelFile = ModelResolver.paired().resolve(context, modelName)
                    ?: throw IllegalStateException(
                        "Model '$modelName' not found. Run 'octomil deploy $modelName --phone' first."
                    )

                Timber.i("ChatViewModel: resolved model file %s", modelFile.absolutePath)

                val factory = LLMRuntimeRegistry.factory
                    ?: throw IllegalStateException("No LLMRuntime factory registered")

                val llmRuntime = factory.invoke(modelFile)

                // Preload the model immediately
                if (llmRuntime is LlamaCppRuntime) {
                    llmRuntime.loadModel()
                }

                runtime = llmRuntime
                _uiState.value = ChatUiState.Ready

                Timber.i("ChatViewModel: model ready")
            } catch (e: Exception) {
                Timber.e(e, "ChatViewModel: failed to load model")
                _uiState.value = ChatUiState.Error(
                    e.message ?: "Failed to load model"
                )
            }
        }
    }

    // -- Chat --

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val currentState = _uiState.value
        if (currentState != ChatUiState.Ready) return

        // Cancel any in-flight generation
        cancelGeneration()

        // Add user message
        val userMessage = ChatMessage(role = Role.USER, content = trimmed)
        _messages.value = _messages.value + userMessage

        // Build prompt with chat history
        val prompt = assemblePrompt(_messages.value)

        // Start generation
        _uiState.value = ChatUiState.Generating
        _streamingText.value = ""

        val startTimeNs = System.nanoTime()
        var firstTokenTimeNs = 0L
        var tokenCount = 0

        generationJob = viewModelScope.launch {
            try {
                val config = GenerateConfig(
                    maxTokens = 1024,
                    temperature = 0.7f,
                )

                runtime?.generate(prompt, config)?.collect { token ->
                    if (tokenCount == 0) {
                        firstTokenTimeNs = System.nanoTime()
                    }
                    tokenCount++
                    _streamingText.value += token
                }

                // Generation complete — compute metrics
                val endTimeNs = System.nanoTime()
                val metrics = computeMetrics(
                    startTimeNs = startTimeNs,
                    firstTokenTimeNs = firstTokenTimeNs,
                    endTimeNs = endTimeNs,
                    tokenCount = tokenCount,
                )

                val assistantMessage = ChatMessage(
                    role = Role.ASSISTANT,
                    content = _streamingText.value,
                    metrics = metrics,
                )
                _messages.value = _messages.value + assistantMessage
                _streamingText.value = ""
                _uiState.value = ChatUiState.Ready

            } catch (e: Exception) {
                // If cancelled, finalize partial response
                if (_streamingText.value.isNotEmpty()) {
                    val endTimeNs = System.nanoTime()
                    val metrics = computeMetrics(startTimeNs, firstTokenTimeNs, endTimeNs, tokenCount)
                    val partialMessage = ChatMessage(
                        role = Role.ASSISTANT,
                        content = _streamingText.value,
                        metrics = metrics,
                    )
                    _messages.value = _messages.value + partialMessage
                }
                _streamingText.value = ""
                _uiState.value = ChatUiState.Ready
                Timber.w(e, "ChatViewModel: generation ended")
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    // -- Prompt assembly --

    /**
     * Assemble a multi-turn prompt from chat history.
     *
     * Uses a simple turn-based format that works with most instruction-tuned
     * GGUF models (Gemma, Llama, Qwen). The llama.cpp engine applies the
     * model's chat template internally if one is embedded in the GGUF metadata.
     */
    private fun assemblePrompt(messages: List<ChatMessage>): String {
        return messages.joinToString("\n") { msg ->
            when (msg.role) {
                Role.USER -> "User: ${msg.content}"
                Role.ASSISTANT -> "Assistant: ${msg.content}"
            }
        } + "\nAssistant:"
    }

    // -- Metrics --

    private fun computeMetrics(
        startTimeNs: Long,
        firstTokenTimeNs: Long,
        endTimeNs: Long,
        tokenCount: Int,
    ): GenerationMetrics? {
        if (tokenCount == 0) return null

        val totalMs = (endTimeNs - startTimeNs) / 1_000_000.0
        val ttftMs = if (firstTokenTimeNs > 0) {
            (firstTokenTimeNs - startTimeNs) / 1_000_000.0
        } else {
            totalMs
        }

        // Decode speed: tokens after the first / time after first token
        val decodeTokens = (tokenCount - 1).coerceAtLeast(0)
        val decodeTimeMs = if (firstTokenTimeNs > 0 && decodeTokens > 0) {
            (endTimeNs - firstTokenTimeNs) / 1_000_000.0
        } else {
            0.0
        }
        val decodeTokPerSec = if (decodeTimeMs > 0) {
            decodeTokens / (decodeTimeMs / 1000.0)
        } else {
            0.0
        }

        return GenerationMetrics(
            ttftMs = ttftMs,
            decodeTokPerSec = decodeTokPerSec,
            totalMs = totalMs,
            tokenCount = tokenCount,
        )
    }

    override fun onCleared() {
        cancelGeneration()
        runtime?.close()
        runtime = null
        super.onCleared()
    }
}

// -- Data classes --

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data object Ready : ChatUiState
    data object Generating : ChatUiState
    data class Error(val message: String) : ChatUiState
}

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val role: Role,
    val content: String,
    val metrics: GenerationMetrics? = null,
)

data class GenerationMetrics(
    /** Time to first token in milliseconds. */
    val ttftMs: Double,
    /** Decode speed in tokens per second (excludes first token). */
    val decodeTokPerSec: Double,
    /** Total wall-clock time in milliseconds. */
    val totalMs: Double,
    /** Total tokens generated. */
    val tokenCount: Int,
)
