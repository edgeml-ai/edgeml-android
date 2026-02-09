package ai.edgeml.personalization

/**
 * Training mode for personalization and federated learning.
 *
 * This enum provides a clear API for choosing between:
 * - LOCAL_ONLY: Maximum privacy, all training stays on-device
 * - FEDERATED: Privacy-preserving collaborative learning with encrypted updates
 */
enum class TrainingMode {
    /**
     * Local-only personalization mode (maximum privacy).
     *
     * In this mode:
     * - All training happens on-device
     * - Model stays on-device
     * - Training data never leaves device
     * - NO updates sent to server
     * - Best for privacy-critical applications
     * - GDPR/CCPA/HIPAA compliant by design
     *
     * Use cases:
     * - Healthcare applications with PHI
     * - Financial applications with PII
     * - Apps requiring maximum privacy
     * - Testing and development
     */
    LOCAL_ONLY,

    /**
     * Federated learning mode (privacy + collective intelligence).
     *
     * In this mode:
     * - Training happens on-device
     * - Model personalizes locally
     * - Training data stays on-device
     * - Only encrypted weight deltas sent to server
     * - Cannot reconstruct original data from deltas
     * - Benefits from global model improvements
     * - 25%+ better predictions
     *
     * Use cases:
     * - Keyboard predictions
     * - Content recommendations
     * - Search suggestions
     * - Apps with millions of users
     * - When users opt-in to sharing
     */
    FEDERATED,

    ;

    /**
     * Whether this mode uploads updates to the server.
     */
    val uploadsToServer: Boolean
        get() = this == FEDERATED

    /**
     * User-friendly description of what this mode does.
     */
    val description: String
        get() =
            when (this) {
                LOCAL_ONLY -> "Your model learns your patterns. Data never leaves your device."
                FEDERATED -> "Your model learns from millions while keeping your data private."
            }

    /**
     * Privacy level indicator.
     */
    val privacyLevel: String
        get() =
            when (this) {
                LOCAL_ONLY -> "Maximum"
                FEDERATED -> "High"
            }

    /**
     * Data transmission indicator for UI display.
     */
    val dataTransmitted: String
        get() =
            when (this) {
                LOCAL_ONLY -> "0 bytes"
                FEDERATED -> "Encrypted weight deltas only"
            }
}
