package ai.edgeml.discovery

import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * High-level manager for making an EdgeML device discoverable on the local network.
 *
 * Wraps [NsdAdvertiser] with a simpler API and manages the advertiser lifecycle.
 * Intended as the primary entry point for discovery from application code.
 *
 * ## Usage
 *
 * ```kotlin
 * val discovery = DiscoveryManager(context)
 * discovery.startDiscoverable(deviceId = "abc123")
 *
 * if (discovery.isDiscoverable) {
 *     // Device is visible to `edgeml deploy --phone`
 * }
 *
 * discovery.stopDiscoverable()
 * ```
 */
class DiscoveryManager(
    private val context: Context,
    internal val advertiser: NsdAdvertiser = NsdAdvertiser(context),
) {

    /**
     * Whether this device is currently advertising on the local network.
     */
    val isDiscoverable: Boolean
        get() = advertiser.isAdvertising

    /**
     * Start making this device discoverable via mDNS/NSD.
     *
     * The device will be advertised as `EdgeML-{deviceName}` with the given [deviceId]
     * in the TXT record so the CLI can identify it. Calling this while already
     * discoverable is a no-op.
     *
     * @param deviceId Unique identifier for this device.
     * @param deviceName Human-readable name; defaults to [Build.MODEL].
     */
    fun startDiscoverable(
        deviceId: String,
        deviceName: String = Build.MODEL,
    ) {
        Timber.i("Making device discoverable: id=%s name=%s", deviceId, deviceName)
        advertiser.startAdvertising(deviceId = deviceId, deviceName = deviceName)
    }

    /**
     * Stop advertising this device on the network.
     *
     * Calling this when not discoverable is a no-op.
     */
    fun stopDiscoverable() {
        Timber.i("Stopping device discovery")
        advertiser.stopAdvertising()
    }
}
