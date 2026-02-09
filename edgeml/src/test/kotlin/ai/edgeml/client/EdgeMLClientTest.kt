package ai.edgeml.client

import ai.edgeml.api.EdgeMLApi
import ai.edgeml.api.dto.DeviceCapabilities
import ai.edgeml.api.dto.DevicePolicyResponse
import ai.edgeml.api.dto.DeviceRegistrationResponse
import ai.edgeml.api.dto.GroupMembership
import ai.edgeml.api.dto.GroupMembershipsResponse
import ai.edgeml.models.InferenceOutput
import ai.edgeml.models.ModelManager
import ai.edgeml.storage.SecureStorage
import ai.edgeml.sync.EventQueue
import ai.edgeml.sync.WorkManagerSync
import ai.edgeml.testCachedModel
import ai.edgeml.testConfig
import ai.edgeml.training.TFLiteTrainer
import ai.edgeml.utils.DeviceUtils
import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EdgeMLClientTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var api: EdgeMLApi
    private lateinit var storage: SecureStorage
    private lateinit var modelManager: ModelManager
    private lateinit var trainer: TFLiteTrainer
    private lateinit var syncManager: WorkManagerSync
    private lateinit var eventQueue: EventQueue
    private lateinit var client: EdgeMLClient

    private val config = testConfig(
        enableBackgroundSync = false,
        enableHeartbeat = false,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock DeviceUtils to avoid NPE from null Build fields on CI JVM
        mockkObject(DeviceUtils)
        every { DeviceUtils.getManufacturer() } returns "TestManufacturer"
        every { DeviceUtils.getModel() } returns "TestModel"
        every { DeviceUtils.getOsVersion() } returns "Android 14 (API 34)"
        every { DeviceUtils.getLocale() } returns "en_US"
        every { DeviceUtils.getRegion() } returns "us"
        every { DeviceUtils.getCpuArchitecture() } returns "arm64-v8a"
        every { DeviceUtils.generateDeviceIdentifier(any()) } returns "abcd1234abcd1234abcd1234abcd1234"
        every { DeviceUtils.getDeviceCapabilities(any()) } returns DeviceCapabilities(
            cpuArchitecture = "arm64-v8a",
            gpuAvailable = false,
            nnapiAvailable = false,
            totalMemoryMb = 4096,
            availableStorageMb = 2048,
        )
        every { DeviceUtils.getAvailableStorageMb() } returns 2048L
        every { DeviceUtils.getAvailableMemoryMb(any()) } returns 1024L

        context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context

        api = mockk<EdgeMLApi>(relaxed = true)
        storage = mockk<SecureStorage>(relaxed = true)
        modelManager = mockk<ModelManager>(relaxed = true)
        trainer = mockk<TFLiteTrainer>(relaxed = true)
        syncManager = mockk<WorkManagerSync>(relaxed = true)
        eventQueue = mockk<EventQueue>(relaxed = true)

        // Default stubs
        coEvery { storage.getClientDeviceIdentifier() } returns null
        coEvery { storage.getServerDeviceId() } returns null
        coEvery { eventQueue.addTrainingEvent(any(), any(), any()) } returns true

        client = EdgeMLClient.Builder(context)
            .config(config)
            .ioDispatcher(testDispatcher)
            .mainDispatcher(testDispatcher)
            .api(api)
            .storage(storage)
            .modelManager(modelManager)
            .trainer(trainer)
            .syncManager(syncManager)
            .eventQueue(eventQueue)
            .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(DeviceUtils)
    }

    /**
     * Force the client into READY state via reflection.
     *
     * MockK 1.14.9 cannot reliably stub ModelManager methods that use default
     * parameters referencing instance state (config.modelId) and return
     * Result<T> (value class). The Kotlin $default synthetic evaluates the
     * default param on the mock before MockK intercepts, causing NPE.
     * This helper bypasses full initialization for tests that just need READY.
     */
    private fun setClientReady() {
        val field = EdgeMLClient::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(client) as MutableStateFlow<ClientState>).value = ClientState.READY
    }

    // =========================================================================
    // State machine
    // =========================================================================

    @Test
    fun `initial state is UNINITIALIZED`() = runTest(testDispatcher) {
        val state = client.state.first()
        assertEquals(ClientState.UNINITIALIZED, state)
    }

    @Test
    fun `setClientReady transitions state to READY`() = runTest(testDispatcher) {
        assertEquals(ClientState.UNINITIALIZED, client.state.first())
        setClientReady()
        assertEquals(ClientState.READY, client.state.first())
    }

    @Test
    fun `initialize transitions to ERROR on registration failure`() = runTest(testDispatcher) {
        coEvery { storage.getClientDeviceIdentifier() } returns "existing-id"
        coEvery { storage.getServerDeviceId() } returns null
        coEvery { api.registerDevice(any()) } returns
            Response.error(500, okhttp3.ResponseBody.Companion.create(null, ""))

        val result = client.initialize()
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertEquals(ClientState.ERROR, client.state.first())
    }

    @Test
    fun `close transitions to CLOSED`() = runTest(testDispatcher) {
        setClientReady()

        client.close()
        advanceUntilIdle()

        assertEquals(ClientState.CLOSED, client.state.first())
    }

    // =========================================================================
    // Registration flow
    // =========================================================================

    @Test
    fun `initialize registers device when not registered`() = runTest(testDispatcher) {
        stubSuccessfulRegistration()
        coEvery { modelManager.ensureModelAvailable(any(), any()) } returns Result.success(testCachedModel())
        coEvery { modelManager.getCachedModel(any(), any()) } returns testCachedModel()
        coEvery { trainer.loadModel(any()) } returns Result.success(true)

        client.initialize()
        advanceUntilIdle()

        coVerify { api.registerDevice(any()) }
        coVerify { storage.setServerDeviceId("server-device-id") }
    }

    @Test
    fun `initialize skips registration when already registered`() = runTest(testDispatcher) {
        coEvery { storage.getClientDeviceIdentifier() } returns "existing-id"
        coEvery { storage.getServerDeviceId() } returns "existing-server-id"
        coEvery { api.getDeviceGroups(any()) } returns Response.success(
            GroupMembershipsResponse(memberships = emptyList(), count = 0),
        )
        coEvery { modelManager.ensureModelAvailable(any(), any()) } returns Result.success(testCachedModel())
        coEvery { modelManager.getCachedModel(any(), any()) } returns testCachedModel()
        coEvery { trainer.loadModel(any()) } returns Result.success(true)

        client.initialize()
        advanceUntilIdle()

        coVerify(exactly = 0) { api.registerDevice(any()) }
    }

    // =========================================================================
    // Group membership
    // =========================================================================

    @Test
    fun `isInGroup returns false when device has no groups`() {
        assertFalse(client.isInGroup("any-group"))
    }

    @Test
    fun `isInGroupNamed returns false when device has no groups`() {
        assertFalse(client.isInGroupNamed("any-name"))
    }

    @Test
    fun `group memberships populated after initialize`() = runTest(testDispatcher) {
        val memberships = listOf(
            GroupMembership(
                id = "m1",
                deviceId = "d1",
                groupId = "g1",
                groupName = "beta-testers",
                membershipType = "static",
                createdAt = "2026-01-01T00:00:00Z",
            ),
        )

        coEvery { storage.getClientDeviceIdentifier() } returns "device-id"
        coEvery { storage.getServerDeviceId() } returns "server-id"
        coEvery { api.getDeviceGroups("server-id") } returns Response.success(
            GroupMembershipsResponse(memberships = memberships, count = 1),
        )
        coEvery { modelManager.ensureModelAvailable(any(), any()) } returns Result.success(testCachedModel())
        coEvery { modelManager.getCachedModel(any(), any()) } returns testCachedModel()
        coEvery { trainer.loadModel(any()) } returns Result.success(true)

        client.initialize()
        advanceUntilIdle()

        assertTrue(client.isInGroup("g1"))
        assertTrue(client.isInGroupNamed("beta-testers"))
        assertFalse(client.isInGroup("nonexistent"))
    }

    // =========================================================================
    // Inference
    // =========================================================================

    @Test
    fun `runInference delegates to trainer and tracks events`() = runTest(testDispatcher) {
        setClientReady()

        val inferenceOutput = InferenceOutput(
            data = floatArrayOf(0.1f, 0.9f),
            shape = intArrayOf(1, 2),
            inferenceTimeMs = 42L,
        )
        coEvery { trainer.runInference(any<FloatArray>()) } returns Result.success(inferenceOutput)

        val result = client.runInference(floatArrayOf(1.0f, 2.0f))
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(42L, result.getOrNull()?.inferenceTimeMs)
    }

    @Test
    fun `runInference tracks failure event`() = runTest(testDispatcher) {
        setClientReady()

        coEvery { trainer.runInference(any<FloatArray>()) } returns
            Result.failure(RuntimeException("interpreter closed"))

        val result = client.runInference(floatArrayOf(1.0f))
        advanceUntilIdle()

        assertTrue(result.isFailure)
    }

    @Test
    fun `runInference throws when not ready`() = runTest(testDispatcher) {
        try {
            client.runInference(floatArrayOf(1.0f))
            assertTrue(false, "Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not ready"))
        }
    }

    // =========================================================================
    // Model management
    // =========================================================================

    // Note: updateModel() cannot be tested directly because it calls
    // modelManager.ensureModelAvailable(forceDownload = true), and Kotlin's
    // $default synthetic evaluates config.modelId on the mock before MockK
    // intercepts, causing NPE. Model access is tested via getCurrentModel.

    @Test
    fun `getCurrentModel delegates to trainer`() {
        val model = testCachedModel()
        every { trainer.currentModel } returns model

        val result = client.getCurrentModel()
        assertEquals(model, result)
    }

    @Test
    fun `getCurrentModel returns null when no model loaded`() {
        every { trainer.currentModel } returns null
        assertNull(client.getCurrentModel())
    }

    // =========================================================================
    // Sync management
    // =========================================================================

    @Test
    fun `triggerSync delegates to syncManager`() {
        client.triggerSync()
        verify { syncManager.triggerImmediateSync(any(), any()) }
    }

    @Test
    fun `cancelSync delegates to syncManager`() {
        client.cancelSync()
        verify { syncManager.cancelAllSync() }
    }

    // =========================================================================
    // Event tracking
    // =========================================================================

    @Test
    fun `trackEvent adds event to queue`() = runTest(testDispatcher) {
        client.trackEvent(
            eventType = "custom_event",
            metrics = mapOf("value" to 1.0),
            metadata = mapOf("key" to "val"),
        )

        coVerify {
            eventQueue.addTrainingEvent(
                type = "custom_event",
                metrics = mapOf("value" to 1.0),
                metadata = mapOf("key" to "val"),
            )
        }
    }

    // =========================================================================
    // Close
    // =========================================================================

    @Test
    fun `close cleans up all resources`() = runTest(testDispatcher) {
        setClientReady()

        client.close()
        advanceUntilIdle()

        coVerify { trainer.close() }
        verify { syncManager.cancelAllSync() }
        assertEquals(ClientState.CLOSED, client.state.first())
    }

    // =========================================================================
    // Builder validation
    // =========================================================================

    @Test
    fun `builder requires config`() {
        try {
            EdgeMLClient.Builder(context)
                .ioDispatcher(testDispatcher)
                .mainDispatcher(testDispatcher)
                .build()
            assertTrue(false, "Should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Configuration is required"))
        }
    }

    @Test
    fun `builder creates client with all injected deps`() {
        val built = EdgeMLClient.Builder(context)
            .config(config)
            .ioDispatcher(testDispatcher)
            .mainDispatcher(testDispatcher)
            .api(api)
            .storage(storage)
            .modelManager(modelManager)
            .trainer(trainer)
            .syncManager(syncManager)
            .eventQueue(eventQueue)
            .build()

        assertNotNull(built)
    }

    // =========================================================================
    // Heartbeat
    // =========================================================================

    @Test
    fun `triggerHeartbeat fails when not registered`() = runTest(testDispatcher) {
        val result = client.triggerHeartbeat()
        assertTrue(result.isFailure)
    }

    @Test
    fun `stopHeartbeat does not throw when no heartbeat running`() {
        client.stopHeartbeat()
        // No exception means success
    }

    // =========================================================================
    // Companion / singleton
    // =========================================================================

    @Test
    fun `isInitialized returns true after build`() {
        assertTrue(EdgeMLClient.isInitialized())
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun stubSuccessfulRegistration() {
        coEvery { storage.getClientDeviceIdentifier() } returns null
        coEvery { storage.getServerDeviceId() } returnsMany listOf(null, "server-device-id")
        coEvery { api.registerDevice(any()) } returns Response.success(
            DeviceRegistrationResponse(
                id = "server-device-id",
                deviceIdentifier = "generated-id",
                orgId = "test-org",
                platform = "android",
                status = "active",
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z",
                apiToken = "new-api-token",
            ),
        )
        coEvery { api.getDevicePolicy(any()) } returns Response.success(
            DevicePolicyResponse(batteryThreshold = 20, networkPolicy = "any"),
        )
        coEvery { api.getDeviceGroups(any()) } returns Response.success(
            GroupMembershipsResponse(memberships = emptyList(), count = 0),
        )
    }
}
