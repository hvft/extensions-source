package eu.kanade.tachiyomi.extension.all.nhapi

import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@Source
abstract class NHentai :
    KeiSource(),
    ConfigurableSource {

    internal val preference by getPreferencesLazy()

    private val apiGate = SlidingWindowGate()

    @Volatile
    private var cachedCdn: CdnConfig? = null

    private val detailCache: MutableMap<Int, GalleryDetail> = Collections.synchronizedMap(
        object : LinkedHashMap<Int, GalleryDetail>(DETAIL_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, GalleryDetail>): Boolean =
                size > DETAIL_CACHE_SIZE
        },
    )

    // ============================== Client ==============================

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this
        .addInterceptor(apiInterceptor)
        .rateLimit(IMAGE_PERMITS, 1.minutes, IMAGE_INTERVAL) { it.host != API_HOST }

    /**
     * Adds the API key, if configured, and paces requests against the documented
     * per-IP budget. The key is read on every request so that changing it in the
     * extension settings takes effect without restarting the app.
     */
    private val apiInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host != API_HOST) return@Interceptor chain.proceed(request)

        val apiKey = preference.getString(API_KEY_PREF, "").orEmpty().trim()
        apiGate.acquire(if (apiKey.isEmpty()) ANON_PERMITS else KEYED_PERMITS)

        if (apiKey.isEmpty()) return@Interceptor chain.proceed(request)

        val response = chain.proceed(
            request.newBuilder().header("Authorization", "Key $apiKey").build(),
        )
        if (response.code != 401) return@Interceptor response

        // A typo must not brick the source: fall back to the anonymous tier.
        response.close()
        Log.w(TAG, "API key rejected by the server, retrying anonymously")
        chain.proceed(request)
    }

    // ============================== Browsing ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = searchList(page, WILDCARD_QUERY, POPULAR_SORT)

    override suspend fun getLatestUpdates(page: Int): MangasPage = searchList(page, WILDCARD_QUERY, DATE_SORT)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: DATE_SORT
        val language = filters.firstInstanceOrNull<LanguageFilter>()?.toUriPart().orEmpty()
        return searchList(page, queryOf(query, language), sort)
    }

    /**
     * nhentai filters languages through the query string itself; there is no
     * separate parameter. `*` keeps a bare language filter from matching nothing.
     */
    private fun queryOf(rawQuery: String, language: String): String {
        val base = rawQuery.ifBlank { WILDCARD_QUERY }
        return if (language.isEmpty()) base else "$base language:$language"
    }

    private suspend fun searchList(page: Int, query: String, sort: String): MangasPage {
        val url = "$baseUrl/api/v2/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .build()

        val response = client.get(url, Fetch.XHR).parseAs<GalleryListResponse>()
        val thumbServers = cdnConfig().thumbServers

        return MangasPage(
            response.result.map { it.toSManga(thumbServers) },
            page < response.numPages,
        )
    }

    private fun GalleryListItem.toSManga(thumbServers: List<String>): SManga = SManga.create().apply {
        url = "/g/$id/"
        title = japaneseTitle?.takeIf { it.isNotBlank() }
            ?: englishTitle?.takeIf { it.isNotBlank() }
            ?: "Gallery $id"
        thumbnail?.let { thumbnail_url = mediaUrl(thumbServers, 0, it) }
    }

    // ============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != API_HOST) return null
        val id = galleryIdOf(url.encodedPath) ?: return null
        return mangaDetails(SManga.create().apply { this.url = "/g/$id/" })
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detail = detail(galleryIdOfManga(manga))

        val updated = if (fetchDetails) {
            val thumbServers = cdnConfig().thumbServers
            manga.apply { fill(detail, thumbServers) }
        } else {
            manga
        }

        return SMangaUpdate(updated, if (fetchChapters) listOf(detail.toChapter()) else chapters)
    }

    private suspend fun mangaDetails(manga: SManga): SManga {
        val detail = detail(galleryIdOfManga(manga))
        val thumbServers = cdnConfig().thumbServers
        return manga.apply { fill(detail, thumbServers) }
    }

    private fun SManga.fill(detail: GalleryDetail, thumbServers: List<String>) {
        val resolvedTitle = titleOf(detail.title)
        if (resolvedTitle != null) title = resolvedTitle

        val artists = detail.tags.filter { it.type == "artist" }.joinToString(", ") { it.name }
        if (artists.isNotEmpty()) {
            author = artists
            artist = artists
        }

        val tagNames = detail.tags.filter { it.type == "tag" }.joinToString(", ") { it.name }
        if (tagNames.isNotEmpty()) genre = tagNames

        description = detail.summary()
        (detail.thumbnail?.path ?: detail.cover?.path)?.let { thumbnail_url = mediaUrl(thumbServers, 0, it) }
    }

    /**
     * The Japanese title is the one worth showing: it keeps the circle and the
     * language marker. `pretty` is only the bracket-stripped last resort.
     */
    private fun titleOf(title: GalleryTitle?): String? = title?.japanese?.takeIf { it.isNotBlank() }
        ?: title?.english?.takeIf { it.isNotBlank() }
        ?: title?.pretty?.takeIf { it.isNotBlank() }

    private fun GalleryDetail.summary(): String = buildList {
        // Mihon carries a single title, so the other language would be lost.
        title?.english?.takeIf { it.isNotBlank() && it != titleOf(title) }?.let { add(it) }
        scanlator?.takeIf { it.isNotBlank() }?.let { add("Scanlator: $it") }
        add("Pages: $numPages")
        add("Favorites: $numFavorites")
        if (uploadDate > 0) {
            add("Uploaded: ${Instant.ofEpochSecond(uploadDate).atZone(ZoneId.systemDefault()).toLocalDate()}")
        }
    }.joinToString("\n")

    private fun GalleryDetail.toChapter(): SChapter {
        val galleryId = id
        val pageCount = numPages
        val scanlatorName = scanlator?.takeIf { it.isNotBlank() }
        val uploadedAt = uploadDate

        return SChapter.create().apply {
            url = "/g/$galleryId/"
            name = "${pageCount}P"
            chapter_number = 1f
            scanlator = scanlatorName
            date_upload = uploadedAt * 1000
        }
    }

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val detail = detail(galleryIdOrThrow(chapter.url))
        val imageServers = cdnConfig().imageServers

        return detail.pages.mapIndexed { index, page ->
            Page(index, imageUrl = mediaUrl(imageServers, index, page.path))
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("搜索框留空 = 浏览全部；语言筛选只在搜索时生效"),
        SortFilter(),
        LanguageFilter(),
    )

    // ============================= Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = setupApiKeyPreferences(screen)

    // ============================= Utilities =============================

    internal suspend fun probeApiKey(): String {
        val apiKey = preference.getString(API_KEY_PREF, "").orEmpty().trim()
        if (apiKey.isEmpty()) return "未配置 Key，当前为匿名模式（搜索 10 次/分）"

        return try {
            client.get("$baseUrl/api/v2/favorites?page=1", Fetch.XHR, ensureSuccess = false).use { response ->
                when (response.code) {
                    200 -> "有效：已登录（搜索 20 次/分）"
                    401 -> "无效：服务器拒绝了这个 Key（401）"
                    403 -> "被拒绝（403）"
                    429 -> "限流中（429），稍后再试"
                    else -> "异常响应：HTTP ${response.code}"
                }
            }
        } catch (e: Exception) {
            "请求失败：${e.message}"
        }
    }

    private suspend fun detail(id: Int): GalleryDetail {
        detailCache[id]?.let { return it }

        val fetched = client.get("$baseUrl/api/v2/galleries/$id", Fetch.XHR).parseAs<GalleryDetail>()
        detailCache[id] = fetched
        return fetched
    }

    /**
     * The server list is fetched at runtime on purpose: the API forbids hardcoding
     * CDN subdomains and bans clients that request unknown media routes.
     */
    private suspend fun cdnConfig(): CdnConfig {
        cachedCdn?.let { return it }

        val fetched = runCatching { client.get("$baseUrl/api/v2/cdn", Fetch.XHR).parseAs<CdnConfig>() }.getOrNull()
        val resolved = fetched
            ?.takeIf { it.imageServers.isNotEmpty() && it.thumbServers.isNotEmpty() }
            ?: FALLBACK_CDN

        cachedCdn = resolved
        return resolved
    }

    private fun mediaUrl(servers: List<String>, index: Int, path: String): String {
        val server = servers[index % servers.size]
        return "$server/${path.trimStart('/')}"
    }

    private fun galleryIdOfManga(manga: SManga): Int = galleryIdOrThrow(manga.url)

    private fun galleryIdOrThrow(url: String): Int =
        url.trimEnd('/').substringAfterLast('/').toIntOrNull()
            ?: throw Exception("Unsupported gallery URL: $url")

    private fun galleryIdOf(path: String): Int? = GALLERY_PATH.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()

    // ============================== Filters ==============================

    private open class UriPartFilter(
        displayName: String,
        private val options: Array<Pair<String, String>>,
    ) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = options[state].second
    }

    private class SortFilter : UriPartFilter(
        "排序",
        arrayOf(
            Pair("最新上传", "date"),
            Pair("最热（累计）", "popular"),
            Pair("今日热门", "popular-today"),
            Pair("本周热门", "popular-week"),
            Pair("本月热门", "popular-month"),
        ),
    )

    private class LanguageFilter : UriPartFilter(
        "语言",
        arrayOf(
            Pair("全部", ""),
            Pair("日文", "japanese"),
            Pair("英文", "english"),
            Pair("中文", "chinese"),
        ),
    )
}

