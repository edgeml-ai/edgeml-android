package ai.edgeml.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Secure storage for EdgeML SDK using EncryptedSharedPreferences.
 *
 * Stores sensitive data like device credentials, API tokens, and configuration
 * using AES-256 encryption backed by the Android Keystore.
 */
class SecureStorage private constructor(
    private val prefs: SharedPreferences,
    private val json: Json,
) {
    private val mutex = Mutex()

    companion object {
        private const val PREFS_FILE_NAME = "edgeml_secure_prefs"

        // Keys for stored values
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SERVER_DEVICE_ID = "server_device_id"
        const val KEY_CLIENT_DEVICE_IDENTIFIER = "client_device_identifier"
        const val KEY_API_TOKEN = "api_token"
        const val KEY_CURRENT_MODEL_VERSION = "current_model_version"
        const val KEY_DEVICE_REGISTERED = "device_registered"
        const val KEY_REGISTRATION_TIMESTAMP = "registration_timestamp"
        const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        const val KEY_EXPERIMENT_ID = "experiment_id"
        const val KEY_VARIANT = "variant"
        const val KEY_MODEL_CHECKSUM = "model_checksum"
        const val KEY_DEVICE_POLICY = "device_policy"

        @Volatile
        private var instance: SecureStorage? = null

        /**
         * Get or create the SecureStorage instance.
         *
         * @param context Application context
         * @param useEncryption Whether to use EncryptedSharedPreferences (default: true)
         * @return SecureStorage instance
         */
        fun getInstance(context: Context, useEncryption: Boolean = true): SecureStorage {
            return instance ?: synchronized(this) {
                instance ?: createInstance(context.applicationContext, useEncryption).also {
                    instance = it
                }
            }
        }

        private fun createInstance(context: Context, useEncryption: Boolean): SecureStorage {
            val prefs = if (useEncryption) {
                createEncryptedPrefs(context)
            } else {
                context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            }

            val json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

            return SecureStorage(prefs, json)
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        /**
         * Clear the singleton instance (for testing).
         */
        internal fun clearInstance() {
            instance = null
        }
    }

    // =========================================================================
    // Basic Read/Write Operations
    // =========================================================================

    /**
     * Store a string value securely.
     */
    suspend fun putString(key: String, value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().putString(key, value).apply()
        }
    }

    /**
     * Retrieve a string value.
     */
    suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.getString(key, null)
        }
    }

    /**
     * Store a long value.
     */
    suspend fun putLong(key: String, value: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().putLong(key, value).apply()
        }
    }

    /**
     * Retrieve a long value.
     */
    suspend fun getLong(key: String, defaultValue: Long = 0L): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.getLong(key, defaultValue)
        }
    }

    /**
     * Store a boolean value.
     */
    suspend fun putBoolean(key: String, value: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    /**
     * Retrieve a boolean value.
     */
    suspend fun getBoolean(key: String, defaultValue: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.getBoolean(key, defaultValue)
        }
    }

    /**
     * Store a serializable object as JSON.
     */
    @PublishedApi
    internal suspend fun <T> putObjectInternal(key: String, value: T, serializer: kotlinx.serialization.KSerializer<T>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val jsonString = json.encodeToString(serializer, value)
            prefs.edit().putString(key, jsonString).apply()
        }
    }

    suspend inline fun <reified T> putObject(key: String, value: T) {
        putObjectInternal(key, value, kotlinx.serialization.serializer())
    }

    /**
     * Retrieve and deserialize an object from JSON.
     */
    @PublishedApi
    internal suspend fun <T> getObjectInternal(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.getString(key, null)?.let { jsonString ->
                try {
                    json.decodeFromString(serializer, jsonString)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to deserialize object for key: $key")
                    null
                }
            }
        }
    }

    suspend inline fun <reified T> getObject(key: String): T? {
        return getObjectInternal(key, kotlinx.serialization.serializer())
    }

    /**
     * Remove a key from storage.
     */
    suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().remove(key).apply()
        }
    }

    /**
     * Check if a key exists.
     */
    suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.contains(key)
        }
    }

    /**
     * Clear all stored data.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().clear().apply()
        }
    }

    // =========================================================================
    // Device-Specific Convenience Methods
    // =========================================================================

    /**
     * Get the stored device ID (legacy, use getServerDeviceId instead).
     */
    @Deprecated("Use getServerDeviceId instead", ReplaceWith("getServerDeviceId()"))
    suspend fun getDeviceId(): String? = getString(KEY_DEVICE_ID)

    /**
     * Store the device ID (legacy, use setServerDeviceId instead).
     */
    @Deprecated("Use setServerDeviceId instead", ReplaceWith("setServerDeviceId(deviceId)"))
    suspend fun setDeviceId(deviceId: String) = putString(KEY_DEVICE_ID, deviceId)

    /**
     * Get the server-assigned device UUID.
     */
    suspend fun getServerDeviceId(): String? = getString(KEY_SERVER_DEVICE_ID)

    /**
     * Store the server-assigned device UUID.
     */
    suspend fun setServerDeviceId(deviceId: String) = putString(KEY_SERVER_DEVICE_ID, deviceId)

    /**
     * Get the client-generated device identifier.
     */
    suspend fun getClientDeviceIdentifier(): String? = getString(KEY_CLIENT_DEVICE_IDENTIFIER)

    /**
     * Store the client-generated device identifier.
     */
    suspend fun setClientDeviceIdentifier(identifier: String) = putString(KEY_CLIENT_DEVICE_IDENTIFIER, identifier)

    /**
     * Get the stored API token.
     */
    suspend fun getApiToken(): String? = getString(KEY_API_TOKEN)

    /**
     * Store the API token.
     */
    suspend fun setApiToken(token: String) = putString(KEY_API_TOKEN, token)

    /**
     * Check if the device is registered.
     */
    suspend fun isDeviceRegistered(): Boolean = getBoolean(KEY_DEVICE_REGISTERED, false)

    /**
     * Mark the device as registered.
     */
    suspend fun setDeviceRegistered(registered: Boolean) {
        putBoolean(KEY_DEVICE_REGISTERED, registered)
        if (registered) {
            putLong(KEY_REGISTRATION_TIMESTAMP, System.currentTimeMillis())
        }
    }

    /**
     * Get the current model version.
     */
    suspend fun getCurrentModelVersion(): String? = getString(KEY_CURRENT_MODEL_VERSION)

    /**
     * Store the current model version.
     */
    suspend fun setCurrentModelVersion(version: String) = putString(KEY_CURRENT_MODEL_VERSION, version)

    /**
     * Get the model checksum.
     */
    suspend fun getModelChecksum(): String? = getString(KEY_MODEL_CHECKSUM)

    /**
     * Store the model checksum.
     */
    suspend fun setModelChecksum(checksum: String) = putString(KEY_MODEL_CHECKSUM, checksum)

    /**
     * Get the last sync timestamp.
     */
    suspend fun getLastSyncTimestamp(): Long = getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)

    /**
     * Update the last sync timestamp.
     */
    suspend fun setLastSyncTimestamp(timestamp: Long = System.currentTimeMillis()) {
        putLong(KEY_LAST_SYNC_TIMESTAMP, timestamp)
    }

    /**
     * Get the current experiment ID.
     */
    suspend fun getExperimentId(): String? = getString(KEY_EXPERIMENT_ID)

    /**
     * Store the current experiment ID.
     */
    suspend fun setExperimentId(experimentId: String?) {
        if (experimentId != null) {
            putString(KEY_EXPERIMENT_ID, experimentId)
        } else {
            remove(KEY_EXPERIMENT_ID)
        }
    }

    /**
     * Get the current variant.
     */
    suspend fun getVariant(): String = getString(KEY_VARIANT) ?: "default"

    /**
     * Store the current variant.
     */
    suspend fun setVariant(variant: String) = putString(KEY_VARIANT, variant)

    /**
     * Get the cached device policy from storage.
     */
    suspend fun getDevicePolicy(): ai.edgeml.api.dto.DevicePolicyResponse? {
        val policyJson = getString(KEY_DEVICE_POLICY) ?: return null
        return try {
            json.decodeFromString(policyJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode device policy")
            null
        }
    }

    /**
     * Store the device policy fetched from server.
     */
    suspend fun setDevicePolicy(policy: ai.edgeml.api.dto.DevicePolicyResponse) {
        val policyJson = json.encodeToString(policy)
        putString(KEY_DEVICE_POLICY, policyJson)
    }
}

/**
 * Data class for storing device registration info.
 */
@kotlinx.serialization.Serializable
data class DeviceRegistrationInfo(
    val deviceId: String,
    val orgId: String,
    val modelId: String,
    val registeredAt: Long,
    val apiToken: String? = null,
)
