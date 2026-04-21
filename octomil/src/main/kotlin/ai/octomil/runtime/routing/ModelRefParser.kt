package ai.octomil.runtime.routing

/**
 * Parses model reference strings into structured [ParsedModelRef] values.
 *
 * Supported formats:
 * - `@app/slug/capability` -> AppRef(slug, capability)
 * - `@capability/cap`      -> CapabilityRef(cap)
 * - `deploy_xxx`           -> DeploymentRef(id)
 * - `exp/variant`          -> ExperimentRef(experimentId, variantId)
 * - empty/blank             -> DefaultRef (kind "default")
 * - bare string             -> DirectRef (kind "model")
 *
 * The parser is deterministic and offline -- no network calls. Parsing failures
 * fall through to [ParsedModelRef.DirectRef] so unknown formats are treated
 * as opaque model identifiers.
 */
object ModelRefParser {

    fun parse(model: String): ParsedModelRef {
        if (model.isBlank()) return ParsedModelRef.DefaultRef(model)

        // @app/slug/capability
        if (model.startsWith("@app/")) {
            val parts = model.removePrefix("@app/").split("/", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                return ParsedModelRef.AppRef(slug = parts[0], capability = parts[1])
            }
        }

        // @capability/cap
        if (model.startsWith("@capability/")) {
            val cap = model.removePrefix("@capability/")
            if (cap.isNotEmpty()) {
                return ParsedModelRef.CapabilityRef(capability = cap)
            }
        }

        // deploy_xxx
        if (model.startsWith("deploy_")) {
            return ParsedModelRef.DeploymentRef(deploymentId = model)
        }

        // exp/variant (must contain exactly one slash, and the prefix before slash
        // must start with "exp" or "experiment")
        val slashIdx = model.indexOf('/')
        if (slashIdx > 0 && slashIdx < model.length - 1) {
            val prefix = model.substring(0, slashIdx)
            val variant = model.substring(slashIdx + 1)
            if (prefix.startsWith("exp")) {
                return ParsedModelRef.ExperimentRef(
                    experimentId = prefix,
                    variantId = variant,
                )
            }
        }

        return ParsedModelRef.DirectRef(model)
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
        override val ref = deploymentId
    }

    data class ExperimentRef(val experimentId: String, val variantId: String) : ParsedModelRef() {
        override val kind = "experiment"
        override val ref = "$experimentId/$variantId"
    }

    data class DirectRef(val model: String) : ParsedModelRef() {
        override val kind = "model"
        override val ref = model
    }

    data class DefaultRef(val model: String) : ParsedModelRef() {
        override val kind = "default"
        override val ref = model
    }
}
