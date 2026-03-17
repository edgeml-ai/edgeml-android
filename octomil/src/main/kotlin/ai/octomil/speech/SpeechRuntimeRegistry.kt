package ai.octomil.speech

import java.io.File

internal object SpeechRuntimeRegistry {
    var factory: ((modelDir: File) -> SpeechRuntime)? = null
}
