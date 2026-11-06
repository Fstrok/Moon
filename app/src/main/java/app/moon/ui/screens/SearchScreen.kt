package app.moon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moon.App
import app.moon.data.MangaItem
import app.moon.data.Sort
import app.moon.data.Source
import app.moon.ui.common.Cover
import app.moon.ui.common.LocalNavigator
import app.moon.ui.common.MangaGrid

@Composable
fun SearchScreen() {
    val nav = LocalNavigator.current
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    val library by App.library.state.collectAsState()

    fun submit(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        query = t
        submitted = t
        App.library.addSearch(t)
        focus.clearFocus()
    }

    val local: List<MangaItem> = remember(query, library) {
        val q = query.trim()
        if (q.length < 2) emptyList() else
            (library.favorites.map { it.manga } + library.history.map { it.manga })
                .distinctBy { it.url }
                .filter { it.title.contains(q, true) || it.author?.contains(q, true) == true || it.tags.any { t -> t.equals(q, true) } }
                .take(20)
    }

    Scaffold(
        topBar = {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.isBlank()) submitted = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Название, автор…") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; submitted = null }) { Icon(Icons.Outlined.Close, "Очистить") }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(query) }),
            )
        },
    ) { pad ->
        val q = submitted
        if (q == null) {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (local.isNotEmpty()) LocalMatches(local) { nav.details(it) }
                if (library.searches.isNotEmpty()) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Недавние", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { App.library.clearSearches() }) { Text("Очистить") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        library.searches.forEach { s ->
                            InputChip(
                                selected = false,
                                onClick = { submit(s) },
                                label = { Text(s) },
                                leadingIcon = { Icon(Icons.Outlined.History, null, Modifier.size(16.dp)) },
                                trailingIcon = {
                                    Icon(Icons.Outlined.Close, "Удалить", Modifier.size(16.dp).clickable { App.library.removeSearch(s) })
                                },
                            )
                        }
                    }
                }
                if (library.pinnedTags.isNotEmpty()) {
                    Text("Закреплённые теги", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        library.pinnedTags.forEach { t ->
                            AssistChip(
                                onClick = { nav.listing(Source.Tags(listOf(t), App.settings.value.defaultSort)) },
                                label = { Text(t) },
                                leadingIcon = { Icon(Icons.Outlined.PushPin, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { nav.tags() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Sell, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Подбор по тегам")
                }
                Text(
                    "Поиск по сайту работает только после входа. Долгое нажатие на обложку — добавить в избранное.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val vm: ListingViewModel = viewModel(key = "search-$q") { ListingViewModel(Source.Search(q)) }
            val st by vm.state.collectAsState()
            MangaGrid(
                items = st.items,
                loading = st.loading,
                error = st.error,
                endReached = st.endReached,
                onLoadMore = vm::loadMore,
                onRetry = vm::retry,
                modifier = Modifier.padding(pad),
                header = if (local.isEmpty()) null else {
                    { item(span = { GridItemSpan(maxLineSpan) }, key = "local") { LocalMatches(local) { nav.details(it) } } }
                },
            )
        }
    }
}

@Composable
private fun LocalMatches(items: List<MangaItem>, onClick: (MangaItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("В избранном и истории", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 12.dp)) {
            items(items, key = { it.url }) { m ->
                Column(
                    Modifier
                        .width(96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onClick(m) },
                ) {
                    Cover(m.cover, Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(10.dp)))
                    Text(m.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
