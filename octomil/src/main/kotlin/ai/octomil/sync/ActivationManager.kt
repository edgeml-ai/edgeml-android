package ai.octomil.sync

import timber.log.Timber

/**
 * Activation policies supported by the server's desired-state response.
 */
enum class ActivationPolicy(val code: String) {
    /** Activate the new version immediately after download + verify. */
    IMMEDIATE("immediate"),

    /** Stage the new version; activate on next SDK init / app launch. */
    NEXT_LAUNCH("next_launch"),

    /** Stage only; activation is triggered explicitly by the integrator. */
    MANUAL("manual"),

    /** Stage the new version; activate when the device is idle. */
    WHEN_IDLE("when_idle");

    companion object {
        fun fromCode(code: String?): ActivationPolicy =
            entries.firstOrNull { it.code == code } ?: IMMEDIATE
    }
}

/**
 * Result of an activation attempt.
 */
data class ActivationResult(
    val success: Boolean,
    val previousVersion: String? = null,
    val newVersion: String? = null,
    val errorCode: String? = null,
)

/**
 * Manages activation of downloaded artifacts according to the server-specified
 * activation policy, and handles rollback when activation fails.
 *
 * Works in concert with [ArtifactMetadataStore] to track which version is
 * currently serving and which versions are staged/failed.
 */
class ActivationManager(
    private val metadataStore: ArtifactMetadataStore,
) {
    /**
     * Attempt to activate an artifact according to the given [policy].
     *
     * For [ActivationPolicy.IMMEDIATE]: marks the new version as active and
     * deactivates the old one.
     *
     * For [ActivationPolicy.NEXT_LAUNCH] / [ActivationPolicy.WHEN_IDLE]: marks
     * the new version as staged; call [activatePending] on next init to complete.
     *
     * For [ActivationPolicy.MANUAL]: marks as staged; no automatic activation.
     *
     * @return An [ActivationResult] describing what happened.
     */
    fun activate(
        modelId: String,
        artifactId: String,
        artifactVersion: String,
        policy: ActivationPolicy,
    ): ActivationResult {
        val previousActive = metadataStore.activeEntry(modelId)
        val previousVersion = previousActive?.modelVersion

        return when (policy) {
            ActivationPolicy.IMMEDIATE -> {
                try {
                    metadataStore.activate(modelId, artifactId, artifactVersion)
                    val newEntry = metadataStore.entry(artifactId, artifactVersion)
                    Timber.d("Activated $artifactId@$artifactVersion (immediate)")
                    ActivationResult(
                        success = true,
                        previousVersion = previousVersion,
                        newVersion = newEntry?.modelVersion,
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Activation failed for $artifactId@$artifactVersion")
                    ActivationResult(
                        success = false,
                        previousVersion = previousVersion,
                        errorCode = "activation_exception",
                    )
                }
            }

            ActivationPolicy.NEXT_LAUNCH,
            ActivationPolicy.WHEN_IDLE,
            ActivationPolicy.MANUAL -> {
                metadataStore.markPendingActivation(artifactId, artifactVersion)
                Timber.d("Staged $artifactId@$artifactVersion for ${policy.code} activation")
                ActivationResult(
                    success = true,
                    previousVersion = previousVersion,
                    newVersion = metadataStore.entry(artifactId, artifactVersion)?.modelVersion,
                )
            }
        }
    }

    /**
     * Activate any pending (staged) artifacts for [modelId].
     * Intended to be called on SDK init / app launch for next_launch policy.
     *
     * @return list of activation results for any staged artifacts that were activated.
     */
    fun activatePending(modelId: String): List<ActivationResult> {
        val staged = metadataStore.entriesForModel(modelId)
            .filter { it.status == ArtifactSyncStatus.STAGED }

        return staged.map { entry ->
            activate(modelId, entry.artifactId, entry.artifactVersion, ActivationPolicy.IMMEDIATE)
        }
    }

    /**
     * Roll back to the previous active version when activation or health check fails.
     *
     * Marks [failedArtifactId]@[failedVersion] as failed and re-activates the
     * previous version if one exists.
     *
     * @return An [ActivationResult] describing the rollback outcome.
     */
    fun rollback(
        modelId: String,
        failedArtifactId: String,
        failedVersion: String,
        errorCode: String,
    ): ActivationResult {
        Timber.w("Rolling back $failedArtifactId@$failedVersion: $errorCode")

        // Mark the failed version
        metadataStore.markFailed(failedArtifactId, failedVersion, errorCode)

        // Find the most recent non-failed version to revert to
        val fallback = metadataStore.entriesForModel(modelId)
            .filter {
                it.status != ArtifactSyncStatus.FAILED &&
                    !(it.artifactId == failedArtifactId && it.artifactVersion == failedVersion)
            }
            .maxByOrNull { it.installedAt }

        return if (fallback != null) {
            metadataStore.activate(modelId, fallback.artifactId, fallback.artifactVersion)
            Timber.i("Rolled back to ${fallback.artifactId}@${fallback.artifactVersion}")
            ActivationResult(
                success = true,
                previousVersion = fallback.modelVersion,
                newVersion = fallback.modelVersion,
            )
        } else {
            Timber.w("No fallback version available for $modelId after rollback")
            ActivationResult(
                success = false,
                errorCode = "no_fallback_available",
            )
        }
    }
}
