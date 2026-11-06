package app.moon.data.store

import app.moon.data.Chapter
import app.moon.data.MangaItem
import kotlinx.serialization.Serializable

@Serializable
data class Favorite(val manga: MangaItem, val added: Long)

@Serializable
data class HistoryEntry(
    val manga: MangaItem,
    val chapterUrl: String,
    val chapterName: String,
    val page: Int,
    val total: Int,
    val time: Long,
)

@Serializable
data class Progress(val page: Int, val total: Int, val time: Long) {
    val finished: Boolean get() = total > 0 && page >= total - 1
}

@Serializable
data class LibraryData(
    val favorites: List<Favorite> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val progress: Map<String, Progress> = emptyMap(),
    val searches: List<String> = emptyList(),
    val pinnedTags: List<String> = emptyList(),
)

class Library(private val store: JsonStore<LibraryData>) {
    val state get() = store.state
    val value get() = store.value

    /** Пользователь добавил/убрал избранное в приложении — синхронизация отправит это на сайт. */
    var onUserToggle: ((MangaItem, Boolean) -> Unit)? = null

    fun isFavorite(url: String) = store.value.favorites.any { it.manga.url == url }

    /** Добавить без отправки на сайт (используется синхронизацией). */
    fun addFavoriteSilently(item: MangaItem, added: Long = System.currentTimeMillis()) = store.update { d ->
        if (d.favorites.any { it.manga.url == item.url }) d else d.copy(favorites = d.favorites + Favorite(item, added))
    }

    /** Убрать без отправки на сайт (используется синхронизацией). */
    fun removeFavoriteSilently(url: String) = store.update { d -> d.copy(favorites = d.favorites.filterNot { it.manga.url == url }) }

    fun toggleFavorite(item: MangaItem): Boolean = toggleLocal(item).also { onUserToggle?.invoke(item, it) }

    private fun toggleLocal(item: MangaItem): Boolean {
        var added = false
        store.update { d ->
            if (d.favorites.any { it.manga.url == item.url }) {
                d.copy(favorites = d.favorites.filterNot { it.manga.url == item.url })
            } else {
                added = true
                d.copy(favorites = listOf(Favorite(item, System.currentTimeMillis())) + d.favorites)
            }
        }
        return added
    }

    /** Обновляет данные тайтла (обложка/теги) в избранном и истории, если они изменились. */
    fun refreshItem(item: MangaItem) {
        val d = store.value
        val fav = d.favorites.any { it.manga.url == item.url && it.manga != item }
        val hist = d.history.any { it.manga.url == item.url && it.manga != item }
        if (!fav && !hist) return
        store.update { s ->
            s.copy(
                favorites = s.favorites.map { if (it.manga.url == item.url) it.copy(manga = item) else it },
                history = s.history.map { if (it.manga.url == item.url) it.copy(manga = item) else it },
            )
        }
    }

    fun saveProgress(manga: MangaItem, chapter: Chapter, page: Int, total: Int) {
        val now = System.currentTimeMillis()
        store.update { d ->
            val entry = HistoryEntry(manga, chapter.url, chapter.name, page, total, now)
            val history = (listOf(entry) + d.history.filterNot { it.manga.url == manga.url }).take(MAX_HISTORY)
            var progress = d.progress + (chapter.url to Progress(page, total, now))
            if (progress.size > MAX_PROGRESS) {
                progress = progress.entries.sortedByDescending { it.value.time }.take(MAX_PROGRESS)
                    .associate { it.key to it.value }
            }
            d.copy(history = history, progress = progress)
        }
    }

    fun progressOf(chapterUrl: String): Progress? = store.value.progress[chapterUrl]

    fun lastRead(mangaUrl: String): HistoryEntry? = store.value.history.firstOrNull { it.manga.url == mangaUrl }

    fun removeHistory(mangaUrl: String) = store.update { d -> d.copy(history = d.history.filterNot { it.manga.url == mangaUrl }) }

    fun clearHistory() = store.update { it.copy(history = emptyList(), progress = emptyMap()) }

    fun addSearch(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        store.update { d -> d.copy(searches = (listOf(t) + d.searches.filterNot { it.equals(t, true) }).take(20)) }
    }

    fun removeSearch(q: String) = store.update { d -> d.copy(searches = d.searches - q) }

    fun clearSearches() = store.update { it.copy(searches = emptyList()) }

    fun togglePinnedTag(tag: String) = store.update { d ->
        d.copy(pinnedTags = if (tag in d.pinnedTags) d.pinnedTags - tag else d.pinnedTags + tag)
    }

    private companion object {
        const val MAX_HISTORY = 500
        const val MAX_PROGRESS = 5000
    }
}
