package app.moon.data.site

import app.moon.data.MangaDetails
import app.moon.data.MangaPage
import app.moon.data.Sort
import app.moon.data.net.AuthRequiredException
import app.moon.data.net.CookieMode
import app.moon.data.net.HttpError
import app.moon.data.net.NoPagesException
import app.moon.data.net.ReadOnlyCookieJar
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
    /** Сайт показал гостевую страницу при cookie входа — сессия закончилась. */
    private val onSessionExpired: () -> Unit = {},
) {
    val base: String get() = baseUrl().trimEnd('/')

    /** Для запросов «как гость»/«десктоп»: cookies читаются, но ответы их не меняют — сессия не портится. */
    private val isolated: OkHttpClient by lazy { client.newBuilder().cookieJar(ReadOnlyCookieJar(client.cookieJar)).build() }

    /** Последние сбои — для отчёта диагностики. */
    val errorLog = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun logError(what: String) {
        errorLog.addFirst("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())} $what")
        while (errorLog.size > 25) errorLog.pollLast()
    }

    private val detailsCache = ConcurrentHashMap<String, MangaDetails>()
    private var tagsCache: List<String>? = null

    fun abs(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("http") -> pathOrUrl
        pathOrUrl.startsWith("/") -> base + pathOrUrl
        else -> "$base/$pathOrUrl"
    }

    @Volatile
    private var lastLayoutFix = 0L

    suspend fun document(url: String, mode: CookieMode = CookieMode.NORMAL): Document {
        val doc = fetch(url, mode)
        // разбор написан под мобильную вёрстку; если сайт запомнил «обычную» — переключаем его ссылкой сайта
        if (mode == CookieMode.NORMAL && NudeMoonParser.isDesktopLayout(doc) &&
            System.currentTimeMillis() - lastLayoutFix > 60_000
        ) {
            lastLayoutFix = System.currentTimeMillis()
            logError("сайт отдал обычную вёрстку, переключаю на мобильную")
            runCatching { fetch("$base/redi.php?mobile", mode) }
            return fetch(url, mode)
        }
        return doc
    }

    private suspend fun fetch(url: String, mode: CookieMode): Document {
        val c = if (mode == CookieMode.NORMAL) client else isolated
        val resp = c.newCall(Request.Builder().url(url).tag(CookieMode::class.java, mode).build()).await()
        val doc = withContext(Dispatchers.IO) {
            resp.use {
                if (!it.isSuccessful) {
                    logError("HTTP ${it.code} $url")
                    throw HttpError(it.code, url)
                }
                val body = it.body ?: throw HttpError(it.code, url)
                val charset = body.contentType()?.charset()?.name()
                Jsoup.parse(body.byteStream(), charset, it.request.url.toString())
            }
        }
        if (mode == CookieMode.NORMAL && isLoggedIn() && NudeMoonParser.pageLoggedIn(doc) == false) {
            logError("сессия истекла: сайт показал форму входа на $url")
            onSessionExpired()
        }
        return doc
    }

    /** 404 у тайтла: сайт так отвечает и гостям на закрытые тайтлы. Проверяем, жива ли сессия. */
    private suspend fun explain404(e: HttpError): Exception {
        if (!isLoggedIn()) return AuthRequiredException("Тайтл открывается только после входа на сайт")
        runCatching { document("$base/") } // при истёкшей сессии сработает onSessionExpired
        return if (!isLoggedIn()) AuthRequiredException("Сессия на сайте истекла — войди снова") else e
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

    /** Любая страница-список сайта (автор, переводчик, тег, раздел): листаем по ссылке «>». */
    suspend fun listing(url: String): MangaPage = NudeMoonParser.parseListAny(document(abs(url)))

    data class Favorites(val items: List<app.moon.data.MangaItem>, val removeLinks: Map<String, String>)

    /** Все страницы избранного сайта + ссылки удаления. */
    suspend fun favorites(url: String, maxPages: Int = 60): Favorites {
        val items = LinkedHashMap<String, app.moon.data.MangaItem>()
        val remove = HashMap<String, String>()
        var next: String? = abs(url)
        val seen = HashSet<String>()
        while (next != null && seen.add(next) && seen.size <= maxPages) {
            val doc = document(next)
            val page = NudeMoonParser.parseListAny(doc)
            val before = items.size
            page.items.forEach { items.putIfAbsent(it.url, it) }
            remove.putAll(NudeMoonParser.favoriteRemoveLinks(doc))
            if (items.size == before) break
            next = page.nextUrl
        }
        return Favorites(items.values.toList(), remove)
    }

    /** GET действия на сайте (ссылки «В избранное!» и т.п.). Возвращает HTTP-код. */
    suspend fun hit(url: String, referer: String): Int {
        val resp = client.newCall(Request.Builder().url(abs(url)).header("Referer", referer).build()).await()
        return withContext(Dispatchers.IO) { resp.use { it.code } }
    }

    /** Ссылка сайта «В избранное!» со страницы тайтла: favorites?did=ID&add. */
    fun favoriteAddUrl(mangaPath: String): String? = NudeMoonParser.mangaId(mangaPath)?.let { "$base/favorites?did=$it&add" }

    /** Сколько тайтлов в обычной вёрстке списка (table.news_pic2) на странице. */
    suspend fun nativeCount(url: String): Int = NudeMoonParser.parseList(document(abs(url))).items.size

    /** Все страницы списка (закладки и т.п.), не больше [maxPages]. */
    suspend fun listingAll(url: String, maxPages: Int = 30): List<app.moon.data.MangaItem> {
        val out = LinkedHashMap<String, app.moon.data.MangaItem>()
        var next: String? = abs(url)
        val seen = HashSet<String>()
        while (next != null && seen.add(next) && seen.size <= maxPages) {
            val page = NudeMoonParser.parseListAny(document(next))
            page.items.forEach { out.putIfAbsent(it.url, it) }
            next = page.nextUrl
        }
        return out.values.toList()
    }

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
        val doc = try {
            document(abs(path))
        } catch (e: HttpError) {
            throw if (e.code == 404) explain404(e) else e
        }
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
        val url = abs(chapterPath)
        val doc = try {
            document(url)
        } catch (e: HttpError) {
            throw if (e.code == 404) explain404(e) else e
        }
        val pages = NudeMoonParser.parsePages(doc)
        if (pages.isEmpty()) {
            val imgs = doc.select("img").take(6).joinToString(" | ") { it.outerHtml().take(160) }
            logError("нет картинок на $url (title=${doc.title()}) img: $imgs")
            if (!isLoggedIn()) throw AuthRequiredException("Страницы видны только после входа на сайт")
            throw NoPagesException(url)
        }
        return pages
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}
