package app.moon

import app.moon.data.site.NudeMoonParser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Синтетический HTML в структуре, которую ожидает парсер (table.news_pic2 и т.д.). */
class ParserTest {
    private val base = "https://nude-moon.org"

    private val listHtml = """
        <html><body>
        <table class="news_pic2"><tr><td>
          <a href="/1001--title-one.html"><img src="/thumb/1.jpg"></a>
          <a href="/1001--title-one.html"><h2>Первый / First One №1</h2></a>
          <a href="/mangaka/Author">Author</a>
          <div class="tag-links"><a href="/tags/tag_a">tag a</a><a href="/tags/b">b</a></div>
          Страниц: 24
        </td></tr></table>
        <table class="news_pic2"><tr><td>
          <a href="/1002--title-two.html"><img data-src="https://cdn.example/2.jpg" src="data:image/gif;base64,AAA"></a>
          <a href="/1002--title-two.html"><h2>Второй</h2></a>
        </td></tr></table>
        <table class="news_pic2"><tr><td>реклама без ссылки</td></tr></table>
        <a class="small" href="/all_manga?date&rowstart=0">&lt;</a>
        <a class="small" href="/all_manga?date&rowstart=30">&gt;</a>
        <a class="small" href="/all_manga?date&rowstart=900">&gt;&gt;</a>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesListing() {
        val page = NudeMoonParser.parseList(Jsoup.parse(listHtml, "$base/all_manga?date&rowstart=0"))
        assertEquals(2, page.items.size)
        val a = page.items[0]
        assertEquals("/1001--title-one.html", a.url)
        assertEquals("Первый", a.title)
        assertEquals("$base/thumb/1.jpg", a.cover)
        assertEquals(listOf("tag a", "b"), a.tags)
        assertEquals("Author", a.author)
        assertEquals(24, a.pages)
        assertEquals("https://cdn.example/2.jpg", page.items[1].cover)
        assertTrue(page.hasNext)
        assertEquals("$base/all_manga?date&rowstart=30", page.nextUrl)
    }

    @Test
    fun noNextOnLastPage() {
        val doc = Jsoup.parse("<html><body><a class='small' href='/x?rowstart=0'>&lt;</a></body></html>", base)
        assertNull(NudeMoonParser.nextPageUrl(doc))
    }

    @Test
    fun parsesDetailsAndChapters() {
        val html = """
            <html><head><meta property="og:image" content="https://nude-moon.org/cover.jpg"></head><body>
            <h1>Название / Alt Name №3</h1>
            <table class="news_pic2"><tr><td>
              <a href="/mangaka/Ivan">Ivan</a> <a href="/perevod/Team">Team</a>
              <div class="tag-links"><a href="/tags/x">x</a><a href="/tags/y">y</a></div>
            </td></tr></table>
            <div class="description">Описание тайтла</div>
            <table><tr><td class="button"><a href="/vse_glavy/123">Все главы</a></td></tr></table>
            <table><tr><td><img src="/img/time.png"> <span class="small2">5 марта 2024</span></td></tr></table>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "$base/123--name.html")
        val d = NudeMoonParser.parseDetails(doc, "/123--name.html")
        assertEquals("Название", d.title)
        assertEquals("Название / Alt Name №3", d.fullTitle)
        assertEquals("https://nude-moon.org/cover.jpg", d.cover)
        assertEquals("Ivan", d.author?.name)
        assertEquals("Team", d.translator?.name)
        assertEquals(listOf("x", "y"), d.tags.map { it.name })
        assertEquals("Описание тайтла", d.description)
        val cal = Calendar.getInstance().apply { timeInMillis = d.date }
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
        assertEquals(5, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals("$base/vse_glavy/123", NudeMoonParser.allChaptersUrl(doc))

        val chHtml = """
            <html><body>
            <table class="news_pic2"><tr valign="top"><td><a href="/124--ch2.html"><h2>Название №2</h2></a></td>
              <td align="left"><a href="/perevod/Team">Team</a><span class="small2">Дата: 1 января 2023</span></td></tr></table>
            <table class="news_pic2"><tr valign="top"><td><a href="/123--ch1.html"><h2>Название №1</h2></a></td>
              <td align="left"><span class="small2">12 декабрь 2022</span></td></tr></table>
            </body></html>
        """.trimIndent()
        val chapters = NudeMoonParser.parseChapterList(Jsoup.parse(chHtml, "$base/vse_glavy/123"))
        assertEquals(2, chapters.size)
        assertEquals("/124--ch2.html", chapters[0].url)
        assertEquals(2f, chapters[0].number)
        assertEquals("Team", chapters[0].translator)
        assertTrue(chapters[1].date > 0)
        val ordered = d.copy(chapters = chapters).readingOrder
        assertEquals("/123--ch1.html", ordered.first().url)
    }

    @Test
    fun parsesPages() {
        val html = """
            <html><body>
            <img src="/logo.png">
            <img title="1" loading="lazy" data-src="https://img.nude-moon.org/m/1.jpg">
            <img title="2" loading="lazy" data-src="https://img.nude-moon.org/m/2.jpg">
            </body></html>
        """.trimIndent()
        val pages = NudeMoonParser.parsePages(Jsoup.parse(html, base))
        assertEquals(listOf("https://img.nude-moon.org/m/1.jpg", "https://img.nude-moon.org/m/2.jpg"), pages)

        val script = """<html><body><script>var images=[]; images[0].src = 'https://x.org/a/01.jpg'; images[1].src = 'https://x.org/a/02.png';</script></body></html>"""
        assertEquals(2, NudeMoonParser.parsePages(Jsoup.parse(script, base)).size)
    }

    @Test
    fun menuLinks() {
        val html = """
            <html><body>
            <nav>
              <a href="/">Главная</a>
              <a href="/manga">Манга</a>
              <a href="https://nude-moon.org/doujinshi?date">Додзинси</a>
              <a href="/all_manga?views">Каталог</a>
              <a href="/tags/">Теги</a>
              <a href="/login.php">Вход</a>
              <a href="/register.php">Регистрация</a>
              <a href="https://other.site/x">Партнёры</a>
              <a href="/manga">Манга (дубль)</a>
              <a href="#top">Наверх</a>
              <a href="/12">12</a>
            </nav>
            <table class="news_pic2"><tr><td><a href="/777--x.html"><h2>Тайтл</h2></a><a href="/mangaka/A">A</a></td></tr></table>
            <footer><a href="/rules_other">Правила</a></footer>
            </body></html>
        """.trimIndent()
        val links = NudeMoonParser.parseMenuLinks(Jsoup.parse(html, "$base/"))
        assertEquals(listOf("Манга" to "/manga", "Додзинси" to "/doujinshi?date"), links.map { it.name to it.url })
    }

    @Test
    fun tagsAndHelpers() {
        val doc = Jsoup.parse("<form><input name='tag[]' value=' анал '><input name='tag[]' value='big breasts'><input name='q' value='z'></form>", base)
        assertEquals(listOf("анал", "big breasts"), NudeMoonParser.parseTagList(doc))
        assertEquals(3.5f, NudeMoonParser.chapterNumber("Тайтл №3-5 часть"))
        assertEquals(-1f, NudeMoonParser.chapterNumber("Сингл"))
        assertEquals("/a.html?x=1", NudeMoonParser.path("https://nude-moon.org/a.html?x=1"))
        assertNotNull(NudeMoonParser.parseDate("опубликовано 7 мая 2021"))
        assertEquals(0L, NudeMoonParser.parseDate("вчера"))
    }
}
