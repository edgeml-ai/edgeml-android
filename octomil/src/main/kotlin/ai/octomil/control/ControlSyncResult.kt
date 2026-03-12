package ai.octomil.control

/**
 * Result of a control-plane sync.
 *
 * Returned by [ControlPlaneClient.refresh] to communicate what changed
 * (if anything) since the last sync.
 */
data class ControlSyncResult(
    /** Whether the remote configuration was newer than the local copy. */
    val updated: Boolean,
    /** The semantic version of the configuration that is now active. */
    val configVersion: String,
    /** Whether experiment/feature-flag assignments changed in this sync. */
    val assignmentsChanged: Boolean,
    /** Whether rollout percentages or rules changed in this sync. */
    val rolloutsChanged: Boolean,
    /** Epoch millis when this sync completed. */
    val fetchedAt: Long,
)
