package app.moon.data.site

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface BookmarkResult {
    /** Нажали кнопку, сайт подтвердил. */
    data object Done : BookmarkResult

    /** Уже в нужном состоянии. */
    data object Already : BookmarkResult

    /** Нажали, но проверить результат по странице не удалось. */
    data object Unverified : BookmarkResult

    /** На странице тайтла нет кнопки закладок. */
    data object NoControl : BookmarkResult

    data class Failed(val message: String) : BookmarkResult
}

class BookmarkOp(val mangaUrl: String, val on: Boolean) {
    val result = CompletableDeferred<BookmarkResult>()
}

/**
 * Очередь действий на сайте, которые выполняет невидимый WebView (ui/web/SiteActionHost):
 * открыть страницу тайтла и нажать кнопку «в закладки / убрать». Так работает и обычная ссылка, и AJAX-кнопка.
 */
class SiteActions {
    val ops = Channel<BookmarkOp>(Channel.UNLIMITED)

    private val _active = MutableStateFlow(false)
    /** Пока true — WebView-исполнитель держится в разметке. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    suspend fun setBookmark(mangaUrl: String, on: Boolean): BookmarkResult {
        val op = BookmarkOp(mangaUrl, on)
        _active.value = true
        ops.send(op)
        return op.result.await()
    }

    fun idle() {
        _active.value = false
    }
}
