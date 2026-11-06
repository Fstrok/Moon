package app.moon.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Карточка тайтла. [url] — путь относительно сайта (/12345--name.html) или абсолютный URL. */
@Serializable
data class MangaItem(
    val url: String,
    val title: String,
    val cover: String? = null,
    val tags: List<String> = emptyList(),
    val author: String? = null,
    val pages: Int? = null,
)

data class MangaPage(
    val items: List<MangaItem>,
    val hasNext: Boolean,
    val nextUrl: String? = null,
)

@Serializable
data class Link(val name: String, val url: String)

@Serializable
data class Chapter(
    val url: String,
    val name: String,
    val number: Float = -1f,
    val date: Long = 0L,
    val translator: String? = null,
)

data class MangaDetails(
    val url: String,
    val title: String,
    val fullTitle: String,
    val cover: String?,
    val author: Link?,
    val translator: Link?,
    val tags: List<Link>,
    val description: String?,
    val date: Long,
    val pages: Int?,
    val chapters: List<Chapter>,
) {
    fun toItem() = MangaItem(url, title, cover, tags.map { it.name }, author?.name, pages)

    /** Главы в порядке чтения (от первой к последней). */
    val readingOrder: List<Chapter>
        get() {
            val nums = chapters.map { it.number }
            return if (chapters.size > 1 && nums.all { it >= 0 } && nums.toSet().size == nums.size) {
                chapters.sortedBy { it.number }
            } else {
                chapters
            }
        }
}

@Serializable
enum class Sort(val key: String, val label: String) {
    DATE("date", "Новое"),
    VIEWS("views", "Популярное"),
    LIKES("like", "Лайки"),
    COMMENTS("com", "Обсуждаемое"),
    PAGES("pages", "Объёмные"),
}

@Serializable
sealed interface Source {
    @Serializable
    @SerialName("catalog")
    data class Catalog(val sort: Sort) : Source

    @Serializable
    @SerialName("tags")
    data class Tags(val tags: List<String>, val sort: Sort) : Source

    @Serializable
    @SerialName("search")
    data class Search(val query: String) : Source

    @Serializable
    @SerialName("url")
    data class Url(val url: String, val title: String) : Source
}

@Serializable
enum class ReaderMode(val label: String) {
    WEBTOON("Лента"),
    PAGER_LTR("Страницы →"),
    PAGER_RTL("Страницы ←"),
}
