package app.moon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import app.moon.data.Link
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moon.App
import app.moon.data.Sort
import app.moon.data.Source
import app.moon.ui.common.LocalNavigator
import app.moon.ui.common.MangaGrid
import app.moon.ui.common.Routes
import app.moon.ui.common.SortChips

@Composable
fun CatalogScreen() {
    val nav = LocalNavigator.current
    var sort by rememberSaveable { mutableStateOf(App.settings.value.defaultSort) }
    val cats by App.categories.state.collectAsState()
    val catsRefreshing by App.categories.refreshing.collectAsState()
    var selectedUrl by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { App.categories.refreshIfStale() }

    val tabs = remember(cats.list) { listOf(Link("Все", "")) + cats.list }
    // категория пропала (например, после выхода) — возвращаемся во «Все»
    val selected = tabs.firstOrNull { it.url == selectedUrl } ?: tabs.first()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Каталог") },
                actions = {
                    IconButton(onClick = { nav.tags() }) { Icon(Icons.Outlined.Sell, "Теги") }
                    IconButton(onClick = { nav.top(Routes.SEARCH) }) { Icon(Icons.Outlined.Search, "Поиск") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (tabs.size > 1) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = tabs.indexOf(selected),
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    divider = {},
                ) {
                    tabs.forEach { t ->
                        val fresh = t.url in cats.fresh
                        Tab(
                            selected = t == selected,
                            onClick = {
                                selectedUrl = t.url
                                App.categories.markSeen(t.url)
                            },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(t.name, maxLines = 1)
                                    if (fresh) {
                                        Spacer(Modifier.width(4.dp))
                                        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (selected.url.isEmpty()) {
                val vm: ListingViewModel = viewModel(key = "catalog-${sort.name}") { ListingViewModel(Source.Catalog(sort)) }
                val st by vm.state.collectAsState()
                SortChips(sort, { sort = it }, Modifier.padding(vertical = 4.dp))
                PullToRefreshBox(
                    isRefreshing = st.refreshing,
                    onRefresh = {
                        vm.refresh()
                        App.categories.refresh()
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    key(sort) {
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
            } else {
                val vm: ListingViewModel = viewModel(key = "cat-${selected.url}") { ListingViewModel(Source.Url(selected.url, selected.name)) }
                val st by vm.state.collectAsState()
                PullToRefreshBox(
                    isRefreshing = st.refreshing || catsRefreshing && st.items.isEmpty(),
                    onRefresh = {
                        vm.refresh()
                        App.categories.refresh()
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    key(selected.url) {
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
        }
    }
}

/** Любой список: теги, автор, переводчик, результаты поиска. */
@Composable
fun ListingScreen(source: Source) {
    val nav = LocalNavigator.current
    var current by rememberSaveable(stateSaver = SourceSaver) { mutableStateOf(source) }
    val vm: ListingViewModel = viewModel(key = "listing-${current.hashCode()}") { ListingViewModel(current) }
    val st by vm.state.collectAsState()
    val title = when (val s = current) {
        is Source.Catalog -> "Каталог"
        is Source.Tags -> s.tags.joinToString(" + ")
        is Source.Search -> "«${s.query}»"
        is Source.Url -> s.title
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { BackButton { nav.back() } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            (current as? Source.Tags)?.let { t ->
                SortChips(t.sort, { current = t.copy(sort = it) }, Modifier.padding(bottom = 4.dp))
            }
            PullToRefreshBox(isRefreshing = st.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                key(current) {
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
    }
}

val SourceSaver = androidx.compose.runtime.saveable.Saver<Source, String>(
    save = { app.moon.data.store.AppJson.encodeToString(Source.serializer(), it) },
    restore = { app.moon.data.store.AppJson.decodeFromString(Source.serializer(), it) },
)
