package app.moon.data.site

import app.moon.data.Chapter
import app.moon.data.Link
import app.moon.data.MangaDetails
import app.moon.data.MangaItem
import app.moon.data.MangaPage
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar

/**
 * Разбор HTML nude-moon. Селекторы взяты из открытого парсера Mihon/Tachiyomi (ru.nudemoon v33),
 * поверх них — мягкие запасные варианты.
 */
object NudeMoonParser {
    const val ITEM = "table.news_pic2"
    private const val NEXT = "a.small:contains(>)"
    private val DATE_RE = Regex("""\b(\d{1,2})\s+([А-Яа-яЁё]+)\s+((?:19|20)\d{2})\b""")
    private val PAGES_RE = Regex("""(\d{1,4})\s*(?:стр\.|страниц|стр\b|pages)""", RegexOption.IGNORE_CASE)
    private val PAGES_RE2 = Regex("""(?:Страниц|Страницы|Стр\.)\s*:?\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
    private val IMG_URL_RE = Regex("""["'](https?://[^"'\s]+?\.(?:jpe?g|png|webp|gif))["']""", RegexOption.IGNORE_CASE)

    fun cleanTitle(raw: String): String = raw.substringBefore(" / ").substringBefore(" №").trim()

    /** Путь от корня сайта: так закладки переживают смену домена. */
    fun path(absUrl: String): String {
        val u = absUrl.toHttpUrlOrNull() ?: return absUrl
        return u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "")
    }

    fun parseList(doc: Document): MangaPage {
        val items = doc.select(ITEM).mapNotNull(::parseItem).distinctBy { it.url }
        val next = nextPageUrl(doc)
        return MangaPage(items, next != null, next)
    }

    fun nextPageUrl(doc: Document): String? {
        val links = doc.select(NEXT)
        val exact = links.firstOrNull { it.text().trim() == ">" } ?: links.firstOrNull { !it.text().contains(">>") }
        return exact?.absUrl("href")?.takeIf { it.isNotBlank() }
    }

    fun parseItem(el: Element): MangaItem? {
        val a = el.selectFirst("a:has(h2)") ?: return null
        val href = a.absUrl("href").ifBlank { return null }
        val title = cleanTitle(a.text()).ifBlank { return null }
        return MangaItem(
            url = path(href),
            title = title,
            cover = imageSrc(el.selectFirst("a img")),
            tags = el.select("div.tag-links a").map { it.text().trim() }.filter { it.isNotEmpty() },
            author = el.selectFirst("a[href*=mangaka]")?.text()?.trim()?.ifBlank { null },
            pages = pagesOf(el.text()),
        )
    }

    private fun imageSrc(img: Element?): String? {
        img ?: return null
        val candidates = listOf("data-src", "data-original", "src")
        for (attr in candidates) {
            val v = img.absUrl(attr)
            if (v.isNotBlank() && !v.startsWith("data:")) return v
        }
        return null
    }

    private fun pagesOf(text: String): Int? =
        (PAGES_RE2.find(text) ?: PAGES_RE.find(text))?.groupValues?.get(1)?.toIntOrNull()

    fun parseDetails(doc: Document, path: String): MangaDetails {
        val info = doc.selectFirst(ITEM)
        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.ifBlank { null }
            ?: imageSrc(info?.selectFirst("a img"))
        val author = (info ?: doc).selectFirst("a[href*=mangaka]")?.let { Link(it.text().trim(), it.absUrl("href")) }
        val translator = (info ?: doc).selectFirst("a[href*=perevod]")?.let { Link(it.text().trim(), it.absUrl("href")) }
        val tags = (info?.select("div.tag-links a") ?: doc.select("div.tag-links a"))
            .map { Link(it.text().trim(), it.absUrl("href")) }
            .filter { it.name.isNotEmpty() }
            .distinctBy { it.name }
        val dateText = doc.selectFirst("td:has(img[src*=time]) span.small2")?.text()
            ?: info?.selectFirst("span.small2")?.text()
        return MangaDetails(
            url = path,
            title = cleanTitle(h1).ifBlank { doc.title().substringBefore(" / ").trim() },
            fullTitle = h1,
            cover = cover,
            author = author?.takeIf { it.name.isNotEmpty() },
            translator = translator?.takeIf { it.name.isNotEmpty() },
            tags = tags,
            description = doc.selectFirst(".description")?.text()?.trim()?.ifBlank { null },
            date = parseDate(dateText),
            pages = info?.let { pagesOf(it.text()) },
            chapters = emptyList(),
        )
    }

    /** Ссылка «Все главы», если тайтл многоглавный. */
    fun allChaptersUrl(doc: Document): String? =
        doc.selectFirst("td.button a:contains(Все главы)")?.absUrl("href")?.ifBlank { null }
            ?: doc.selectFirst("a:contains(Все главы)")?.absUrl("href")?.ifBlank { null }

    fun parseChapterList(doc: Document): List<Chapter> = doc.select(ITEM).mapNotNull { el ->
        val a = el.selectFirst("tr[valign=top] a:has(h2)") ?: el.selectFirst("a:has(h2)") ?: return@mapNotNull null
        val name = a.selectFirst("h2")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
        val href = a.absUrl("href").ifBlank { return@mapNotNull null }
        val inform = el.selectFirst("tr[valign=top] td[align=left]") ?: el
        Chapter(
            url = path(href),
            name = name,
            number = chapterNumber(name),
            date = parseDate(inform.selectFirst("span.small2")?.text()),
            translator = inform.selectFirst("a[href*=perevod]")?.text()?.trim()?.ifBlank { null },
        )
    }

    fun singleChapter(doc: Document, path: String): Chapter {
        val name = doc.selectFirst("table td.bg_style1 h1")?.text()?.substringAfter("/")?.trim()
            ?.ifBlank { null }
        return Chapter(
            url = path,
            name = name?.let { "Сингл: $it" } ?: "Читать",
            number = 0f,
            date = parseDate(doc.selectFirst("td:has(img[src*=time]) span.small2")?.text()),
            translator = doc.selectFirst("table.news_pic2 a[href*=perevod]")?.text()?.trim()?.ifBlank { null },
        )
    }

    fun chapterNumber(name: String): Float {
        if (!name.contains('№')) return -1f
        return name.substringAfter("№").trim().substringBefore(" ").replace("-", ".").replace(",", ".")
            .toFloatOrNull() ?: -1f
    }

    fun parsePages(doc: Document): List<String> {
        val main = doc.select("""img[title~=.+][loading="lazy"]""").mapNotNull { imageSrc(it) }
        if (main.isNotEmpty()) return main.distinct()
        // запасной вариант: ленивые картинки внутри контента
        val lazy = doc.select("img[data-src]").map { it.absUrl("data-src") }
            .filter { it.isNotBlank() && IMG_EXT.containsMatchIn(it) && !it.contains("/images/") }
        if (lazy.size >= 2) return lazy.distinct()
        // старый формат: массив картинок в <script>
        val fromScripts = doc.select("script").flatMap { s ->
            val d = s.data()
            if (d.contains(".src") || d.contains("images")) IMG_URL_RE.findAll(d).map { it.groupValues[1] }.toList() else emptyList()
        }
        return fromScripts.distinct()
    }

    private val MENU_EXCLUDE = listOf(
        "/tags", "mangaka", "perevod", "profile", "login", "logout", "register", "setuser", "lostpassword",
        "password", "messages", "forum", "rules", "contact", "faq", "search", "members", "edit_profile",
        "submit", "readmore", "rss", "/user", "upload", "comments", "print.php", "javascript:", "mailto:",
        "/all_manga", "rowstart=", "#",
    )

    /**
     * Ссылки-кандидаты в категории: все внутренние ссылки страницы, кроме карточек тайтлов,
     * тегов/авторов/служебных страниц. Какие из них реально списки манги — проверяет [app.moon.data.store.Categories].
     */
    fun parseMenuLinks(doc: Document): List<Link> {
        val host = doc.location().toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return emptyList()
        val out = LinkedHashMap<String, Link>()
        for (a in doc.select("a[href]")) {
            if (a.parents().any { it.hasClass("news_pic2") || it.tagName() == "footer" }) continue
            val href = a.absUrl("href")
            val u = href.toHttpUrlOrNull() ?: continue
            if (u.host.removePrefix("www.") != host) continue
            val p = u.encodedPath
            if (p == "/" || p.endsWith("/index.php") || (p.endsWith("/news.php") && u.encodedQuery == null)) continue
            if (p.endsWith(".html") && p.contains("--")) continue
            if (IMG_EXT.containsMatchIn(p)) continue
            val low = href.lowercase()
            if (MENU_EXCLUDE.any { low.contains(it) }) continue
            val text = a.text().trim().ifEmpty { a.attr("title").trim() }
            if (text.length !in 2..40 || text.none { it.isLetter() }) continue
            val key = path(href)
            if (key !in out) out[key] = Link(text, key)
        }
        return out.values.toList()
    }

    private val IMG_EXT = Regex("""\.(?:jpe?g|png|webp|gif)(?:\?|$)""", RegexOption.IGNORE_CASE)

    fun parseTagList(doc: Document): List<String> =
        doc.select("input[name*=tag]").map { it.attr("value").trim() }.filter { it.isNotEmpty() }.distinct()

    /** «12 января 2024» / «12 январь 2024» → millis; 0 если не распознано. */
    fun parseDate(text: String?): Long {
        text ?: return 0L
        val m = DATE_RE.find(text) ?: return 0L
        val day = m.groupValues[1].toInt()
        val month = monthOf(m.groupValues[2].lowercase()) ?: return 0L
        val year = m.groupValues[3].toInt()
        return Calendar.getInstance().apply {
            clear()
            set(year, month, day, 12, 0, 0)
        }.timeInMillis
    }

    private fun monthOf(w: String): Int? = when {
        w.startsWith("янв") -> 0
        w.startsWith("фев") -> 1
        w.startsWith("мар") -> 2
        w.startsWith("апр") -> 3
        w.startsWith("ма") -> 4
        w.startsWith("июн") -> 5
        w.startsWith("июл") -> 6
        w.startsWith("авг") -> 7
        w.startsWith("сен") -> 8
        w.startsWith("окт") -> 9
        w.startsWith("ноя") -> 10
        w.startsWith("дек") -> 11
        else -> null
    }
}
