package app.moon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement.spacedBy
import app.moon.App
import app.moon.data.download.DownloadMeta
import app.moon.ui.common.Cover
import app.moon.ui.common.EmptyBox
import app.moon.ui.common.LocalNavigator
import app.moon.ui.common.MangaCard
import app.moon.ui.common.gridCells
import androidx.compose.material.icons.outlined.DownloadForOffline

private enum class FavSort(val label: String) { ADDED("Добавлены"), READ("Недавно читал"), TITLE("По названию") }

@Composable
fun LibraryScreen() {
    val nav = LocalNavigator.current
    val library by App.library.state.collectAsState()
    val settings by App.settings.state.collectAsState()
    var sort by rememberSaveable { mutableStateOf(FavSort.ADDED) }
    var menu by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current

    val items = remember(library, sort, filter) {
        val lastRead = library.history.associate { it.manga.url to it.time }
        library.favorites
            .filter { filter.isBlank() || it.manga.title.contains(filter, true) || it.manga.tags.any { t -> t.contains(filter, true) } }
            .let { list ->
                when (sort) {
                    FavSort.ADDED -> list.sortedByDescending { it.added }
                    FavSort.READ -> list.sortedByDescending { lastRead[it.manga.url] ?: 0L }
                    FavSort.TITLE -> list.sortedBy { it.manga.title.lowercase() }
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = filter, onValueChange = { filter = it }, singleLine = true,
                            placeholder = { Text("Фильтр") }, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        )
                    } else {
                        Text("Избранное · ${library.favorites.size}")
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) filter = "" }) {
                        Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Фильтр")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Сортировка") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            FavSort.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; menu = false })
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
        SyncBar()
        if (items.isEmpty()) {
            EmptyBox(
                Icons.Outlined.FavoriteBorder,
                if (library.favorites.isEmpty()) "Здесь будет избранное.\nДолгое нажатие на обложку или ♥ в карточке тайтла." else "Ничего не найдено",
                Modifier.padding(top = 64.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = gridCells(settings.gridColumns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = spacedBy(10.dp),
                verticalArrangement = spacedBy(8.dp),
            ) {
                items(items, key = { it.manga.url }) { f ->
                    val last = library.history.firstOrNull { it.manga.url == f.manga.url }
                    MangaCard(
                        item = f.manga,
                        favorite = false,
                        badge = last?.let { "стр. ${it.page + 1}/${it.total}" },
                        onClick = { nav.details(f.manga) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            App.library.toggleFavorite(f.manga)
                            App.toast("Убрано из избранного")
                        },
                    )
                }
            }
        }
        }
    }
}

/** Состояние синхронизации избранного с закладками сайта. */
@Composable
private fun SyncBar() {
    val nav = LocalNavigator.current
    val sync by App.siteFavorites.state.collectAsState()
    val progress by App.siteFavorites.progress.collectAsState()
    val loggedIn by App.session.loggedIn.collectAsState()
    if (!sync.enabled) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (sync.status != null) Icons.Outlined.CloudOff else Icons.Outlined.CloudSync,
            null,
            tint = if (sync.status != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                !loggedIn -> "Войди — избранное синхронизируется с закладками сайта"
                progress != null -> progress!!
                sync.status != null -> sync.status!!
                sync.lastSync > 0 -> "Синхронизировано с сайтом · на сайте ${sync.siteCount} · ${ago(sync.lastSync)}"
                else -> "Избранное ещё не сверялось с сайтом"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            maxLines = 3,
        )
        if (!loggedIn) {
            TextButton(onClick = { nav.login() }) { Text("Войти") }
        } else if (progress != null) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = { App.siteFavorites.sync() }) { Icon(Icons.Outlined.Sync, "Синхронизировать") }
        }
    }
}

@Composable
fun HistoryScreen() {
    val nav = LocalNavigator.current
    val library by App.library.state.collectAsState()
    var confirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История") },
                actions = {
                    if (library.history.isNotEmpty()) IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.DeleteSweep, "Очистить") }
                },
            )
        },
    ) { pad ->
        if (library.history.isEmpty()) {
            EmptyBox(Icons.Outlined.History, "Пока ничего не прочитано", Modifier.padding(pad).padding(top = 64.dp))
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(library.history, key = { it.manga.url }) { h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav.reader(h.manga.url, h.chapterUrl) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(
                            h.manga.cover,
                            Modifier
                                .width(56.dp)
                                .height(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { nav.details(h.manga) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(h.manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${h.chapterName} · стр. ${h.page + 1}/${h.total}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            LinearProgressIndicator(
                                progress = { if (h.total == 0) 0f else (h.page + 1f) / h.total },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            Text(ago(h.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { App.library.removeHistory(h.manga.url) }) { Icon(Icons.Outlined.Close, "Удалить") }
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Очистить историю?") },
            text = { Text("Удалится история и прогресс чтения всех глав.") },
            confirmButton = { TextButton(onClick = { App.library.clearHistory(); confirm = false }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun DownloadsScreen() {
    val nav = LocalNavigator.current
    val metas by App.downloads.metas.collectAsState()
    val states by App.downloads.states.collectAsState()
    val groups = remember(metas) { metas.groupBy { it.manga.url }.values.toList() }
    val active = states.filterValues { it is app.moon.data.download.DlState.Running || it == app.moon.data.download.DlState.Queued }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Загрузки · ${formatBytes(metas.sumOf { it.sizeBytes })}") },
                navigationIcon = { BackButton { nav.back() } },
            )
        },
    ) { pad ->
        if (groups.isEmpty() && active.isEmpty()) {
            EmptyBox(Icons.Outlined.DownloadForOffline, "Скачанных глав нет.\nСкачивай в карточке тайтла — читается без интернета.", Modifier.padding(pad).padding(top = 64.dp))
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (active.isNotEmpty()) {
                    item {
                        Text(
                            "Качается: ${active.size}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(groups, key = { it.first().manga.url }) { g -> DownloadGroup(g) }
            }
        }
    }
}

@Composable
private fun DownloadGroup(list: List<DownloadMeta>) {
    val nav = LocalNavigator.current
    val m = list.first().manga
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(m.cover, Modifier.width(44.dp).height(62.dp).clip(RoundedCornerShape(6.dp)).clickable { nav.details(m) })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${list.size} гл. · ${formatBytes(list.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { App.downloads.deleteManga(m.url) }) { Icon(Icons.Outlined.Delete, "Удалить всё") }
        }
        list.sortedBy { it.chapter.number }.forEach { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { nav.reader(m.url, d.chapter.url) }
                    .padding(start = 56.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${d.chapter.name} · ${d.pages} стр.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { App.downloads.delete(d.chapter.url) }) { Icon(Icons.Outlined.Close, "Удалить", Modifier.height(18.dp)) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
