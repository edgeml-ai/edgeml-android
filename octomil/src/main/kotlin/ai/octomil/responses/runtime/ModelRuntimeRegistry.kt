package ai.octomil.responses.runtime

object ModelRuntimeRegistry {
    private val families = mutableMapOf<String, RuntimeFactory>()
    var defaultFactory: RuntimeFactory? = null

    fun register(family: String, factory: RuntimeFactory) {
        families[family.lowercase()] = factory
    }

    fun resolve(modelId: String): ModelRuntime? {
        // 1. Exact family match
        families[modelId.lowercase()]?.invoke(modelId)?.let { return it }

        // 2. Prefix match (e.g., "phi-4-mini" matches registered "phi")
        val prefix = families.keys
            .filter { modelId.lowercase().startsWith(it) }
            .maxByOrNull { it.length }
        if (prefix != null) {
            families[prefix]?.invoke(modelId)?.let { return it }
        }

        // 3. Default factory
        return defaultFactory?.invoke(modelId)
    }

    fun clear() {
        families.clear()
        defaultFactory = null
    }
}
