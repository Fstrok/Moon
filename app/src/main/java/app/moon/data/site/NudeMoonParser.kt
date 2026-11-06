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

    /** Служебные ссылки: никогда не разделы. */
    private val JUNK = listOf(
        "login", "logout", "register", "setuser", "lostpassword", "password", "rss", "print.php",
        "javascript:", "mailto:", "/rules", "contact", "faq",
    )

    /** Ссылки аккаунта: появляются после входа, но это не разделы с мангой. */
    private val ACCOUNT_RE = Regex(
        """profile|edit_profile|messages|/pm\b|notif|settings|logout|setuser|""" +
            """профиль|сообщени|настройк|уведомлен|выход|выйти|личн""",
        RegexOption.IGNORE_CASE,
    )
    private val SORT_KEYS = setOf("date", "views", "like", "com", "pages")
    const val MENU_CONTAINER = "nav, header, [id*=menu], [class*=menu], [id*=nav], [class*=nav], [class*=header], [id*=header]"
    private val SPECIFIC_PAGE = Regex("""^/(tags?|mangaka|perevod)/[^/]+""")
    private val MANGA_PATH = Regex("""/[^/]*--[^/]+\.html$""")
    val BOOKMARKS_RE = Regex("""закладк|избранн|bookmark|favou?rit""", RegexOption.IGNORE_CASE)

    /** HTML блоков меню (для отчёта диагностики), без скриптов. */
    fun menuHtml(doc: Document, limit: Int = 5000): String {
        val copy = doc.clone()
        copy.select("script, style, noscript, svg").remove()
        val blocks = copy.select(MENU_CONTAINER).filter { el -> el.parents().none { it.`is`(MENU_CONTAINER) } }
        return blocks.joinToString("\n") { it.outerHtml() }.take(limit)
    }

    /** Элементы, похожие на кнопки закладок (статический поиск для отчёта). */
    fun bookmarkControlsHtml(doc: Document): List<String> =
        doc.select("a, button, input, [onclick], img, span, i, div[title]").filter { el ->
            val hay = el.ownText() + " " + el.attr("title") + " " + el.attr("alt") + " " + el.attr("value") + " " +
                el.attr("href") + " " + el.attr("onclick") + " " + el.id() + " " + el.className()
            BOOKMARKS_RE.containsMatchIn(hay) && el.parents().none { it.hasClass("tag-links") }
        }.take(12).map { it.outerHtml().replace(Regex("\\s+"), " ").take(400) }

    fun isMangaPath(path: String): Boolean = MANGA_PATH.containsMatchIn(path)

    fun isAccountLink(l: Link): Boolean = ACCOUNT_RE.containsMatchIn(l.name) || ACCOUNT_RE.containsMatchIn(l.url)

    fun isBookmarksLink(l: Link): Boolean =
        (BOOKMARKS_RE.containsMatchIn(l.name) || BOOKMARKS_RE.containsMatchIn(l.url)) &&
            !l.name.contains("добав", true) && !l.url.contains("add", true)

    /** Убирает пагинацию из ссылки, чтобы /x?rowstart=30 и /x считались одним разделом. */
    private fun normalize(href: String): String? {
        val u = href.toHttpUrlOrNull() ?: return null
        val q = u.encodedQuery?.split('&')?.filterNot { it.startsWith("rowstart=") }?.joinToString("&")?.ifEmpty { null }
        return u.encodedPath + (q?.let { "?$it" } ?: "")
    }

    /**
     * Ссылки-кандидаты в разделы: внутренние ссылки страницы без карточек тайтлов и служебных страниц.
     * Ссылки из меню (nav, header, блоки с menu в классе) идут первыми. Какие из них списки манги — решает [app.moon.data.store.Categories].
     */
    fun parseMenuLinks(doc: Document): List<Link> {
        val host = doc.location().toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return emptyList()
        val menu = LinkedHashMap<String, Link>()
        val other = LinkedHashMap<String, Link>()
        for (a in doc.select("a[href]")) {
            val raw = a.attr("href").trim()
            if (raw.isEmpty() || raw.startsWith("#")) continue
            if (a.parents().any { it.hasClass("news_pic2") || it.tagName() == "footer" }) continue
            val href = a.absUrl("href")
            val u = href.toHttpUrlOrNull() ?: continue
            if (u.host.removePrefix("www.") != host) continue
            val p = u.encodedPath
            if (p == "/" || p.endsWith("/index.php") || (p.endsWith("/news.php") && u.encodedQuery == null)) continue
            if (MANGA_PATH.containsMatchIn(p) || IMG_EXT.containsMatchIn(p)) continue
            if (p.trimEnd('/') == "/tags") continue
            val low = href.lowercase()
            if (JUNK.any { low.contains(it) }) continue
            // каталог с одной лишь сортировкой — это вкладка «Все»
            if (p.startsWith("/all_manga")) {
                val keys = u.encodedQuery?.split('&')?.map { it.substringBefore('=') }?.filterNot { it == "rowstart" }.orEmpty()
                if (keys.all { it in SORT_KEYS }) continue
            }
            val text = a.text().trim().ifEmpty { a.attr("title").trim() }
            if (text.length !in 2..40 || text.none { it.isLetter() }) continue
            val inMenu = a.closest(MENU_CONTAINER) != null
            // отдельный тег/автор/переводчик — раздел, только если стоит в меню (не облако тегов в сайдбаре)
            if (SPECIFIC_PAGE.containsMatchIn(p) && !inMenu) continue
            val key = normalize(href) ?: continue
            if (key in menu || key in other) continue
            (if (inMenu) menu else other)[key] = Link(text, key)
        }
        return menu.values.toList() + other.values.toList()
    }

    /**
     * Универсальный разбор списка: все ссылки на тайтлы (…--….html) со своими обложками.
     * Нужен для разделов со своей вёрсткой (например, закладки), где нет table.news_pic2.
     */
    fun parseGenericList(doc: Document): List<MangaItem> {
        val host = doc.location().toHttpUrlOrNull()?.host?.removePrefix("www.")
        val out = LinkedHashMap<String, MangaItem>()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href")
            val u = href.toHttpUrlOrNull() ?: continue
            if (host != null && u.host.removePrefix("www.") != host) continue
            if (!MANGA_PATH.containsMatchIn(u.encodedPath)) continue
            val img = a.selectFirst("img")
            val title = cleanTitle(
                a.attr("title").ifBlank { a.text() }.ifBlank { img?.attr("alt").orEmpty() }.ifBlank { img?.attr("title").orEmpty() },
            )
            val key = path(href)
            val prev = out[key]
            val cover = prev?.cover ?: imageSrc(img)
            val name = prev?.title?.ifBlank { null } ?: title
            out[key] = MangaItem(url = key, title = name, cover = cover)
        }
        return out.values.filter { it.title.isNotBlank() }
    }

    /** Сначала обычная вёрстка списков, если её нет — универсальный разбор. */
    fun parseListAny(doc: Document): MangaPage {
        val page = parseList(doc)
        if (page.items.isNotEmpty()) return page
        return MangaPage(parseGenericList(doc), page.hasNext, page.nextUrl)
    }

    /** Имя пользователя из ссылки «Профиль» (как в парсере Kotatsu) или null. */
    fun username(doc: Document): String? =
        doc.getElementsContainingOwnText("Профиль").firstOrNull { it.hasAttr("href") }
            ?.attr("href")?.trimEnd('/')?.substringAfterLast('/')?.substringAfterLast('=')?.ifBlank { null }

    /** Абсолютная ссылка «Профиль» или null. */
    fun profileUrl(doc: Document): String? =
        doc.getElementsContainingOwnText("Профиль").firstOrNull { it.hasAttr("href") }?.absUrl("href")?.ifBlank { null }

    /** По странице: true — вошли, false — гость (есть форма входа), null — непонятно. */
    fun pageLoggedIn(doc: Document): Boolean? = when {
        doc.selectFirst("form[name=loginform], input[name=user_pass]") != null -> false
        doc.selectFirst("a[href*=logout]") != null || username(doc) != null -> true
        else -> null
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
