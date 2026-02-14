package ai.edgeml.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Monitors network connectivity using ConnectivityManager.NetworkCallback (API 24+).
 *
 * Exposes reactive [StateFlow] properties for connectivity state and provides
 * a suspending [waitForConnectivity] helper that blocks until the network is
 * available or a timeout elapses.
 *
 * ## Usage
 *
 * ```kotlin
 * val monitor = NetworkMonitor.getInstance(context)
 *
 * // Observe connectivity
 * monitor.isConnected.collect { connected -> ... }
 *
 * // Wait up to 10 s for connectivity
 * val available = monitor.waitForConnectivity(timeoutMs = 10_000L)
 * ```
 */
class NetworkMonitor private constructor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private val _isConnected = MutableStateFlow(false)
    /** Whether any network with internet capability is available. */
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isOnWiFi = MutableStateFlow(false)
    /** Whether the device is connected via WiFi. */
    val isOnWiFi: StateFlow<Boolean> = _isOnWiFi.asStateFlow()

    private val _isOnCellular = MutableStateFlow(false)
    /** Whether the device is connected via cellular. */
    val isOnCellular: StateFlow<Boolean> = _isOnCellular.asStateFlow()

    private val _isMetered = MutableStateFlow(true)
    /** Whether the current connection is metered. */
    val isMetered: StateFlow<Boolean> = _isMetered.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Timber.d("Network available")
            updateState()
        }

        override fun onLost(network: Network) {
            Timber.d("Network lost")
            updateState()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            updateState()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        updateState()
    }

    private fun updateState() {
        val network = connectivityManager.activeNetwork
        val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }

        _isConnected.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _isOnWiFi.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        _isOnCellular.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        _isMetered.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }

    /**
     * Suspends until network connectivity is available or [timeoutMs] elapses.
     *
     * @param timeoutMs Maximum time to wait in milliseconds.
     * @return `true` if network became available within the timeout, `false` otherwise.
     */
    suspend fun waitForConnectivity(timeoutMs: Long): Boolean {
        if (_isConnected.value) return true

        return withTimeoutOrNull(timeoutMs) {
            _isConnected.first { it }
            true
        } ?: false
    }

    /**
     * Unregisters the network callback. Call when the monitor is no longer needed.
     */
    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Timber.d("NetworkMonitor stopped")
        } catch (e: Exception) {
            Timber.w(e, "Failed to unregister network callback")
        }
    }

    companion object {
        @Volatile
        private var instance: NetworkMonitor? = null

        /**
         * Get the singleton [NetworkMonitor] instance.
         *
         * @param context Application or activity context (application context is used internally).
         */
        fun getInstance(context: Context): NetworkMonitor =
            instance ?: synchronized(this) {
                instance ?: NetworkMonitor(context.applicationContext).also {
                    instance = it
                }
            }
    }
}
