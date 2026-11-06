package app.moon.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moon.App
import app.moon.data.Source
import app.moon.ui.common.MangaGrid
import app.moon.ui.web.EmbeddedSitePage

/**
 * Содержимое раздела сайта: сетка тайтлов, если страницу удалось разобрать,
 * иначе — сама страница сайта (тайтлы с неё всё равно открываются в приложении).
 */
@Composable
fun SectionContent(url: String, title: String, modifier: Modifier = Modifier) {
    val vm: ListingViewModel = viewModel(key = "section-$url") { ListingViewModel(Source.Url(url, title)) }
    val st by vm.state.collectAsState()
    if (st.items.isEmpty() && st.endReached && st.error == null && !st.loading) {
        EmbeddedSitePage(App.api.abs(url), modifier)
        return
    }
    PullToRefreshBox(isRefreshing = st.refreshing, onRefresh = vm::refresh, modifier = modifier.fillMaxSize()) {
        key(url) {
            MangaGrid(
                items = st.items,
                loading = st.loading && !st.refreshing,
                error = st.error,
                endReached = st.endReached,
                onLoadMore = vm::loadMore,
                onRetry = vm::retry,
            )
        }
    }
}
