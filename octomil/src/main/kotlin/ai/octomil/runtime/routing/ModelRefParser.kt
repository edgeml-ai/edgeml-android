package ai.octomil.runtime.routing

/**
 * Parses model reference strings into structured [ParsedModelRef] values.
 *
 * Supported formats:
 * - `@app/slug/capability` -> AppRef(slug, capability)
 * - `@capability/cap`      -> CapabilityRef(cap)
 * - `deploy_xxx`           -> DeploymentRef(id)
 * - `exp_id/variant`       -> ExperimentRef(experimentId, variantId)
 * - `alias:name`           -> AliasRef(name)
 * - bare string            -> ModelRef(model)
 *
 * The parser is deterministic and offline -- no network calls. Malformed scoped
 * references return [ParsedModelRef.UnknownRef]; plain strings are treated as
 * model IDs.
 */
object ModelRefParser {

    fun parse(model: String): ParsedModelRef {
        val trimmed = model.trim()
        if (trimmed.isBlank()) return ParsedModelRef.DefaultRef

        // @app/slug/capability
        if (trimmed.startsWith("@app/")) {
            val parts = trimmed.removePrefix("@app/").split("/", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                return ParsedModelRef.AppRef(slug = parts[0], capability = parts[1])
            }
            return ParsedModelRef.UnknownRef(trimmed)
        }

        // @capability/cap
        if (trimmed.startsWith("@capability/")) {
            val cap = trimmed.removePrefix("@capability/")
            if (cap.isNotEmpty()) {
                return ParsedModelRef.CapabilityRef(capability = cap)
            }
            return ParsedModelRef.UnknownRef(trimmed)
        }

        // deploy_xxx — always classify as deployment if prefix matches
        if (trimmed.startsWith("deploy_")) {
            val id = trimmed.removePrefix("deploy_")
            return ParsedModelRef.DeploymentRef(deploymentId = id)
        }

        // exp/variant (must contain exactly one slash, and the prefix before slash
        // must start with "exp" or "experiment")
        val slashIdx = trimmed.indexOf('/')
        if (slashIdx > 0 && slashIdx < trimmed.length - 1) {
            val prefix = trimmed.substring(0, slashIdx)
            val variant = trimmed.substring(slashIdx + 1)
            if (prefix.startsWith("exp_")) {
                return ParsedModelRef.ExperimentRef(
                    experimentId = prefix.removePrefix("exp_"),
                    variantId = variant,
                )
            }
        }

        if (trimmed.startsWith("alias:") && trimmed.length > "alias:".length) {
            return ParsedModelRef.AliasRef(trimmed)
        }

        if (trimmed.startsWith("@") || trimmed.contains("://")) {
            return ParsedModelRef.UnknownRef(trimmed)
        }

        return ParsedModelRef.ModelRef(trimmed)
    }
}

/**
 * A parsed model reference with a discriminated kind for route metadata.
 */
sealed class ParsedModelRef {
    /** The wire kind name recorded in route metadata. */
    abstract val kind: String

    /** The original reference string (reconstructed). */
    abstract val ref: String

    data class AppRef(val slug: String, val capability: String) : ParsedModelRef() {
        override val kind = "app"
        override val ref = "@app/$slug/$capability"
    }

    data class CapabilityRef(val capability: String) : ParsedModelRef() {
        override val kind = "capability"
        override val ref = "@capability/$capability"
    }

    data class DeploymentRef(val deploymentId: String) : ParsedModelRef() {
        override val kind = "deployment"
        override val ref = "deploy_$deploymentId"
    }

    data class ExperimentRef(val experimentId: String, val variantId: String) : ParsedModelRef() {
        override val kind = "experiment"
        override val ref = "exp_$experimentId/$variantId"
    }

    data class AliasRef(val alias: String) : ParsedModelRef() {
        override val kind = "alias"
        override val ref = alias
    }

    object DefaultRef : ParsedModelRef() {
        override val kind = "default"
        override val ref = ""
    }

    data class UnknownRef(val raw: String) : ParsedModelRef() {
        override val kind = "unknown"
        override val ref = raw
    }

    data class ModelRef(val model: String) : ParsedModelRef() {
        override val kind = "model"
        override val ref = model
    }
}
