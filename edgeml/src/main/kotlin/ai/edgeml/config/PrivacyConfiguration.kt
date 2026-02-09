package ai.edgeml.config

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Configuration for privacy-preserving upload behavior.
 *
 * Provides three key privacy enhancements:
 * 1. Staggered updates: Random delays before upload
 * 2. Differential privacy: Noise injection for formal guarantees
 * 3. Advanced weighting: Trust scores and staleness penalties (server-side)
 */
@Serializable
data class PrivacyConfiguration(
    /**
     * Whether to enable staggered updates (random delays before upload).
     *
     * Prevents timing correlation attacks by randomizing upload times.
     */
    val enableStaggeredUpdates: Boolean = true,

    /**
     * Minimum delay before upload (milliseconds).
     */
    val minUploadDelayMs: Long = 0,

    /**
     * Maximum delay before upload (milliseconds).
     *
     * Default: 300,000 ms = 5 minutes
     */
    val maxUploadDelayMs: Long = 300_000,

    /**
     * Whether to enable differential privacy (noise injection).
     *
     * When enabled, adds calibrated Gaussian noise to gradients.
     * Note: Actual DP is applied server-side; this is client-side config.
     */
    val enableDifferentialPrivacy: Boolean = false,

    /**
     * Privacy budget (epsilon) for differential privacy.
     *
     * Smaller values = more private (more noise).
     * Typical values: 0.1 (high privacy) to 10.0 (low privacy)
     *
     * Default: 1.0 (balanced)
     */
    val dpEpsilon: Double = 1.0,

    /**
     * Gradient clipping norm for differential privacy.
     *
     * Clips gradients to this L2 norm before noise injection.
     *
     * Default: 1.0
     */
    val dpClippingNorm: Double = 1.0,
) {

    /**
     * Compute a random upload delay based on configuration.
     *
     * @return Random delay in milliseconds
     */
    fun randomUploadDelay(): Long {
        if (!enableStaggeredUpdates) return 0L

        val range = maxUploadDelayMs - minUploadDelayMs
        val randomValue = Random.nextDouble()
        return minUploadDelayMs + (randomValue * range).toLong()
    }

    companion object {
        /**
         * Default privacy configuration.
         *
         * Staggered updates enabled, DP disabled.
         */
        val DEFAULT = PrivacyConfiguration()

        /**
         * High privacy configuration.
         *
         * Staggered updates + differential privacy with strong guarantees.
         */
        val HIGH_PRIVACY = PrivacyConfiguration(
            enableStaggeredUpdates = true,
            minUploadDelayMs = 60_000,      // 1 minute
            maxUploadDelayMs = 600_000,     // 10 minutes
            enableDifferentialPrivacy = true,
            dpEpsilon = 0.5,                // Strong privacy
            dpClippingNorm = 1.0,
        )

        /**
         * No privacy enhancements.
         *
         * For testing/debugging only.
         */
        val DISABLED = PrivacyConfiguration(
            enableStaggeredUpdates = false,
            minUploadDelayMs = 0,
            maxUploadDelayMs = 0,
            enableDifferentialPrivacy = false,
        )

        /**
         * Moderate privacy configuration.
         *
         * Balanced privacy/utility tradeoff.
         */
        val MODERATE = PrivacyConfiguration(
            enableStaggeredUpdates = true,
            minUploadDelayMs = 0,
            maxUploadDelayMs = 300_000,     // 5 minutes
            enableDifferentialPrivacy = true,
            dpEpsilon = 1.0,                // Moderate privacy
            dpClippingNorm = 1.0,
        )
    }
}
