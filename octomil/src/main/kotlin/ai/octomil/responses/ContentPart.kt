package ai.octomil.responses

sealed interface ContentPart {
    data class Text(val text: String) : ContentPart

    data class Image(
        val data: String? = null,
        val url: String? = null,
        val mediaType: String? = null,
        val detail: String = "auto",
    ) : ContentPart

    data class Audio(
        val data: String,
        val mediaType: String,
    ) : ContentPart

    data class File(
        val data: String,
        val mediaType: String,
        val filename: String? = null,
    ) : ContentPart
}
