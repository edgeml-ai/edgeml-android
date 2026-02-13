package ai.edgeml.api

import ai.edgeml.config.EdgeMLConfig
import okhttp3.CertificatePinner
import org.junit.Assert.*
import org.junit.Test

class CertificatePinningTest {

    private fun baseConfigBuilder(): EdgeMLConfig.Builder =
        EdgeMLConfig.Builder()
            .serverUrl("https://api.edgeml.ai")
            .deviceAccessToken("test-token")
            .orgId("test-org")
            .modelId("test-model")

    @Test
    fun `config with certificate pins stores values`() {
        val pins = listOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")
        val config = baseConfigBuilder()
            .certificatePins(pins)
            .pinnedHostname("api.edgeml.ai")
            .build()

        assertEquals(2, config.certificatePins.size)
        assertEquals("api.edgeml.ai", config.pinnedHostname)
    }

    @Test
    fun `config without pins defaults to empty`() {
        val config = baseConfigBuilder().build()

        assertTrue(config.certificatePins.isEmpty())
        assertEquals("", config.pinnedHostname)
    }

    @Test
    fun `buildCertificatePinner returns null when no pins configured`() {
        val config = baseConfigBuilder().build()
        // Access through reflection since buildCertificatePinner is private
        val method = EdgeMLApiFactory::class.java.getDeclaredMethod(
            "buildCertificatePinner",
            EdgeMLConfig::class.java
        )
        method.isAccessible = true
        val pinner = method.invoke(EdgeMLApiFactory, config)
        assertNull("No pinner should be created when no pins configured", pinner)
    }

    @Test
    fun `buildCertificatePinner returns pinner when pins configured`() {
        val config = baseConfigBuilder()
            .certificatePins(listOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
            .pinnedHostname("api.edgeml.ai")
            .build()

        val method = EdgeMLApiFactory::class.java.getDeclaredMethod(
            "buildCertificatePinner",
            EdgeMLConfig::class.java
        )
        method.isAccessible = true
        val pinner = method.invoke(EdgeMLApiFactory, config)
        assertNotNull("Pinner should be created when pins are configured", pinner)
        assertTrue(pinner is CertificatePinner)
    }

    @Test
    fun `buildCertificatePinner returns null when hostname is blank`() {
        val config = baseConfigBuilder()
            .certificatePins(listOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
            .build()

        val method = EdgeMLApiFactory::class.java.getDeclaredMethod(
            "buildCertificatePinner",
            EdgeMLConfig::class.java
        )
        method.isAccessible = true
        val pinner = method.invoke(EdgeMLApiFactory, config)
        assertNull("No pinner when hostname is blank", pinner)
    }
}
