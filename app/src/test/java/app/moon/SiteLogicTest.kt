package app.moon

import app.moon.data.Link
import app.moon.data.MangaItem
import app.moon.data.net.CookieModeInterceptor
import app.moon.data.site.NudeMoonApi
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
    private val siteBookmarks = CopyOnWriteArrayList(listOf("/101--t.html", "/102--t.html"))
    /** Как сайт удаляет из избранного и показывает ли ссылки удаления на странице избранного. */
    @Volatile private var removeParam = "del"
    @Volatile private var showRemoveLinks = true

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
                    path.startsWith("/favorites?did=") && auth -> {
                        val q = path.substringAfter('?').split('&')
                        val id = q.first { it.startsWith("did=") }.substringAfter('=')
                        val url = "/$id--t.html"
                        when {
                            "add" in q -> siteBookmarks.addIfAbsent(url)
                            removeParam in q -> siteBookmarks.remove(url)
                        }
                        "<html><body>ok</body></html>"
                    }
                    path.startsWith("/favorites") && auth -> {
                        // по 2 на страницу, пагинация rowstart, ссылки удаления — если сайт их показывает
                        val start = Regex("rowstart=(\\d+)").find(path)?.groupValues?.get(1)?.toInt() ?: 0
                        val page = siteBookmarks.toList().drop(start).take(2)
                        "<html><body>" + page.joinToString("") { u ->
                            val id = u.removePrefix("/").substringBefore("--")
                            "<div class='f'><a href='$u'>Тайтл $id</a>" + (if (showRemoveLinks) " <a href='favorites?did=$id&$removeParam'>x</a>" else "") + "</div>"
                        } + (0 until siteBookmarks.size step 2).joinToString("") { "<a href='/favorites?rowstart=$it'>${it / 2 + 1}</a>" } + "</body></html>"
                    }
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
        assertEquals(SectionKind.LOGIN_ONLY, c.value.sections.first { it.name == "Закладки" }.kind)
        assertTrue("Закладки" !in tabs) // избранное сайта живёт во вкладке «Избранное» приложения
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
        assertEquals(setOf("Для своих", "О сайте"), c.value.tabs.map { it.name }.toSet())
    }

    private fun favSync(): Pair<Library, SiteFavorites> {
        val library = Library(JsonStore(java.io.File(dir, "l.json"), LibraryData.serializer(), LibraryData(), scope))
        val sync = SiteFavorites(
            JsonStore(java.io.File(dir, "s.json"), SyncData.serializer(), SyncData(), scope),
            library, { api }, { "/favorites" }, { loggedIn }, scope, {},
        )
        library.onUserToggle = { item, added -> sync.onLocalToggle(item, added) }
        return library to sync
    }

    private fun Library.urls() = value.favorites.map { it.manga.url }.toSet()

    @Test
    fun favoritesSyncBothWays() = runBlocking<Unit> {
        // на сайте 5 тайтлов (3 страницы по 2) — проверяем и пагинацию
        siteBookmarks.addAll(listOf("/103--t.html", "/105--t.html", "/106--t.html"))
        val (library, sync) = favSync()
        library.addFavoriteSilently(MangaItem("/102--t.html", "B"))
        library.addFavoriteSilently(MangaItem("/104--t.html", "D"))

        // 1) первая синхронизация: всё с сайта приходит, 104 уходит на сайт ссылкой «В избранное!»
        sync.sync().join()
        val all = setOf("/101--t.html", "/102--t.html", "/103--t.html", "/104--t.html", "/105--t.html", "/106--t.html")
        assertEquals(all, library.urls())
        assertEquals(all, siteBookmarks.toSet())
        assertEquals(6, sync.state.value.siteCount)

        // 2) на сайте удалили 101 → пропадает из приложения
        siteBookmarks.remove("/101--t.html")
        sync.sync().join()
        assertTrue("/101--t.html" !in library.urls())

        // 3) в приложении убрали 102 → убирается на сайте (ссылкой со страницы избранного)
        library.toggleFavorite(MangaItem("/102--t.html", "B"))
        waitUntil { "/102--t.html" !in siteBookmarks && sync.state.value.pendingRemove.isEmpty() }
        assertEquals("link", sync.state.value.removeHint)

        // 4) ♥ на новом тайтле → появляется на сайте
        library.toggleFavorite(MangaItem("/107--t.html", "G"))
        waitUntil { "/107--t.html" in siteBookmarks && sync.state.value.pendingAdd.isEmpty() }

        // 5) пустой ответ сайта не стирает избранное
        val saved = siteBookmarks.toList()
        siteBookmarks.clear()
        sync.sync().join()
        assertEquals(setOf("/103--t.html", "/104--t.html", "/105--t.html", "/106--t.html", "/107--t.html"), library.urls())
        siteBookmarks.addAll(saved)
    }

    @Test
    fun removalWithoutLinksFindsWorkingVariant() = runBlocking<Unit> {
        showRemoveLinks = false
        removeParam = "delete"
        val (library, sync) = favSync()
        sync.sync().join()
        library.toggleFavorite(MangaItem("/101--t.html", "A"))
        waitUntil { "/101--t.html" !in siteBookmarks && sync.state.value.pendingRemove.isEmpty() }
        assertEquals("delete", sync.state.value.removeHint)
        assertEquals(null, sync.state.value.status)
    }

    @Test
    fun guestRequestsDoNotTouchSession() = runBlocking<Unit> {
        val saved = CopyOnWriteArrayList<String>()
        val jar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { saved += it.name }
            }
            override fun loadForRequest(url: HttpUrl) = listOf(
                Cookie.parse(url, "fusion_user=7")!!, Cookie.parse(url, "PHPSESSID=s")!!,
                Cookie.parse(url, "cf_clearance=c")!!, Cookie.parse(url, "nm_mobile=0")!!,
            )
        }
        val headers = CopyOnWriteArrayList<String>()
        val srv = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    headers += request.getHeader("Cookie").orEmpty()
                    return MockResponse().addHeader("Set-Cookie", "PHPSESSID=new; Path=/").setBody("<html></html>")
                }
            }
            start()
        }
        val c = OkHttpClient.Builder().cookieJar(jar).addNetworkInterceptor(CookieModeInterceptor()).build()
        val a = NudeMoonApi(c, { srv.url("/").toString().trimEnd('/') }, { true })
        a.document(a.base + "/", app.moon.data.net.CookieMode.GUEST)
        assertEquals(emptyList<String>(), saved.toList())
        assertEquals("cf_clearance=c; NMfYa=1; nm_mobile=1", headers.last())
        a.document(a.base + "/")
        assertEquals(listOf("PHPSESSID"), saved.toList())
        assertEquals("fusion_user=7; PHPSESSID=s; cf_clearance=c; NMfYa=1; nm_mobile=1", headers.last())
        srv.shutdown()
    }

    @Test
    fun expiredSessionTurns404IntoLogin() = runBlocking<Unit> {
        var cookieSaysLoggedIn = true
        var expiredCalls = 0
        val srv = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    // сайт уже считает нас гостем: на главной форма входа, закрытый тайтл — 404
                    "/" -> MockResponse().setBody("<html><body><form name='loginform'><input name='user_pass' type='password'></form></body></html>")
                    "/500--empty.html" -> MockResponse().setBody("<html><body><p>нет картинок</p></body></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        val a = NudeMoonApi(OkHttpClient(), { srv.url("/").toString().trimEnd('/') }, { cookieSaysLoggedIn }) {
            expiredCalls++
            cookieSaysLoggedIn = false
        }
        val e = runCatching { a.details("/400--closed.html") }.exceptionOrNull()
        assertTrue(e.toString(), e is app.moon.data.net.AuthRequiredException)
        assertEquals("Сессия на сайте истекла — войди снова", e!!.message)
        assertEquals(1, expiredCalls)

        // вошли, а картинок нет — честное сообщение вместо «404»
        cookieSaysLoggedIn = true
        val srv2 = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html><body><a href='/setuser.php?logout=yes'>Выход</a></body></html>")
            }
            start()
        }
        val b = NudeMoonApi(OkHttpClient(), { srv2.url("/").toString().trimEnd('/') }, { true })
        val e2 = runCatching { b.pages("/500--empty.html") }.exceptionOrNull()
        assertTrue(e2.toString(), e2 is app.moon.data.net.NoPagesException)
        assertTrue(b.errorLog.first().contains("нет картинок"))
        srv.shutdown()
        srv2.shutdown()
    }

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "не дождались" }
            Thread.sleep(20)
        }
    }
}