/**
 * Sliding-window limiter, matching how the API counts requests. The permit count
 * depends on whether an API key is configured, so it cannot be a fixed
 * [rateLimit] rule on the client builder.
 */
private class SlidingWindowGate {
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val stamps = ArrayDeque<Long>()

    fun acquire(permits: Int) = lock.withLock {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            while (stamps.isNotEmpty() && now - stamps.first() >= WINDOW_MS) {
                stamps.removeFirst()
            }

            if (stamps.size < permits) {
                stamps.addLast(now)
                return@withLock
            }

            condition.await(WINDOW_MS - (now - stamps.first()), TimeUnit.MILLISECONDS)
        }
    }
}

internal const val API_KEY_PREF = "api_key"

private const val API_HOST = "nhentai.net"
private const val WILDCARD_QUERY = "*"
private const val DATE_SORT = "date"
private const val POPULAR_SORT = "popular"
private const val ANON_PERMITS = 10
private const val KEYED_PERMITS = 20
private const val WINDOW_MS = 60_000L
private const val IMAGE_PERMITS = 120
private const val DETAIL_CACHE_SIZE = 16
private const val TAG = "NHentaiApi"
private val IMAGE_INTERVAL = 250.milliseconds
private val GALLERY_PATH = Regex("""^/g/(\d+)/?$""")
private val FALLBACK_CDN = CdnConfig(
    imageServers = listOf("https://i1.nhentai.net"),
    thumbServers = listOf("https://t1.nhentai.net"),
)
