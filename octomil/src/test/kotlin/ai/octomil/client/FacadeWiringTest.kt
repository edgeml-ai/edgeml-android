package ai.octomil.client

import ai.octomil.api.OctomilApi
import ai.octomil.api.dto.DeviceCapabilities
import ai.octomil.models.ModelManager
import ai.octomil.storage.SecureStorage
import ai.octomil.sync.EventQueue
import ai.octomil.sync.WorkManagerSync
import ai.octomil.testCachedModel
import ai.octomil.testConfig
import ai.octomil.training.TFLiteTrainer
import ai.octomil.utils.DeviceUtils
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests that the facade properties (chat, capabilities, telemetry) and
 * getLoadedModel() are correctly wired on OctomilClient.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FacadeWiringTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var api: OctomilApi
    private lateinit var storage: SecureStorage
    private lateinit var modelManager: ModelManager
    private lateinit var trainer: TFLiteTrainer
    private lateinit var syncManager: WorkManagerSync
    private lateinit var eventQueue: EventQueue
    private lateinit var client: OctomilClient

    private val config = testConfig(
        enableBackgroundSync = false,
        enableHeartbeat = false,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

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
        every { DeviceUtils.getTotalMemoryMb(any()) } returns 4096L
        every { DeviceUtils.isNnapiAvailable() } returns false
        every { DeviceUtils.isGpuAvailable() } returns false

        context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context

        api = mockk<OctomilApi>(relaxed = true)
        storage = mockk<SecureStorage>(relaxed = true)
        modelManager = mockk<ModelManager>(relaxed = true)
        trainer = mockk<TFLiteTrainer>(relaxed = true)
        syncManager = mockk<WorkManagerSync>(relaxed = true)
        eventQueue = mockk<EventQueue>(relaxed = true)

        coEvery { storage.getClientDeviceIdentifier() } returns null
        coEvery { storage.getServerDeviceId() } returns null
        coEvery { eventQueue.addTrainingEvent(any(), any(), any()) } returns true

        client = OctomilClient.Builder(context)
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

    // =========================================================================
    // chat property
    // =========================================================================

    @Test
    fun `chat property is accessible and returns OctomilChat`() {
        val chat = client.chat
        assertNotNull(chat)
        assertEquals(config.modelId, chat.modelName)
    }

    @Test
    fun `chat property returns same instance on repeated access`() {
        val chat1 = client.chat
        val chat2 = client.chat
        assert(chat1 === chat2) { "chat should return the same lazy instance" }
    }

    // =========================================================================
    // capabilities property
    // =========================================================================

    @Test
    fun `capabilities property is accessible`() {
        val caps = client.capabilities
        assertNotNull(caps)
    }

    @Test
    fun `capabilities current returns valid profile`() {
        val profile = client.capabilities.current()
        assertNotNull(profile)
        assertEquals("android", profile.platform)
        assertEquals(4096L, profile.memoryMb)
    }

    // =========================================================================
    // telemetry property
    // =========================================================================

    @Test
    fun `telemetry property is accessible`() {
        val tel = client.telemetry
        assertNotNull(tel)
    }

    // =========================================================================
    // getLoadedModel
    // =========================================================================

    @Test
    fun `getLoadedModel returns null when no model loaded`() {
        every { trainer.currentModel } returns null
        assertNull(client.getLoadedModel())
    }

    @Test
    fun `getLoadedModel returns LoadedModel when model is loaded`() {
        val model = testCachedModel()
        every { trainer.currentModel } returns model

        val loaded = client.getLoadedModel()
        assertNotNull(loaded)
        assertEquals("test-model", loaded.modelId)
        assertEquals("1.0.0", loaded.version)
    }
}
