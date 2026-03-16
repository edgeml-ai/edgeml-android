package ai.octomil.audio

import ai.octomil.manifest.ModelCatalogService

/**
 * Audio API surface — accessed via `Octomil.audio`.
 *
 * ```kotlin
 * // Transcribe an audio file
 * val result = Octomil.audio.transcriptions.create(audioFile)
 * println(result.text)
 * ```
 */
class OctomilAudio internal constructor(
    catalogProvider: () -> ModelCatalogService?,
) {
    /** Audio transcription (speech-to-text). */
    val transcriptions: AudioTranscriptions = AudioTranscriptions(catalogProvider)
}
