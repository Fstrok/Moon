package app.moon

import app.moon.data.Link
import app.moon.data.MangaItem
import app.moon.data.net.CookieModeInterceptor
import app.moon.data.site.BookmarkOp
import app.moon.data.site.BookmarkResult
import app.moon.data.site.NudeMoonApi
import app.moon.data.site.SiteActions
import app.moon.data.store.Categories
import app.moon.data.store.CategoriesData
import app.moon.data.store.JsonStore
import app.moon.data.store.Library
import app.moon.data.store.LibraryData
import app.moon.data.store.SectionKind
import app.moon.data.store.SiteFavorites
import app.moon.data.store.SyncData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** Логика разделов и синхронизации избранного на макете сайта (без Android). */
class SiteLogicTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = Files.createTempDirectory("moon").toFile()

    /** Закладки «на сайте». */
    private val siteBookmarks = CopyOnWriteArrayList(listOf("/101--a.html", "/102--b.html"))

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = Regex("fusion_user=[^;\\s]+").containsMatchIn(request.getHeader("Cookie").orEmpty())
                val path = request.path.orEmpty()
                val body = when {
                    path == "/" -> """<html><body><nav>
                        <a href="/doujinshi">Додзинси</a><a href="/about">О сайте</a>
                        ${if (auth) "<a href='/secret'>Для своих</a><a href='/bookmarks'>Закладки</a><a href='/profile.php?lookup=1'>Профиль</a>" else ""}
                        </nav></body></html>"""
                    path.startsWith("/doujinshi") -> listing(5)
                    path.startsWith("/about") -> "<html><body>Правила и контакты</body></html>"
                    path.startsWith("/secret") && auth -> "<html><body><div class='grid'><a href='/301--s.html'>Секрет</a></div></body></html>"
                    path.startsWith("/bookmarks") && auth ->
                        "<html><body>" + siteBookmarks.joinToString("") { "<div class='b'><a href='$it'>Тайтл $it</a></div>" } + "</body></html>"
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
            }
        }
        start()
    }

    private fun listing(n: Int) = "<html><body>" + (1..n).joinToString("") {
        """<table class="news_pic2"><tr><td><a href="/2$it--x.html"><h2>Х $it</h2></a></td></tr></table>"""
    } + "</body></html>"

    private val base = server.url("/").toString().trimEnd('/')
    private var loggedIn = true

    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            if (loggedIn) listOf(Cookie.parse(url, "fusion_user=7")!!, Cookie.parse(url, "nm_mobile=1")!!) else listOf(Cookie.parse(url, "nm_mobile=1")!!)
    }
    private val client = OkHttpClient.Builder().cookieJar(jar).addNetworkInterceptor(CookieModeInterceptor()).build()
    private val api = NudeMoonApi(client, { base }, { loggedIn })

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun categories() = Categories(
        JsonStore(java.io.File(dir, "c.json"), CategoriesData.serializer(), CategoriesData(), scope),
        { api }, { loggedIn }, scope, {},
    )

    @Test
    fun loginOnlySectionsAppearRegardlessOfMarkup() = runBlocking<Unit> {
        val c = categories()
        loggedIn = false
        c.refresh().join()
        assertEquals(listOf("Додзинси"), c.value.tabs.map { it.name })
        assertEquals(SectionKind.OTHER, c.value.sections.first { it.name == "О сайте" }.kind)

        loggedIn = true
        c.refresh().join()
        val tabs = c.value.tabs.associate { it.name to it.kind }
        assertEquals(SectionKind.LISTING, tabs["Додзинси"])
        assertEquals(SectionKind.LOGIN_ONLY, tabs["Для своих"])
        assertEquals(SectionKind.LOGIN_ONLY, tabs["Закладки"])
        assertTrue("Профиль" !in tabs)
        assertEquals("/bookmarks", c.value.bookmarksUrl)
        assertEquals("1", c.value.username)
        assertTrue("/secret" in c.value.fresh)
        // раздел со своей вёрсткой разбирается универсальным парсером
        assertEquals(listOf("/301--s.html"), api.listing("/secret").items.map { it.url })

        // ручное закрепление прочей ссылки и скрытие авто-вкладки
        val about = c.value.sections.first { it.name == "О сайте" }
        c.setVisible(about, true)
        c.setVisible(c.value.sections.first { it.name == "Додзинси" }, false)
        assertEquals(setOf("Для своих", "Закладки", "О сайте"), c.value.tabs.map { it.name }.toSet())
    }

    @Test
    fun favoritesSyncBothWays() = runBlocking<Unit> {
        val library = Library(JsonStore(java.io.File(dir, "l.json"), LibraryData.serializer(), LibraryData(), scope))
        val actions = SiteActions()
        val ops = CopyOnWriteArrayList<String>()
        // «сайт»: выполняем нажатия кнопки закладок
        scope.launch {
            for (op: BookmarkOp in actions.ops) {
                ops += (if (op.on) "+" else "-") + op.mangaUrl
                if (op.on) siteBookmarks.addIfAbsent(op.mangaUrl) else siteBookmarks.remove(op.mangaUrl)
                op.result.complete(BookmarkResult.Done)
            }
        }
        val sync = SiteFavorites(
            JsonStore(java.io.File(dir, "s.json"), SyncData.serializer(), SyncData(), scope),
            library, { api }, { "/bookmarks" }, { loggedIn }, actions, scope, {},
        )
        library.onUserToggle = { item, added -> sync.onLocalToggle(item, added) }

        // в приложении: B (есть и на сайте) и C (только в приложении)
        library.addFavoriteSilently(MangaItem("/102--b.html", "B"))
        library.addFavoriteSilently(MangaItem("/103--c.html", "C"))

        // 1) первая синхронизация: A приходит с сайта, C уходит на сайт
        sync.sync().join()
        assertEquals(setOf("/101--a.html", "/102--b.html", "/103--c.html"), library.value.favorites.map { it.manga.url }.toSet())
        assertEquals(listOf("+/103--c.html"), ops)
        assertEquals(setOf("/101--a.html", "/102--b.html", "/103--c.html"), siteBookmarks.toSet())

        // 2) на сайте удалили A → пропадает из приложения
        siteBookmarks.remove("/101--a.html")
        sync.sync().join()
        assertEquals(setOf("/102--b.html", "/103--c.html"), library.value.favorites.map { it.manga.url }.toSet())

        // 3) в приложении убрали B → убирается на сайте
        library.toggleFavorite(MangaItem("/102--b.html", "B"))
        waitUntil { "-/102--b.html" in ops }
        assertEquals(setOf("/103--c.html"), siteBookmarks.toSet())

        // 4) ♥ в приложении на новом тайтле → появляется на сайте
        library.toggleFavorite(MangaItem("/104--d.html", "D"))
        waitUntil { "+/104--d.html" in ops }
        assertEquals(setOf("/103--c.html", "/104--d.html"), siteBookmarks.toSet())

        // 5) сбой загрузки закладок (пустая страница) не стирает избранное
        val saved = siteBookmarks.toList()
        siteBookmarks.clear()
        sync.sync().join()
        assertEquals(setOf("/103--c.html", "/104--d.html"), library.value.favorites.map { it.manga.url }.toSet())
        siteBookmarks.addAll(saved)
    }

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "не дождались" }
            Thread.sleep(20)
        }
    }
}
