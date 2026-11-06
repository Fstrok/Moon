package app.moon.data.site

import app.moon.data.MangaDetails
import app.moon.data.MangaPage
import app.moon.data.Sort
import app.moon.data.net.AuthRequiredException
import app.moon.data.net.HttpError
import app.moon.data.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

class NudeMoonApi(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
    private val isLoggedIn: () -> Boolean,
) {
    val base: String get() = baseUrl().trimEnd('/')

    private val detailsCache = ConcurrentHashMap<String, MangaDetails>()
    private var tagsCache: List<String>? = null

    fun abs(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("http") -> pathOrUrl
        pathOrUrl.startsWith("/") -> base + pathOrUrl
        else -> "$base/$pathOrUrl"
    }

    suspend fun document(url: String): Document {
        val resp = client.newCall(Request.Builder().url(url).build()).await()
        return withContext(Dispatchers.IO) {
            resp.use {
                if (!it.isSuccessful) throw HttpError(it.code, url)
                val body = it.body ?: throw HttpError(it.code, url)
                val charset = body.contentType()?.charset()?.name()
                Jsoup.parse(body.byteStream(), charset, it.request.url.toString())
            }
        }
    }

    suspend fun catalog(sort: Sort, page: Int): MangaPage =
        NudeMoonParser.parseList(document("$base/all_manga?${sort.key}&rowstart=${PAGE_SIZE * page}"))

    suspend fun byTags(tags: List<String>, sort: Sort, page: Int): MangaPage {
        val seg = tags.joinToString("+") { it.trim().replace(' ', '_') }
        val url = base.toHttpUrl().newBuilder()
            .addPathSegment("tags")
            .addPathSegment("$seg&${sort.key}&rowstart=${PAGE_SIZE * page}")
            .build()
        return NudeMoonParser.parseList(document(url.toString()))
    }

    suspend fun search(query: String, page: Int): MangaPage {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("stext", query)
            .addQueryParameter("rowstart", (PAGE_SIZE * page).toString())
            .build()
        return try {
            NudeMoonParser.parseList(document(url.toString()))
        } catch (e: HttpError) {
            if (e.code == 404 && !isLoggedIn()) throw AuthRequiredException() else throw e
        }
    }

    /** Любая страница-список сайта (автор, переводчик, тег): листаем по ссылке «>». */
    suspend fun listing(url: String): MangaPage = NudeMoonParser.parseList(document(abs(url)))

    suspend fun tagList(force: Boolean = false): List<String> {
        if (!force) tagsCache?.let { return it }
        return NudeMoonParser.parseTagList(document("$base/tags/")).also { tagsCache = it }
    }

    /** Вход/выход меняют то, что показывает сайт. */
    fun invalidate() {
        tagsCache = null
        detailsCache.clear()
    }

    fun cachedDetails(path: String): MangaDetails? = detailsCache[path]

    suspend fun details(path: String, force: Boolean = false): MangaDetails {
        if (!force) detailsCache[path]?.let { return it }
        val doc = document(abs(path))
        val base = NudeMoonParser.parseDetails(doc, path)
        val allUrl = NudeMoonParser.allChaptersUrl(doc)
        val chapters = if (allUrl == null) {
            listOf(NudeMoonParser.singleChapter(doc, path))
        } else {
            val list = mutableListOf<app.moon.data.Chapter>()
            var next: String? = allUrl
            val seen = HashSet<String>()
            while (next != null && seen.add(next) && seen.size <= 50) {
                val page = document(next)
                list += NudeMoonParser.parseChapterList(page)
                next = NudeMoonParser.nextPageUrl(page)
            }
            list.distinctBy { it.url }.ifEmpty { listOf(NudeMoonParser.singleChapter(doc, path)) }
        }
        return base.copy(chapters = chapters).also { detailsCache[path] = it }
    }

    suspend fun pages(chapterPath: String): List<String> {
        val pages = NudeMoonParser.parsePages(document(abs(chapterPath)))
        if (pages.isEmpty()) {
            throw if (!isLoggedIn()) AuthRequiredException() else HttpError(404, chapterPath)
        }
        return pages
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}
