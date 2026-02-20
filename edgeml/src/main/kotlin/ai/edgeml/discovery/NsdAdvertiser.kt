package ai.edgeml.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import timber.log.Timber
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Advertises an EdgeML device on the local network via Android's NSD (Network Service Discovery).
 *
 * The CLI (`edgeml deploy --phone`) scans for `_edgeml._tcp.` services on the local network
 * to discover devices before falling back to QR code pairing. This class registers the
 * device as an NSD service so the CLI can find it.
 *
 * ## Usage
 *
 * ```kotlin
 * val advertiser = NsdAdvertiser(context)
 * advertiser.startAdvertising(deviceId = "abc123")
 * // ...
 * advertiser.stopAdvertising()
 * ```
 *
 * ## Thread Safety
 *
 * All public methods are thread-safe. Start/stop are idempotent.
 */
class NsdAdvertiser(private val context: Context) {

    companion object {
        /** Bonjour/mDNS service type for EdgeML device discovery. */
        const val SERVICE_TYPE = "_edgeml._tcp."

        /** Prefix for the advertised service name. */
        const val SERVICE_NAME_PREFIX = "EdgeML-"

        /** TXT record key for the device identifier. */
        const val TXT_KEY_DEVICE_ID = "device_id"

        /** TXT record key for the platform identifier. */
        const val TXT_KEY_PLATFORM = "platform"

        /** TXT record value for the platform. */
        const val TXT_VALUE_PLATFORM = "android"

        /** TXT record key for the human-readable device name. */
        const val TXT_KEY_DEVICE_NAME = "device_name"
    }

    private var nsdManager: NsdManager? = null

    private val registered = AtomicBoolean(false)
    private val registering = AtomicBoolean(false)
    private val allocatedPort = AtomicInteger(0)

    /**
     * Whether the service is currently advertised on the network.
     *
     * Returns `true` after a successful [NsdManager.RegistrationListener.onServiceRegistered]
     * callback and `false` after [stopAdvertising] completes.
     */
    val isAdvertising: Boolean
        get() = registered.get()

    // Stored for unregistration
    private var registrationListener: NsdManager.RegistrationListener? = null

    /**
     * Start advertising this device on the local network.
     *
     * Allocates a random available TCP port (NSD requires a real port), builds the
     * [NsdServiceInfo] with the given metadata, and registers the service. Calling
     * this method while already advertising is a no-op.
     *
     * @param deviceId Unique identifier for this device.
     * @param deviceName Human-readable name; defaults to [Build.MODEL].
     * @param port TCP port to advertise. Pass 0 (default) to auto-allocate.
     */
    @Synchronized
    fun startAdvertising(
        deviceId: String,
        deviceName: String = Build.MODEL,
        port: Int = 0,
    ) {
        if (registered.get() || registering.get()) {
            Timber.d("Already advertising or registration in progress; ignoring start call")
            return
        }

        registering.set(true)

        val servicePort = if (port == 0) allocatePort() else port
        if (servicePort <= 0) {
            Timber.e("Failed to allocate port for NSD advertising")
            registering.set(false)
            return
        }
        allocatedPort.set(servicePort)

        val serviceInfo = buildServiceInfo(deviceId, deviceName, servicePort)

        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            Timber.e("NsdManager not available on this device")
            registering.set(false)
            return
        }
        nsdManager = manager

        val listener = createRegistrationListener()
        registrationListener = listener

        Timber.i(
            "Registering NSD service: name=%s type=%s port=%d",
            serviceInfo.serviceName, serviceInfo.serviceType, servicePort,
        )

        manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /**
     * Stop advertising this device.
     *
     * Unregisters the NSD service. Calling this method when not advertising is a no-op.
     */
    @Synchronized
    fun stopAdvertising() {
        if (!registered.get() && !registering.get()) {
            Timber.d("Not advertising; ignoring stop call")
            return
        }

        val manager = nsdManager
        val listener = registrationListener

        if (manager != null && listener != null) {
            Timber.i("Unregistering NSD service")
            try {
                manager.unregisterService(listener)
            } catch (e: IllegalArgumentException) {
                // Listener was not registered or already unregistered
                Timber.w(e, "Failed to unregister NSD service (already unregistered?)")
                registered.set(false)
                registering.set(false)
            }
        } else {
            registered.set(false)
            registering.set(false)
        }

        registrationListener = null
        nsdManager = null
        allocatedPort.set(0)
    }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Build the [NsdServiceInfo] with service type, name, port, and TXT records.
     */
    internal fun buildServiceInfo(
        deviceId: String,
        deviceName: String,
        port: Int,
    ): NsdServiceInfo {
        return NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX$deviceName"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(TXT_KEY_DEVICE_ID, deviceId)
            setAttribute(TXT_KEY_PLATFORM, TXT_VALUE_PLATFORM)
            setAttribute(TXT_KEY_DEVICE_NAME, deviceName)
        }
    }

    /**
     * Allocate a random available TCP port by binding a [ServerSocket] to port 0.
     *
     * The socket is closed immediately, but the port number is retained for the NSD
     * registration. There is a small TOCTOU window where another process could grab the
     * port, but this is acceptable for discovery purposes (the port is informational).
     *
     * @return Allocated port number, or -1 on failure.
     */
    internal fun allocatePort(): Int {
        return try {
            ServerSocket(0).use { socket ->
                socket.localPort
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to allocate port for NSD")
            -1
        }
    }

    /**
     * Create the [NsdManager.RegistrationListener] that updates internal state.
     */
    private fun createRegistrationListener(): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Timber.i("NSD service registered: %s", serviceInfo.serviceName)
                registered.set(true)
                registering.set(false)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD registration failed: errorCode=%d", errorCode)
                registered.set(false)
                registering.set(false)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Timber.i("NSD service unregistered: %s", serviceInfo.serviceName)
                registered.set(false)
                registering.set(false)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD unregistration failed: errorCode=%d", errorCode)
                // Still mark as not registered since we initiated the stop
                registered.set(false)
                registering.set(false)
            }
        }
    }
}
