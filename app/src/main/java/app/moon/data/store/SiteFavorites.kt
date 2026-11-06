package app.moon.data.store

import app.moon.data.MangaItem
import app.moon.data.site.BookmarkResult
import app.moon.data.site.NudeMoonApi
import app.moon.data.site.SiteActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class SyncData(
    /** Тайтлы, которые были в закладках сайта после прошлой синхронизации. */
    val synced: List<String> = emptyList(),
    val pendingAdd: List<String> = emptyList(),
    val pendingRemove: List<String> = emptyList(),
    val lastSync: Long = 0,
    val siteCount: Int = 0,
    val status: String? = null,
    val enabled: Boolean = true,
)

/**
 * Двусторонняя синхронизация избранного с закладками сайта.
 * Сайт → приложение: читаем страницу закладок. Приложение → сайт: нажимаем кнопку на странице тайтла.
 * Удаления переносятся только по разнице с прошлой синхронизацией, поэтому ничего не пропадёт из-за сбоя загрузки.
 */
class SiteFavorites(
    private val store: JsonStore<SyncData>,
    private val library: Library,
    private val api: () -> NudeMoonApi,
    private val bookmarksUrl: () -> String?,
    private val loggedIn: () -> Boolean,
    private val actions: SiteActions,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
) {
    val state: StateFlow<SyncData> get() = store.state

    private val _progress = MutableStateFlow<String?>(null)
    /** Текущая операция для экрана («Отправляю на сайт 3/10»), null — ничего не идёт. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val mutex = Mutex()
    private var job: Job? = null

    fun setEnabled(on: Boolean) {
        store.update { it.copy(enabled = on) }
        if (on) sync()
    }

    /** Пользователь нажал ♥ в приложении. */
    fun onLocalToggle(item: MangaItem, added: Boolean) {
        if (!store.value.enabled) return
        store.update { d ->
            if (added) d.copy(pendingAdd = (d.pendingAdd + item.url).distinct(), pendingRemove = d.pendingRemove - item.url)
            else d.copy(pendingRemove = (d.pendingRemove + item.url).distinct(), pendingAdd = d.pendingAdd - item.url)
        }
        if (loggedIn()) scope.launch { mutex.withLock { flush() } }
    }

    fun syncIfStale() {
        val d = store.value
        if (d.enabled && loggedIn() && System.currentTimeMillis() - d.lastSync > STALE_MS) sync()
    }

    fun sync(): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch { mutex.withLock { fullSync() } }.also { job = it }
    }

    private fun status(s: String?) = store.update { it.copy(status = s) }

    private suspend fun fullSync() {
        if (!store.value.enabled) return
        if (!loggedIn()) {
            status("Войди на сайт, чтобы синхронизировать избранное")
            return
        }
        val url = bookmarksUrl()
        if (url == null) {
            status("Не нашёл страницу закладок на сайте (см. «Ещё → Диагностика сайта»)")
            return
        }
        _progress.value = "Читаю закладки сайта…"
        status(null)
        try {
            val site = api().listingAll(url)
            val s = site.mapTo(LinkedHashSet()) { it.url }
            val d = store.value
            val prev = d.synced.toSet()
            val local = library.value.favorites.mapTo(HashSet()) { it.manga.url }

            // сайт → приложение: новые закладки сайта
            var imported = 0
            site.forEach { item ->
                if (item.url !in local && item.url !in d.pendingRemove && item.url !in prev) {
                    library.addFavoriteSilently(item)
                    imported++
                }
            }
            // удалили на сайте → убираем в приложении. Пустой список при непустом прошлом — скорее сбой,
            // чем «удалили всё», поэтому удаления не переносим.
            val trustSite = s.isNotEmpty() || prev.isEmpty()
            var removed = 0
            if (!trustSite) status("Закладки сайта пришли пустыми — удаления не переношу, избранное не трогаю")
            if (trustSite) {
                (prev - s).forEach { u ->
                    if (u in local && u !in d.pendingAdd) {
                        library.removeFavoriteSilently(u)
                        removed++
                    }
                }
            }
            // приложение → сайт: избранное, которого на сайте нет и не было
            val toPush = library.value.favorites.map { it.manga.url }.filter { it !in s && it !in prev }
            // убрали в приложении, пока не было сети/входа, — уберём и на сайте
            val toDrop = (prev intersect s).filter { it !in library.value.favorites.map { f -> f.manga.url }.toSet() }
            store.update { x ->
                x.copy(
                    synced = s.toList(),
                    siteCount = s.size,
                    pendingAdd = (x.pendingAdd + toPush).distinct().filter { it !in s },
                    pendingRemove = (x.pendingRemove + toDrop).distinct().filter { it in s },
                )
            }
            flush()
            val parts = listOfNotNull(
                imported.takeIf { it > 0 }?.let { "с сайта +$it" },
                removed.takeIf { it > 0 }?.let { "убрано $it" },
            )
            store.update { it.copy(lastSync = System.currentTimeMillis()) }
            if (store.value.status == null && parts.isNotEmpty()) toast("Избранное: " + parts.joinToString(", "))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            status("Не удалось прочитать закладки сайта: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            _progress.value = null
        }
    }

    /** Отправить накопленные изменения на сайт. */
    private suspend fun flush() {
        if (!loggedIn()) return
        var problem: String? = null
        try {
            val ops = store.value.pendingAdd.map { it to true } + store.value.pendingRemove.map { it to false }
            ops.forEachIndexed { i, (url, on) ->
                _progress.value = "Отправляю на сайт ${i + 1}/${ops.size}"
                when (val r = actions.setBookmark(url, on)) {
                    BookmarkResult.Done, BookmarkResult.Already, BookmarkResult.Unverified -> store.update { d ->
                        d.copy(
                            pendingAdd = d.pendingAdd - url,
                            pendingRemove = d.pendingRemove - url,
                            synced = if (on) (d.synced + url).distinct() else d.synced - url,
                        )
                    }
                    BookmarkResult.NoControl -> {
                        problem = "На странице тайтла не нашлась кнопка закладок — см. «Ещё → Диагностика сайта»"
                        store.update { d -> d.copy(pendingAdd = d.pendingAdd - url, pendingRemove = d.pendingRemove - url) }
                    }
                    is BookmarkResult.Failed -> {
                        problem = "Сайт: ${r.message}. Повторю при следующей синхронизации"
                        return
                    }
                }
            }
        } finally {
            _progress.value = null
            problem?.let {
                status(it)
                toast(it)
            }
        }
    }

    private companion object {
        const val STALE_MS = 15L * 60 * 1000
    }
}
