package app.moon.data.store

import app.moon.data.Link
import app.moon.data.site.NudeMoonApi
import app.moon.data.site.NudeMoonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

@Serializable
data class CategoriesData(
    val list: List<Link> = emptyList(),
    val fetchedAt: Long = 0,
    val loggedIn: Boolean = false,
    val base: String = "",
    /** Появились после прошлой проверки — в каталоге помечены точкой. */
    val fresh: List<String> = emptyList(),
)

/**
 * Категории берутся с сайта, а не зашиты в приложение: читаем меню главной страницы и оставляем
 * только ссылки, которые открываются как список манги. Пересобирается при входе/выходе,
 * смене адреса и раз в 12 часов — новые разделы сайта появляются сами.
 */
class Categories(
    private val store: JsonStore<CategoriesData>,
    private val api: () -> NudeMoonApi,
    private val loggedIn: () -> Boolean,
    private val scope: CoroutineScope,
    private val onNew: (List<Link>) -> Unit,
) {
    val state: StateFlow<CategoriesData> get() = store.state

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var job: Job? = null
    private var jobKey: String? = null

    fun isStale(): Boolean {
        val d = store.value
        return d.base != api().base || d.loggedIn != loggedIn() || System.currentTimeMillis() - d.fetchedAt > STALE_MS
    }

    fun refreshIfStale() {
        if (isStale()) refresh()
    }

    fun refresh(): Job {
        val key = api().base + "|" + loggedIn()
        job?.takeIf { it.isActive }?.let { running ->
            // уже обновляем для того же адреса и того же статуса входа
            if (jobKey == key) return running
            running.cancel()
        }
        jobKey = key
        return scope.launch {
            _refreshing.value = true
            try {
                discover()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // сеть/Cloudflare: оставляем прошлый список, повторим позже
            } finally {
                _refreshing.value = false
            }
        }.also { job = it }
    }

    private suspend fun discover() {
        val a = api()
        val base = a.base
        val auth = loggedIn()
        val candidates = NudeMoonParser.parseMenuLinks(a.document("$base/")).take(MAX_CANDIDATES)
        val sem = Semaphore(3)
        val ok = coroutineScope {
            candidates.map { l ->
                async { sem.withPermit { runCatching { a.listing(l.url).items.size >= MIN_ITEMS }.getOrDefault(false) } }
            }.awaitAll()
        }
        val list = candidates.filterIndexed { i, _ -> ok[i] }
        val old = store.value
        val oldUrls = old.list.mapTo(HashSet()) { it.url }
        val added = if (old.fetchedAt == 0L || old.base != base) emptyList() else list.filter { it.url !in oldUrls }
        store.update {
            CategoriesData(
                list = list,
                fetchedAt = System.currentTimeMillis(),
                loggedIn = auth,
                base = base,
                fresh = (it.fresh + added.map { l -> l.url }).filter { u -> list.any { l -> l.url == u } }.distinct(),
            )
        }
        if (added.isNotEmpty()) onNew(added)
    }

    fun markSeen(url: String) {
        if (url in store.value.fresh) store.update { it.copy(fresh = it.fresh - url) }
    }

    private companion object {
        const val STALE_MS = 12L * 60 * 60 * 1000
        const val MAX_CANDIDATES = 40
        const val MIN_ITEMS = 3
    }
}
