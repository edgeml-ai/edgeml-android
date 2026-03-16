package ai.octomil.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Persistent install ID for telemetry resource attributes.
 *
 * Generates a UUID on first SDK initialization and persists it to
 * SharedPreferences under [PREFS_KEY]. On subsequent inits, reads from
 * the persisted value. This provides a stable anonymous identifier for
 * the `octomil.install.id` OTLP resource attribute.
 *
 * Thread-safe: uses `@Volatile` and `synchronized` for the in-memory cache.
 */
object InstallId {

    internal const val PREFS_FILE = "octomil_install_prefs"
    internal const val PREFS_KEY = "octomil_install_id"

    @Volatile
    private var cached: String? = null

    /**
     * Returns the persistent install ID, creating it if necessary.
     *
     * On first call, checks SharedPreferences for a stored value. If none
     * exists, generates a new UUID and persists it. The result is cached in
     * memory for subsequent calls.
     *
     * @param context Android application context (used to access SharedPreferences).
     * @return A stable UUID string that persists across SDK sessions.
     */
    fun getOrCreate(context: Context): String {
        cached?.let { return it }

        synchronized(this) {
            // Double-check after acquiring lock
            cached?.let { return it }

            val prefs = getPrefs(context)
            val stored = prefs.getString(PREFS_KEY, null)
            if (!stored.isNullOrEmpty()) {
                cached = stored
                return stored
            }

            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(PREFS_KEY, newId).apply()
            cached = newId
            return newId
        }
    }

    /**
     * Returns the persistent install ID using injected SharedPreferences.
     *
     * This overload avoids requiring a Context, useful for environments
     * where SharedPreferences is already available or for testing.
     *
     * @param prefs SharedPreferences instance to read from / write to.
     * @return A stable UUID string that persists across SDK sessions.
     */
    fun getOrCreate(prefs: SharedPreferences): String {
        cached?.let { return it }

        synchronized(this) {
            cached?.let { return it }

            val stored = prefs.getString(PREFS_KEY, null)
            if (!stored.isNullOrEmpty()) {
                cached = stored
                return stored
            }

            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(PREFS_KEY, newId).apply()
            cached = newId
            return newId
        }
    }

    /**
     * Returns the cached install ID without accessing storage.
     *
     * Returns null if [getOrCreate] has not been called yet in this process.
     * Useful for places that need the install ID but don't have a Context
     * (e.g., OTLP resource building in the telemetry pipeline).
     */
    fun getCached(): String? = cached

    /**
     * Clears the in-memory cache. Primarily for testing.
     *
     * Does NOT remove the persisted value from SharedPreferences.
     */
    fun resetCache() {
        cached = null
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }
}
