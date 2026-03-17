package ai.octomil.runtime.core

object ModelRuntimeRegistry {
    private val families = mutableMapOf<String, RuntimeFactory>()
    private val cache = mutableMapOf<String, ModelRuntime>()
    var defaultFactory: RuntimeFactory? = null

    fun register(family: String, factory: RuntimeFactory) {
        families[family.lowercase()] = factory
    }

    fun resolve(modelId: String): ModelRuntime? {
        // 0. Check cache first — reuse loaded runtimes
        cache[modelId.lowercase()]?.let { return it }

        // 1. Exact family match
        val runtime = families[modelId.lowercase()]?.invoke(modelId)
            // 2. Prefix match (e.g., "phi-4-mini" matches registered "phi")
            ?: families.keys
                .filter { modelId.lowercase().startsWith(it) }
                .maxByOrNull { it.length }
                ?.let { families[it]?.invoke(modelId) }
            // 3. Default factory
            ?: defaultFactory?.invoke(modelId)

        if (runtime != null) {
            cache[modelId.lowercase()] = runtime
        }
        return runtime
    }

    fun evict(modelId: String) {
        cache.remove(modelId.lowercase())
    }

    fun clear() {
        families.clear()
        cache.clear()
        defaultFactory = null
    }
}
