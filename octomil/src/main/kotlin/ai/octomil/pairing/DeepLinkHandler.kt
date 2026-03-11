package ai.octomil.pairing

import android.content.Intent
import android.net.Uri
import timber.log.Timber

/**
 * Handles deep links for the Octomil SDK.
 *
 * Supports both the `octomil://` custom scheme and `https://octomil.com` App Links
 * (Universal Links on iOS) for QR code pairing from `octomil deploy --phone`.
 *
 * ## Supported deep link formats
 *
 * ```
 * octomil://pair?token=<pairing-code>&host=<server-url>
 * https://octomil.com/pair?token=<pairing-code>&host=<server-url>
 * ```
 *
 * - **token** (required): The pairing code that identifies the session.
 * - **host** (optional): The Octomil server URL. Defaults to `https://api.octomil.com`
 *   when omitted.
 */
object DeepLinkHandler {

    /** The custom URL scheme used by Octomil deep links. */
    const val SCHEME = "octomil"

    /** The host used for App Links (https://octomil.com/pair). */
    const val APP_LINK_HOST = "octomil.com"

    /** The path for pairing App Links. */
    const val APP_LINK_PATH = "/pair"

    /** The default Octomil server URL used when the deep link omits the host parameter. */
    const val DEFAULT_HOST = "https://api.octomil.com"

    /**
     * Actions that can be parsed from an `octomil://` deep link.
     */
    sealed class DeepLinkAction {
        /**
         * A pairing action initiated by scanning a QR code from `octomil deploy --phone`.
         *
         * @param token The pairing code that identifies the session on the server.
         * @param host The Octomil server URL, or [DEFAULT_HOST] if not specified in the link.
         */
        data class Pair(val token: String, val host: String) : DeepLinkAction()

        /**
         * An unrecognized `octomil://` deep link.
         *
         * The consuming app may choose to log this or display a generic error.
         *
         * @param uri The original URI that could not be mapped to a known action.
         */
        data class Unknown(val uri: Uri) : DeepLinkAction()
    }

    /**
     * Parse an incoming [Uri] into a [DeepLinkAction].
     *
     * Accepts both custom scheme (`octomil://pair?token=X`) and App Link
     * (`https://octomil.com/pair?token=X`) formats.
     *
     * Returns `null` if the URI is not a recognized Octomil deep link or is
     * missing required parameters.
     */
    fun parse(uri: Uri): DeepLinkAction? {
        val isCustomScheme = uri.scheme == SCHEME && uri.host == "pair"
        val isAppLink = uri.scheme == "https" && uri.host == APP_LINK_HOST && uri.path == APP_LINK_PATH

        if (!isCustomScheme && !isAppLink) {
            if (uri.scheme == SCHEME) {
                Timber.w("Unknown Octomil deep link action: %s", uri.host)
                return DeepLinkAction.Unknown(uri)
            }
            Timber.d("Ignoring URI with non-Octomil scheme: %s", uri.scheme)
            return null
        }

        val token = uri.getQueryParameter("token")
        if (token.isNullOrBlank()) {
            Timber.w("Pairing deep link missing required 'token' parameter: %s", uri)
            return null
        }
        val host = uri.getQueryParameter("host")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_HOST
        Timber.i("Parsed pairing deep link: token=%s host=%s", token, host)
        return DeepLinkAction.Pair(token = token, host = host)
    }

    /**
     * Extract and parse a deep link from an [Intent].
     *
     * Convenience method for use in `Activity.onCreate()` or `Activity.onNewIntent()`.
     * Extracts the data URI from the intent and delegates to [parse].
     *
     * @param intent The incoming intent, typically from `getIntent()` or `onNewIntent()`.
     * @return A [DeepLinkAction], or `null` if the intent has no data URI or the URI
     *   is not a valid Octomil deep link.
     */
    fun handleIntent(intent: Intent): DeepLinkAction? {
        val uri = intent.data ?: return null
        return parse(uri)
    }

    /**
     * The XML snippet that consuming apps should add to their `AndroidManifest.xml`
     * inside the activity that handles pairing.
     *
     * Provided as a constant for documentation and tooling purposes.
     */
    const val MANIFEST_INTENT_FILTER = """
        <intent-filter>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
            <category android:name="android.intent.category.BROWSABLE" />
            <data android:scheme="octomil" android:host="pair" />
        </intent-filter>"""
}
