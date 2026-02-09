package ai.edgeml.sdk

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun interface DeviceAuthTransport {
    fun postJson(
        path: String,
        body: JSONObject,
        bearerToken: String?,
        expectedStatusCodes: Set<Int>,
    ): JSONObject
}

internal interface DeviceAuthStateStore {
    fun save(state: DeviceAuthManager.DeviceTokenState)

    fun load(): DeviceAuthManager.DeviceTokenState?

    fun clear()
}

/**
 * Device auth manager for bootstrap/refresh/revoke token lifecycle.
 *
 * Stores token state encrypted using Android Keystore-backed AES/GCM.
 */
class DeviceAuthManager internal constructor(
    private val baseUrl: String,
    private val orgId: String,
    private val deviceIdentifier: String,
    private val transport: DeviceAuthTransport,
    private val stateStore: DeviceAuthStateStore,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        baseUrl: String,
        orgId: String,
        deviceIdentifier: String,
        prefsName: String = "edgeml_device_auth",
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        baseUrl = baseUrl,
        orgId = orgId,
        deviceIdentifier = deviceIdentifier,
        transport = OkHttpDeviceAuthTransport(baseUrl),
        stateStore =
            KeystorePrefsDeviceAuthStateStore(
                context = context,
                prefsName = prefsName,
                orgId = orgId,
                deviceIdentifier = deviceIdentifier,
            ),
        ioDispatcher = ioDispatcher,
    )

    data class DeviceTokenState(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresAt: String,
        val expiresAtEpochMillis: Long,
        val orgId: String,
        val deviceIdentifier: String,
        val scopes: List<String>,
    )

    suspend fun bootstrap(
        bootstrapBearerToken: String,
        scopes: List<String> = listOf("devices:write"),
        accessTtlSeconds: Int? = null,
        deviceId: String? = null,
    ): DeviceTokenState =
        withContext(ioDispatcher) {
            val payload =
                JSONObject()
                    .put("org_id", orgId)
                    .put("device_identifier", deviceIdentifier)
                    .put("scopes", JSONArray(scopes))

            if (accessTtlSeconds != null) payload.put("access_ttl_seconds", accessTtlSeconds)
            if (deviceId != null) payload.put("device_id", deviceId)

            val response =
                transport.postJson(
                    path = "/api/v1/device-auth/bootstrap",
                    body = payload,
                    bearerToken = bootstrapBearerToken,
                    expectedStatusCodes = setOf(200, 201),
                )
            val state = parseState(response)
            stateStore.save(state)
            state
        }

    suspend fun refresh(): DeviceTokenState =
        withContext(ioDispatcher) {
            val current = stateStore.load() ?: throw IllegalStateException("No token state stored")

            val response =
                transport.postJson(
                    path = "/api/v1/device-auth/refresh",
                    body = JSONObject().put("refresh_token", current.refreshToken),
                    bearerToken = null,
                    expectedStatusCodes = setOf(200),
                )
            val state = parseState(response)
            stateStore.save(state)
            state
        }

    suspend fun revoke(reason: String = "sdk_revoke") =
        withContext(ioDispatcher) {
            val current = stateStore.load() ?: return@withContext

            transport.postJson(
                path = "/api/v1/device-auth/revoke",
                body =
                    JSONObject()
                        .put("refresh_token", current.refreshToken)
                        .put("reason", reason),
                bearerToken = null,
                expectedStatusCodes = setOf(200, 204),
            )
            stateStore.clear()
        }

    suspend fun getAccessToken(refreshIfExpiringWithinSeconds: Long = 30): String =
        withContext(ioDispatcher) {
            val state = stateStore.load() ?: throw IllegalStateException("No token state stored")
            val thresholdMillis = nowMillisProvider() + (refreshIfExpiringWithinSeconds * 1000)
            if (thresholdMillis >= state.expiresAtEpochMillis) {
                return@withContext try {
                    refresh().accessToken
                } catch (error: Exception) {
                    // Offline-safe fallback: keep current token until hard expiry.
                    if (nowMillisProvider() < state.expiresAtEpochMillis) {
                        state.accessToken
                    } else {
                        throw error
                    }
                }
            }
            state.accessToken
        }

    private fun parseState(payload: JSONObject): DeviceTokenState {
        val scopesArray = payload.optJSONArray("scopes") ?: JSONArray()
        val scopes = (0 until scopesArray.length()).map { scopesArray.getString(it) }

        return DeviceTokenState(
            accessToken = payload.getString("access_token"),
            refreshToken = payload.getString("refresh_token"),
            tokenType = payload.optString("token_type", "Bearer"),
            expiresAt = payload.getString("expires_at"),
            expiresAtEpochMillis = nowMillisProvider() + (payload.optLong("expires_in", 0L) * 1000),
            orgId = payload.getString("org_id"),
            deviceIdentifier = payload.getString("device_identifier"),
            scopes = scopes,
        )
    }
}

