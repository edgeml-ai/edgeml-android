package ai.octomil.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes audio files (WAV, MP3, M4A, OGG, etc.) to 16 kHz mono `FloatArray`
 * using Android's [MediaExtractor] + [MediaCodec].
 *
 * Bridges the gap between `AudioTranscriptions.create(audioFile)` and
 * `SpeechSession.feed(samples: FloatArray)`.
 */
internal object AudioFileDecoder {

    private const val TARGET_SAMPLE_RATE = 16_000

    /**
     * Decode an audio file to 16 kHz mono float samples in [-1, 1].
     *
     * @param file Audio file on disk.
     * @return Float samples ready for [ai.octomil.speech.SpeechSession.feed].
     * @throws IllegalArgumentException if the file has no audio track.
     * @throws IllegalStateException on codec errors.
     */
    suspend fun decode(file: File): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)

            val trackIndex = findAudioTrack(extractor)
            require(trackIndex >= 0) { "No audio track found in ${file.name}" }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()

                val pcm = extractPcm(codec, extractor)
                val mono = toMono(pcm, sourceChannels)
                resample(mono, sourceSampleRate, TARGET_SAMPLE_RATE)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /**
     * Feed input buffers from the extractor, drain output buffers from the codec,
     * collecting raw PCM 16-bit samples.
     */
    private fun extractPcm(codec: MediaCodec, extractor: MediaExtractor): ShortArray {
        val bufferInfo = MediaCodec.BufferInfo()
        val output = mutableListOf<Short>()
        var inputDone = false
        val timeoutUs = 10_000L

        while (true) {
            // Feed input
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(timeoutUs)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val read = extractor.readSampleData(buf, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Drain output
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outIdx >= 0) {
                val buf = codec.getOutputBuffer(outIdx)!!
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val shortBuf = buf.asShortBuffer()
                val samples = ShortArray(shortBuf.remaining())
                shortBuf.get(samples)
                for (s in samples) output.add(s)
                codec.releaseOutputBuffer(outIdx, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                // Codec stalled after EOS — done
                break
            }
        }

        return output.toShortArray()
    }

    /** Convert interleaved multi-channel PCM to mono by averaging channels. */
    private fun toMono(samples: ShortArray, channels: Int): FloatArray {
        if (channels == 1) {
            return FloatArray(samples.size) { samples[it] / 32768f }
        }
        val frameCount = samples.size / channels
        return FloatArray(frameCount) { frame ->
            var sum = 0f
            for (ch in 0 until channels) {
                sum += samples[frame * channels + ch] / 32768f
            }
            sum / channels
        }
    }

    /** Linear-interpolation resample from [srcRate] to [dstRate]. */
    private fun resample(samples: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return samples
        val ratio = srcRate.toDouble() / dstRate
        val outLen = (samples.size / ratio).toInt()
        return FloatArray(outLen) { i ->
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            val a = samples[idx]
            val b = if (idx + 1 < samples.size) samples[idx + 1] else a
            a + frac * (b - a)
        }
    }
}
