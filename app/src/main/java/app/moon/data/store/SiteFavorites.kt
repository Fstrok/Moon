package app.moon.data.store

import app.moon.data.MangaItem
import app.moon.data.site.NudeMoonApi
import app.moon.data.site.NudeMoonParser
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
    /** Тайтлы, которые были в избранном сайта после прошлой синхронизации. */
    val synced: List<String> = emptyList(),
    val pendingAdd: List<String> = emptyList(),
    val pendingRemove: List<String> = emptyList(),
    val lastSync: Long = 0,
    val siteCount: Int = 0,
    val status: String? = null,
    val enabled: Boolean = true,
    /** Каким способом сайт удаляет из избранного (запоминаем сработавший). */
    val removeHint: String? = null,
)

/**
 * Двусторонняя синхронизация избранного приложения с «Избранным» сайта (/favorites).
 * Сайт → приложение: читаем все страницы избранного. Приложение → сайт: ссылка сайта «В избранное!»
 * (favorites?did=ID&add) и ссылка удаления со страницы избранного; каждое действие проверяется по списку сайта.
 * Удаления переносятся только по разнице с прошлой синхронизацией, пустой ответ сайта избранное не стирает.
 */
class SiteFavorites(
    private val store: JsonStore<SyncData>,
    private val library: Library,
    private val api: () -> NudeMoonApi,
    private val favoritesUrl: () -> String?,
    private val loggedIn: () -> Boolean,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
) {
    val state: StateFlow<SyncData> get() = store.state

    private val _progress = MutableStateFlow<String?>(null)
    /** Текущая операция для экрана, null — ничего не идёт. */
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
        if (loggedIn()) scope.launch {
            mutex.withLock {
                try {
                    flush(null)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status("Не удалось отправить на сайт: ${e.message ?: e.javaClass.simpleName}. Повторю позже")
                } finally {
                    _progress.value = null
                }
            }
        }
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
        val url = favoritesUrl()
        if (url == null) {
            status("Не нашёл «Избранное» на сайте (см. «Ещё → Диагностика сайта»)")
            return
        }
        _progress.value = "Читаю избранное сайта…"
        status(null)
        try {
            val fav = api().favorites(url)
            val site = fav.items
            val s = site.mapTo(LinkedHashSet()) { it.url }
            val d = store.value
            val prev = d.synced.toSet()
            val local = library.value.favorites.mapTo(HashSet()) { it.manga.url }

            // сайт → приложение
            var imported = 0
            site.forEach { item ->
                if (item.url !in local && item.url !in d.pendingRemove && item.url !in prev) {
                    library.addFavoriteSilently(item)
                    imported++
                }
            }
            // удалили на сайте → убираем в приложении. Пустой список при непустом прошлом — скорее сбой.
            val trustSite = s.isNotEmpty() || prev.isEmpty()
            var removed = 0
            if (trustSite) {
                (prev - s).forEach { u ->
                    if (u in local && u !in d.pendingAdd) {
                        library.removeFavoriteSilently(u)
                        removed++
                    }
                }
            } else {
                status("Избранное сайта пришло пустым — удаления не переношу")
            }
            val nowLocal = library.value.favorites.map { it.manga.url }.toSet()
            // приложение → сайт: избранное, которого на сайте нет и не было
            val toPush = nowLocal.filter { it !in s && it !in prev }
            // убрали в приложении, пока не было сети/входа
            val toDrop = (prev intersect s).filter { it !in nowLocal }
            store.update { x ->
                x.copy(
                    synced = s.toList(),
                    siteCount = s.size,
                    pendingAdd = (x.pendingAdd + toPush).distinct().filter { it !in s },
                    pendingRemove = (x.pendingRemove + toDrop).distinct().filter { it in s },
                )
            }
            flush(fav)
            store.update { it.copy(lastSync = System.currentTimeMillis()) }
            val parts = listOfNotNull(
                imported.takeIf { it > 0 }?.let { "с сайта +$it" },
                removed.takeIf { it > 0 }?.let { "убрано $it" },
            )
            if (store.value.status == null && parts.isNotEmpty()) toast("Избранное: " + parts.joinToString(", "))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            status("Не удалось прочитать избранное сайта: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            _progress.value = null
        }
    }

    /** Отправить накопленные изменения на сайт и проверить их по списку избранного. */
    private suspend fun flush(snapshot: NudeMoonApi.Favorites?) {
        val d0 = store.value
        if (!loggedIn() || (d0.pendingAdd.isEmpty() && d0.pendingRemove.isEmpty())) return
        val url = favoritesUrl() ?: run {
            status("Не нашёл «Избранное» на сайте — изменения отправлю позже")
            return
        }
        val a = api()
        val favPage = a.abs(url)
        var snap = snapshot ?: a.favorites(url)
        fun urls() = snap.items.mapTo(HashSet()) { it.url }

        val adds = d0.pendingAdd.filter { it !in urls() }
        val removes = d0.pendingRemove.filter { it in urls() }

        adds.forEachIndexed { i, u ->
            _progress.value = "Добавляю на сайт ${i + 1}/${adds.size}"
            a.favoriteAddUrl(u)?.let { a.hit(it, referer = a.abs(u)) }
        }
        if (adds.isNotEmpty()) snap = a.favorites(url)

        var hint = d0.removeHint
        removes.forEachIndexed { i, u ->
            _progress.value = "Убираю с сайта ${i + 1}/${removes.size}"
            val id = NudeMoonParser.mangaId(u) ?: return@forEachIndexed
            val variants = linkedMapOf(
                "link" to snap.removeLinks[id],
                "del" to "${a.base}/favorites?did=$id&del",
                "delete" to "${a.base}/favorites?did=$id&delete",
                "remove" to "${a.base}/favorites?did=$id&remove",
                "toggle" to "${a.base}/favorites?did=$id&add",
            ).filterValues { it != null }
            val ordered = (listOfNotNull(hint?.takeIf { it in variants }) + variants.keys).distinct()
            for (k in ordered) {
                a.hit(variants.getValue(k)!!, referer = favPage)
                snap = a.favorites(url)
                if (u !in urls()) {
                    hint = k
                    break
                }
            }
        }

        val s2 = urls()
        val failedAdds = adds.count { it !in s2 }
        val failedRemoves = removes.count { it in s2 }
        store.update { d ->
            d.copy(
                synced = s2.toList(),
                siteCount = s2.size,
                pendingAdd = d.pendingAdd.filter { it !in s2 },
                pendingRemove = d.pendingRemove.filter { it in s2 },
                removeHint = hint,
            )
        }
        _progress.value = null
        val problem = when {
            failedRemoves > 0 -> "Сайт не убрал из избранного: $failedRemoves. Пришли «Диагностику сайта»"
            failedAdds > 0 -> "Сайт не добавил в избранное: $failedAdds. Повторю при следующей синхронизации"
            else -> null
        }
        if (problem != null) {
            status(problem)
            toast(problem)
        }
    }

    private companion object {
        const val STALE_MS = 15L * 60 * 1000
    }
}
