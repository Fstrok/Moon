package app.moon.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moon.App
import app.moon.data.MangaItem
import app.moon.data.MangaPage
import app.moon.data.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ListingViewModel(val source: Source) : ViewModel() {
    data class State(
        val items: List<MangaItem> = emptyList(),
        val loading: Boolean = false,
        val refreshing: Boolean = false,
        val error: Throwable? = null,
        val endReached: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var page = 0
    private var nextUrl: String? = null
    private var job: Job? = null

    init {
        loadMore()
        viewModelScope.launch {
            App.retrySignal.drop(1).collect { if (_state.value.error != null) retry() }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (job?.isActive == true || s.endReached) return
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val res = fetch(page)
                page++
                nextUrl = res.nextUrl
                _state.update { st ->
                    val known = st.items.mapTo(HashSet()) { it.url }
                    val fresh = res.items.filter { it.url !in known }
                    st.copy(
                        items = st.items + fresh,
                        loading = false,
                        refreshing = false,
                        endReached = !res.hasNext || fresh.isEmpty(),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = e) }
            }
        }
    }

    fun refresh() {
        job?.cancel()
        page = 0
        nextUrl = null
        _state.value = State(refreshing = true)
        loadMore()
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }

    private suspend fun fetch(page: Int): MangaPage {
        return when (val s = source) {
            is Source.Catalog -> App.api.catalog(s.sort, page)
            is Source.Tags -> App.api.byTags(s.tags, s.sort, page)
            is Source.Search -> App.api.search(s.query, page)
            is Source.Url -> App.api.listing(if (page == 0) s.url else nextUrl ?: return MangaPage(emptyList(), false))
        }
    }
}
