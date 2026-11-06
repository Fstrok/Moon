package app.moon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Сквозной сценарий на макете сайта: каталог → тайтл → читалка. Скриншоты — в app/build/screens. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class FlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val server: MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val auth = Regex("fusion_user=[^;\\s]+").containsMatchIn(request.getHeader("Cookie").orEmpty())
                return when {
                    path == "/" -> html(homeHtml(auth))
                    path.startsWith("/doujinshi") || (auth && path.startsWith("/secret")) -> html(listHtml(true))
                    path.startsWith("/about") -> html("<html><body><p>О сайте</p></body></html>")
                    path.startsWith("/all_manga") -> html(listHtml(path.contains("rowstart=0")))
                    path.startsWith("/tags/") -> html(tagsHtml)
                    path.startsWith("/vse_glavy/") -> html(chaptersHtml)
                    path.startsWith("/2001--") || path.startsWith("/2002--") -> html(pagesHtml)
                    path.startsWith("/10") && path.endsWith(".html") -> html(detailsHtml(path))
                    path.startsWith("/img/") -> png(path)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    private fun html(s: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(s)

    private val colors = intArrayOf(0xFF6B5BD2.toInt(), 0xFFD25B8E.toInt(), 0xFF3E9C8F.toInt(), 0xFFC98A2E.toInt(), 0xFF4A6FD1.toInt())

    private fun png(path: String): MockResponse {
        val n = path.filter { it.isDigit() }.toIntOrNull() ?: 0
        val bmp = Bitmap.createBitmap(350, 500, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(colors[n % colors.size])
        val p = Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = 120f; isAntiAlias = true }
        c.drawText("$n", 120f, 290f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(out.toByteArray()))
    }

    /** Меню главной: «Для своих» есть только у вошедших. */
    private fun homeHtml(auth: Boolean) = """
        <html><body><div id="menu">
          <a href="/">Главная</a><a href="/all_manga?date">Каталог</a>
          <a href="/doujinshi">Додзинси</a><a href="/about">О сайте</a><a href="/login.php">Вход</a>
          ${if (auth) "<a href='/secret'>Для своих</a>" else ""}
        </div></body></html>
    """.trimIndent()

    private fun listHtml(first: Boolean): String = buildString {
        append("<html><body>")
        val start = if (first) 0 else 30
        for (i in start until start + 12) {
            val id = 1000 + i
            append(
                """<table class="news_pic2"><tr><td><a href="/$id--title-$i.html"><img src="/img/c$i.png"></a>
                   <a href="/$id--title-$i.html"><h2>Тайтл номер $i / Alt $i</h2></a>
                   <div class="tag-links"><a href="/tags/a">тег</a></div> Страниц: ${10 + i}</td></tr></table>""",
            )
        }
        if (first) append("""<a class="small" href="/all_manga?date&rowstart=30">&gt;</a>""")
        append("</body></html>")
    }

    private fun detailsHtml(path: String): String = """
        <html><head><meta property="og:image" content="/img/c1.png"></head><body>
        <h1>Тайтл номер 1 / Alternative name №1</h1>
        <table class="news_pic2"><tr><td><a href="/mangaka/Ivan">Ivan Petrov</a> <a href="/perevod/Team">Moon Team</a>
          <div class="tag-links"><a href="/tags/x">романтика</a><a href="/tags/y">комедия</a><a href="/tags/z">цвет</a></div>
          Страниц: 24</td></tr></table>
        <div class="description">Короткое описание тайтла, чтобы проверить разметку карточки и сворачивание длинного текста.</div>
        <table><tr><td class="button"><a href="/vse_glavy/1">Все главы</a></td></tr></table>
        <table><tr><td><img src="/img/time.png"> <span class="small2">5 марта 2024</span></td></tr></table>
        </body></html>
    """.trimIndent()

    private val chaptersHtml = """
        <html><body>
        <table class="news_pic2"><tr valign="top"><td><a href="/2002--ch2.html"><h2>Тайтл №2</h2></a></td>
          <td align="left"><a href="/perevod/Team">Moon Team</a><span class="small2">1 января 2025</span></td></tr></table>
        <table class="news_pic2"><tr valign="top"><td><a href="/2001--ch1.html"><h2>Тайтл №1</h2></a></td>
          <td align="left"><span class="small2">12 декабря 2024</span></td></tr></table>
        </body></html>
    """.trimIndent()

    private val pagesHtml = """
        <html><body>
        <img title="1" loading="lazy" data-src="/img/p1.png">
        <img title="2" loading="lazy" data-src="/img/p2.png">
        <img title="3" loading="lazy" data-src="/img/p3.png">
        </body></html>
    """.trimIndent()

    private val tagsHtml = """<html><body><form>${listOf("анал", "романтика", "комедия", "цвет", "big breasts", "школа", "фэнтези", "юри").joinToString("") { "<input name='tag[]' value='$it'>" }}</form></body></html>"""

    private val dir = File("build/screens").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Ждать условие, давая отработать главному потоку (корутины, сеть). */
    private fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(100)
        }
        error("условие не выполнилось за $timeoutMs мс; категории=" + App.categories.state.value + " reqs=" + server.requestCount)
    }

    private fun waitText(text: String, timeout: Long = 10_000) {
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun catalogDetailsReader() {
        rule.mainClock.advanceTimeBy(500)
        shot("01_lock")
        rule.runOnUiThread {
            App.settings.update { it.copy(baseUrl = server.url("/").toString().trimEnd('/')) }
            App.lock.unlock()
        }
        rule.onNodeWithText("Популярное").performClick()
        waitText("Тайтл номер 3")
        Thread.sleep(800)
        shot("02_catalog")

        rule.onNodeWithText("Тайтл номер 1").performClick()
        waitText("Moon Team")
        Thread.sleep(800)
        shot("03_details")

        rule.onNodeWithText("Читать").performClick()
        waitFor { App.library.value.history.isNotEmpty() }
        Thread.sleep(1500)
        rule.waitForIdle()
        shot("04_reader")
        check(App.library.value.history.isNotEmpty()) { "прогресс не сохранился" }

        rule.onAllNodesWithText("Конец главы").fetchSemanticsNodes()
        rule.runOnUiThread { App.settings.update { it.copy(readerMode = app.moon.data.ReaderMode.PAGER_LTR) } }
        Thread.sleep(800)
        shot("05_reader_pager")
    }

    @Test
    fun categoriesAppearAfterLogin() {
        val base = server.url("/").toString().trimEnd('/')
        rule.runOnUiThread {
            App.settings.update { it.copy(baseUrl = base) }
            App.lock.unlock()
            App.categories.refresh()
        }
        waitFor { App.categories.state.value.list.isNotEmpty() && !App.categories.refreshing.value }
        check(App.categories.state.value.list.map { it.name } == listOf("Додзинси")) { App.categories.state.value.list.toString() }
        waitText("Додзинси")

        // «вход»: сайт ставит cookie — приложение само пересобирает категории
        rule.runOnUiThread {
            android.webkit.CookieManager.getInstance().setCookie(base, "fusion_user=42; Path=/")
            App.session.refresh()
        }
        waitFor { App.categories.state.value.list.any { it.name == "Для своих" } }
        check("/secret" in App.categories.state.value.fresh) { "новая категория не помечена" }
        waitText("Для своих")
        Thread.sleep(500)
        shot("08_categories")
        rule.onNodeWithText("Для своих").performClick()
        waitText("Тайтл номер 4")

        // выход — категория пропадает
        rule.runOnUiThread {
            // заглушка CookieManager в Robolectric не удаляет просроченные cookie, поэтому сбрасываем все
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            App.session.refresh()
        }
        waitFor { App.categories.state.value.list.none { it.name == "Для своих" } }
    }

    @Test
    fun loginScreen() {
        rule.runOnUiThread { App.lock.unlock() }
        rule.onNodeWithText("Ещё").performClick()
        rule.onAllNodesWithText("Войти").onFirst().performClick()
        waitText("Вход на сайт")
        shot("09_login")
    }

    @Test
    fun tagsScreen() {
        rule.runOnUiThread {
            App.settings.update { it.copy(baseUrl = server.url("/").toString().trimEnd('/')) }
            App.lock.unlock()
        }
        rule.onNodeWithText("Ещё").performClick()
        waitText("Вход не выполнен")
        shot("06_more")
        rule.onAllNodesWithText("Теги").onFirst().performClick()
        waitText("романтика")
        rule.onNodeWithText("романтика").performClick()
        rule.onNodeWithText("цвет").performClick()
        shot("07_tags")
    }
}
