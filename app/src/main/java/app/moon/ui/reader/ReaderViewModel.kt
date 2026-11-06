package app.moon.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moon.App
import app.moon.data.Chapter
import app.moon.data.MangaDetails
import app.moon.data.MangaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class ReaderViewModel(private val mangaUrl: String, startChapter: String) : ViewModel() {
    data class State(
        val details: MangaDetails? = null,
        val manga: MangaItem? = null,
        val chapter: Chapter? = null,
        val pages: List<Any> = emptyList(),
        val startPage: Int = 0,
        val loading: Boolean = true,
        val error: Throwable? = null,
        val offline: Boolean = false,
        val loadId: Int = 0,
    ) {
        private val order get() = details?.readingOrder.orEmpty()
        private val idx get() = order.indexOfFirst { it.url == chapter?.url }
        val prev: Chapter? get() = idx.takeIf { it > 0 }?.let { order[it - 1] }
        val next: Chapter? get() = idx.takeIf { it >= 0 && it < order.size - 1 }?.let { order[it + 1] }
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var counter = 0
    private var currentUrl = startChapter

    init {
        open(startChapter, fromStart = false)
        viewModelScope.launch { App.retrySignal.drop(1).collect { if (_state.value.error != null) open(currentUrl, false) } }
    }

    fun open(chapterUrl: String, fromStart: Boolean) {
        currentUrl = chapterUrl
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val local = App.downloads.localPages(chapterUrl)
                val meta = App.downloads.metas.value.firstOrNull { it.chapter.url == chapterUrl }
                val details = App.api.cachedDetails(mangaUrl) ?: try {
                    App.api.details(mangaUrl)
                } catch (e: Exception) {
                    if (local == null) throw e else null
                }
                val chapter = details?.chapters?.firstOrNull { it.url == chapterUrl } ?: meta?.chapter ?: Chapter(chapterUrl, "Глава")
                val manga = details?.toItem() ?: meta?.manga ?: MangaItem(mangaUrl, chapter.name)
                val pages: List<Any> = local ?: App.api.pages(chapterUrl)
                val p = App.library.progressOf(chapterUrl)
                val start = if (fromStart || p == null || p.finished) 0 else p.page.coerceIn(0, pages.lastIndex)
                _state.value = State(details, manga, chapter, pages, start, loading = false, offline = local != null, loadId = ++counter)
                App.library.saveProgress(manga, chapter, start, pages.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e)
            }
        }
    }

    fun onPage(index: Int) {
        val s = _state.value
        val ch = s.chapter ?: return
        val m = s.manga ?: return
        if (s.pages.isEmpty()) return
        App.library.saveProgress(m, ch, index.coerceIn(0, s.pages.lastIndex), s.pages.size)
    }
}
