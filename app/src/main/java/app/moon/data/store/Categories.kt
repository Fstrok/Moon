package app.moon.data.store

import app.moon.data.Link
import app.moon.data.net.CookieMode
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
enum class SectionKind {
    /** Обычный список манги сайта. */
    LISTING,

    /** Появляется в меню только после входа — показываем всегда, какой бы ни была вёрстка. */
    LOGIN_ONLY,

    /** Прочие ссылки меню: видны в «Разделы сайта», во вкладки — только если закрепить. */
    OTHER,
}

@Serializable
data class Section(val name: String, val url: String, val kind: SectionKind, val items: Int = 0)

@Serializable
data class CategoriesData(
    val sections: List<Section> = emptyList(),
    val fetchedAt: Long = 0,
    val loggedIn: Boolean = false,
    val base: String = "",
    /** Появились после прошлой проверки — в каталоге помечены точкой. */
    val fresh: List<String> = emptyList(),
    /** Закреплены пользователем как вкладки. */
    val pinned: List<String> = emptyList(),
    /** Скрыты пользователем из вкладок. */
    val hidden: List<String> = emptyList(),
    val username: String? = null,
    /** Страница закладок/избранного на сайте, если нашлась. */
    val bookmarksUrl: String? = null,
    val error: String? = null,
) {
    /** Вкладки каталога. */
    val tabs: List<Section>
        get() = sections.filter { (it.kind != SectionKind.OTHER || it.url in pinned) && it.url !in hidden }
}

/**
 * Разделы берутся с сайта, а не зашиты в приложение. Главная читается дважды — с cookies входа и «как гость»:
 * всё, что есть только у вошедшего, становится вкладкой автоматически. Остальные ссылки меню проверяются —
 * вкладкой становятся те, что открываются как список манги. Пересборка: вход/выход, смена адреса, раз в 12 ч.
 */
class Categories(
    private val store: JsonStore<CategoriesData>,
    private val api: () -> NudeMoonApi,
    private val loggedIn: () -> Boolean,
    private val scope: CoroutineScope,
    private val onNew: (List<Section>) -> Unit,
) {
    val state: StateFlow<CategoriesData> get() = store.state
    val value: CategoriesData get() = store.value

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var job: Job? = null
    private var jobKey: String? = null

    fun isStale(): Boolean {
        val d = store.value
        return d.sections.isEmpty() || d.base != api().base || d.loggedIn != loggedIn() ||
            System.currentTimeMillis() - d.fetchedAt > STALE_MS
    }

    fun refreshIfStale() {
        if (isStale()) refresh()
    }

    fun refresh(): Job {
        val key = api().base + "|" + loggedIn()
        job?.takeIf { it.isActive }?.let { running ->
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
            } catch (e: Exception) {
                store.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _refreshing.value = false
            }
        }.also { job = it }
    }

    private suspend fun menu(a: NudeMoonApi, mode: CookieMode): List<Link> =
        NudeMoonParser.parseMenuLinks(a.document(a.base + "/", mode))

    private suspend fun discover() {
        val a = api()
        val base = a.base
        val auth = loggedIn()
        val home = a.document("$base/")
        val desktop = runCatching { NudeMoonParser.parseMenuLinks(a.document("$base/", CookieMode.DESKTOP)) }.getOrDefault(emptyList())
        val candidates = (NudeMoonParser.parseMenuLinks(home) + desktop)
            .distinctBy { it.url }
            .filterNot { NudeMoonParser.isAccountLink(it) && !NudeMoonParser.isBookmarksLink(it) }

        // меню гостя: всё, чего в нём нет, — разделы «только после входа»
        val guestUrls: Set<String>? = if (!auth) null else runCatching {
            (menu(a, CookieMode.GUEST) + runCatching { menu(a, CookieMode.GUEST_DESKTOP) }.getOrDefault(emptyList()))
                .mapTo(HashSet()) { it.url }
        }.getOrNull()

        val old = store.value
        val oldByUrl = old.sections.associateBy { it.url }
        val toCheck = candidates.take(MAX_CANDIDATES)
        val sem = Semaphore(3)
        val sections = coroutineScope {
            toCheck.map { l ->
                async {
                    sem.withPermit {
                        val loginOnly = guestUrls != null && l.url !in guestUrls
                        val count = try {
                            if (loginOnly) a.listing(l.url).items.size else a.nativeCount(l.url)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        val kind = when {
                            loginOnly -> SectionKind.LOGIN_ONLY
                            count == null -> oldByUrl[l.url]?.kind?.takeIf { it != SectionKind.LOGIN_ONLY } ?: SectionKind.OTHER
                            count >= MIN_ITEMS -> SectionKind.LISTING
                            else -> SectionKind.OTHER
                        }
                        Section(l.name, l.url, kind, count ?: oldByUrl[l.url]?.items ?: 0)
                    }
                }
            }.awaitAll()
        }
        var bookmarks = (sections.filter { it.kind == SectionKind.LOGIN_ONLY } + sections)
            .firstOrNull { NudeMoonParser.isBookmarksLink(Link(it.name, it.url)) }?.url
            ?.takeIf { auth }
        // нет в меню — ищем в профиле
        if (bookmarks == null && auth) {
            bookmarks = runCatching {
                NudeMoonParser.profileUrl(home)?.let { p ->
                    NudeMoonParser.parseMenuLinks(a.document(p)).firstOrNull { NudeMoonParser.isBookmarksLink(it) }?.url
                }
            }.getOrNull()
        }

        val oldTabs = old.tabs.mapTo(HashSet()) { it.url }
        val next = old.copy(
            sections = sections,
            fetchedAt = System.currentTimeMillis(),
            loggedIn = auth,
            base = base,
            username = if (auth) NudeMoonParser.username(home) ?: old.username else null,
            bookmarksUrl = bookmarks,
            error = null,
        )
        val added = if (old.fetchedAt == 0L || old.base != base) emptyList() else next.tabs.filter { it.url !in oldTabs }
        val tabUrls = next.tabs.mapTo(HashSet()) { it.url }
        store.update {
            next.copy(fresh = (it.fresh + added.map { s -> s.url }).filter { u -> u in tabUrls }.distinct())
        }
        if (added.isNotEmpty()) onNew(added)
    }

    fun markSeen(url: String) {
        if (url in store.value.fresh) store.update { it.copy(fresh = it.fresh - url) }
    }

    /** Показать/скрыть раздел во вкладках каталога. */
    fun setVisible(s: Section, visible: Boolean) = store.update { d ->
        val auto = s.kind != SectionKind.OTHER
        d.copy(
            pinned = if (visible && !auto) (d.pinned + s.url).distinct() else d.pinned - s.url,
            hidden = if (!visible && auto) (d.hidden + s.url).distinct() else d.hidden - s.url,
        )
    }

    private companion object {
        const val STALE_MS = 12L * 60 * 60 * 1000
        const val MAX_CANDIDATES = 60
        const val MIN_ITEMS = 3
    }
}
