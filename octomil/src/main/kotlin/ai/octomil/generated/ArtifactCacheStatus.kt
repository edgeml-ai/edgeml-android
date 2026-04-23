package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ArtifactCacheStatus(val code: String) {
    HIT("hit"),
    MISS("miss"),
    DOWNLOADED("downloaded"),
    NOT_APPLICABLE("not_applicable"),
    UNAVAILABLE("unavailable");

    companion object {
        fun fromCode(code: String): ArtifactCacheStatus? =
            entries.firstOrNull { it.code == code }
    }
}
