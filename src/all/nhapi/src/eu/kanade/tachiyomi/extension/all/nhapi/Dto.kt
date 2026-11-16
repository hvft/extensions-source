package eu.kanade.tachiyomi.extension.all.nhapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GalleryListResponse(
    val result: List<GalleryListItem> = emptyList(),
    @SerialName("num_pages") val numPages: Int = 0,
    @SerialName("per_page") val perPage: Int = 25,
    val total: Int = 0,
)

@Serializable
data class GalleryListItem(
    val id: Int,
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("english_title") val englishTitle: String? = null,
    @SerialName("japanese_title") val japaneseTitle: String? = null,
    val thumbnail: String? = null,
    @SerialName("num_pages") val numPages: Int = 0,
    @SerialName("num_favorites") val numFavorites: Int = 0,
)

@Serializable
data class GalleryDetail(
    val id: Int,
    @SerialName("media_id") val mediaId: String? = null,
    val title: GalleryTitle? = null,
    val cover: GalleryImage? = null,
    val thumbnail: GalleryImage? = null,
    val scanlator: String? = null,
    @SerialName("upload_date") val uploadDate: Long = 0,
    val tags: List<GalleryTag> = emptyList(),
    @SerialName("num_pages") val numPages: Int = 0,
    @SerialName("num_favorites") val numFavorites: Int = 0,
    val pages: List<GalleryPage> = emptyList(),
)

@Serializable
data class GalleryTitle(
    val english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
data class GalleryImage(
    val path: String,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class GalleryTag(
    val id: Int = 0,
    val type: String = "",
    val name: String = "",
)

@Serializable
data class GalleryPage(
    val number: Int = 0,
    val path: String,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class CdnConfig(
    @SerialName("image_servers") val imageServers: List<String> = emptyList(),
    @SerialName("thumb_servers") val thumbServers: List<String> = emptyList(),
)
