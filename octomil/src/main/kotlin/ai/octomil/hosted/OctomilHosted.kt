package ai.octomil.hosted

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Hosted Octomil client.
 *
 * Lightweight HTTP client targeting `api.octomil.com`. Distinct from
 * `Octomil` (the local-runtime facade) so hosted callers do not pay the
 * cost of importing the runtime planner / speech runtime registry.
 *
 * ```kotlin
 * val client = OctomilHosted(apiKey = System.getenv("OCTOMIL_API_KEY")!!)
 * val response = client.audio.speech.create(
 *     model = "tts-1",
 *     input = "Hello world.",
 *     voice = "alloy"
 * )
 * File("hello.mp3").writeBytes(response.audioBytes)
 * ```
 */
class OctomilHosted(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    val audio: HostedAudio = HostedAudio(baseUrl, apiKey)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.octomil.com/v1"
    }
}

/** Audio surface on [OctomilHosted]. */
class HostedAudio internal constructor(baseUrl: String, apiKey: String) {
    val speech: HostedSpeech = HostedSpeech(baseUrl, apiKey)
}

/** Result of a [HostedSpeech.create] call. */
data class HostedSpeechResponse(
    val audioBytes: ByteArray,
    val contentType: String,
    val provider: String?,
    val model: String?,
    val latencyMs: Double?,
    val billedUnits: Int?,
    val unitKind: String?,
)

class HostedSpeechException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Hosted text-to-speech surface (mirrors `openai.audio.speech`). */
class HostedSpeech internal constructor(
    private val baseUrl: String,
    private val apiKey: String,
) {
    /**
     * Synthesize speech from text. Returns the raw audio bytes plus
     * Octomil routing metadata surfaced via `X-Octomil-*` response headers.
     *
     * Blocking IO. Wrap in `withContext(Dispatchers.IO)` from a coroutine.
     */
    fun create(
        model: String,
        input: String,
        voice: String? = null,
        responseFormat: String = "mp3",
        speed: Double = 1.0,
    ): HostedSpeechResponse {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            throw HostedSpeechException("`input` must be a non-empty string.")
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("input", input)
            if (voice != null) put("voice", voice)
            put("response_format", responseFormat)
            put("speed", speed)
        }

        val url = URL(baseUrl.trimEnd('/') + "/audio/speech")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.doOutput = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000

        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status >= 400) {
                val body = (connection.errorStream ?: connection.inputStream)?.use { it.readBytes() }
                    ?: ByteArray(0)
                val preview = String(body.take(500).toByteArray(), Charsets.UTF_8)
                throw HostedSpeechException("Hosted speech request failed: HTTP $status: $preview")
            }

            val bytes = connection.inputStream.use { it.readBytes() }
            val contentType = connection.contentType ?: "application/octet-stream"
            return HostedSpeechResponse(
                audioBytes = bytes,
                contentType = contentType,
                provider = connection.getHeaderField("X-Octomil-Provider"),
                model = connection.getHeaderField("X-Octomil-Model") ?: model,
                latencyMs = connection.getHeaderField("X-Octomil-Latency-Ms")?.toDoubleOrNull(),
                billedUnits = connection.getHeaderField("X-Octomil-Billed-Units")?.toIntOrNull(),
                unitKind = connection.getHeaderField("X-Octomil-Unit-Kind"),
            )
        } catch (e: HostedSpeechException) {
            throw e
        } catch (e: Throwable) {
            throw HostedSpeechException("Transport error: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection.disconnect()
        }
    }
}