private class OkHttpDeviceAuthTransport(
    private val baseUrl: String,
) : DeviceAuthTransport {
    private val client = OkHttpClient()
    private val contentType = "application/json".toMediaType()

    override fun postJson(
        path: String,
        body: JSONObject,
        bearerToken: String?,
        expectedStatusCodes: Set<Int>,
    ): JSONObject {
        val requestBody = body.toString().toRequestBody(contentType)
        val builder =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}$path")
                .post(requestBody)
                .header("Content-Type", "application/json")
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }

        client.newCall(builder.build()).execute().use { response ->
            val statusCode = response.code
            check(expectedStatusCodes.contains(statusCode)) {
                "Device auth request failed with status $statusCode"
            }
            if (statusCode == 204) {
                return JSONObject()
            }
            val bodyText = response.body?.string().orEmpty()
            return if (bodyText.isBlank()) JSONObject() else JSONObject(bodyText)
        }
    }
}

private class KeystorePrefsDeviceAuthStateStore(
    private val context: Context,
    private val prefsName: String,
    private val orgId: String,
    private val deviceIdentifier: String,
) : DeviceAuthStateStore {
    private val alias = "edgeml_device_token_key"
    private val keyStoreProvider = "AndroidKeyStore"

    private val prefs
        get() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun storageKey(): String = "$orgId:$deviceIdentifier"

    override fun save(state: DeviceAuthManager.DeviceTokenState) {
        val stateJson =
            JSONObject()
                .put("access_token", state.accessToken)
                .put("refresh_token", state.refreshToken)
                .put("token_type", state.tokenType)
                .put("expires_at", state.expiresAt)
                .put("expires_at_epoch_ms", state.expiresAtEpochMillis)
                .put("org_id", state.orgId)
                .put("device_identifier", state.deviceIdentifier)
                .put("scopes", JSONArray(state.scopes))
                .toString()

        val encrypted = encrypt(stateJson)
        prefs.edit().putString(storageKey(), encrypted).apply()
    }

    override fun load(): DeviceAuthManager.DeviceTokenState? {
        val encrypted = prefs.getString(storageKey(), null) ?: return null
        val json = JSONObject(decrypt(encrypted))
        val scopesArray = json.optJSONArray("scopes") ?: JSONArray()
        val scopes = (0 until scopesArray.length()).map { scopesArray.getString(it) }
        return DeviceAuthManager.DeviceTokenState(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            tokenType = json.optString("token_type", "Bearer"),
            expiresAt = json.getString("expires_at"),
            expiresAtEpochMillis = json.optLong("expires_at_epoch_ms", 0L),
            orgId = json.getString("org_id"),
            deviceIdentifier = json.getString("device_identifier"),
            scopes = scopes,
        )
    }

    override fun clear() {
        prefs.edit().remove(storageKey()).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(keyStoreProvider).apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance("AES", keyStoreProvider)
        val parameterSpec =
            android.security.keystore.KeyGenParameterSpec
                .Builder(
                    alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        keyGenerator.init(parameterSpec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        val payload = iv + encrypted
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, 12)
        val encrypted = payload.copyOfRange(12, payload.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
        val plain = cipher.doFinal(encrypted)
        return String(plain, StandardCharsets.UTF_8)
    }
}
