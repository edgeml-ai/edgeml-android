package ai.edgeml.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

/**
 * A deployed model that automatically adapts its compute delegate and threading
 * based on device state (battery, thermal, memory, power-save mode).
 *
 * Wraps [AdaptiveInterpreter] and [DeviceStateMonitor] to provide seamless
 * runtime adaptation. When device conditions change (e.g. battery drops below
 * 10%, device overheats), the interpreter is reloaded with the appropriate
 * delegate.
 *
 * ## Usage
 * ```kotlin
 * val monitor = DeviceStateMonitor(context)
 * monitor.startMonitoring()
 *
 * val model = AdaptiveDeployedModel(modelFile, context, monitor)
 * model.load()
 * model.startAdaptationLoop(lifecycleScope)
 *
 * val output = ByteBuffer.allocateDirect(outputSize)
 * model.predict(inputBuffer, output)
 *
 * model.close()
 * monitor.stopMonitoring()
 * ```
 */
class AdaptiveDeployedModel(
    private val modelFile: File,
    private val context: Context,
    private val stateMonitor: DeviceStateMonitor,
) {
    private val interpreter = AdaptiveInterpreter(modelFile, context)
    private var adaptationJob: Job? = null
    private var currentRecommendation: RuntimeAdapter.ComputeRecommendation? = null

    /** Delegate currently in use by the interpreter. */
    val activeDelegate: String
        get() = interpreter.activeDelegate

    /** Whether inference is currently being throttled due to device conditions. */
    val isThrottled: Boolean
        get() = currentRecommendation?.shouldThrottle ?: false

    /** Current compute recommendation, or null if not yet loaded. */
    val recommendation: RuntimeAdapter.ComputeRecommendation?
        get() = currentRecommendation

    /**
     * Load the model with the delegate recommended for current device state.
     *
     * @return [AdaptiveInterpreter.LoadResult] from the initial load.
     */
    suspend fun load(): AdaptiveInterpreter.LoadResult {
        val state = stateMonitor.deviceState.value
        val rec = RuntimeAdapter.recommend(state)
        currentRecommendation = rec
        Timber.d(
            "Initial load: delegate=%s reason=%s",
            rec.preferredDelegate, rec.reason,
        )
        return interpreter.load(rec.preferredDelegate)
    }

    /**
     * Run inference with the currently loaded interpreter.
     *
     * @param input Input [ByteBuffer].
     * @param output Output [ByteBuffer].
     */
    fun predict(input: ByteBuffer, output: ByteBuffer) {
        interpreter.run(input, output)
    }

    /**
     * Start an adaptation loop that watches [DeviceStateMonitor.deviceState]
     * and reloads the interpreter when the recommended delegate changes.
     *
     * The loop uses [distinctUntilChanged] on the recommended delegate to avoid
     * unnecessary reloads. Only delegate changes trigger a reload — thread count
     * and throttle changes are picked up without reloading.
     *
     * @param scope [CoroutineScope] to launch the adaptation coroutine in.
     *   Typically a lifecycle scope so the loop is cancelled automatically.
     */
    fun startAdaptationLoop(scope: CoroutineScope) {
        adaptationJob?.cancel()
        adaptationJob = scope.launch {
            stateMonitor.deviceState
                .map { state -> RuntimeAdapter.recommend(state) }
                .distinctUntilChanged { old, new -> old.preferredDelegate == new.preferredDelegate }
                .collect { newRec ->
                    val oldDelegate = currentRecommendation?.preferredDelegate
                    currentRecommendation = newRec

                    // Only reload if the delegate actually changed and we have an active interpreter
                    if (oldDelegate != null && oldDelegate != newRec.preferredDelegate) {
                        Timber.i(
                            "Adapting delegate: %s -> %s reason=%s",
                            oldDelegate, newRec.preferredDelegate, newRec.reason,
                        )
                        try {
                            interpreter.reload(newRec.preferredDelegate)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to reload interpreter during adaptation")
                        }
                    }
                }
        }
    }

    /**
     * Stop the adaptation loop.
     */
    fun stopAdaptationLoop() {
        adaptationJob?.cancel()
        adaptationJob = null
    }

    /**
     * Release all resources: interpreter, adaptation loop.
     */
    fun close() {
        stopAdaptationLoop()
        interpreter.close()
        currentRecommendation = null
    }
}
