package app.moon.ui.screens

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moon.App
import app.moon.data.Chapter
import app.moon.data.MangaDetails
import app.moon.data.MangaItem
import app.moon.data.Source
import app.moon.data.download.DlState
import app.moon.ui.common.Cover
import app.moon.ui.common.ErrorBox
import app.moon.ui.common.LoadingBox
import app.moon.ui.common.LocalNavigator
import app.moon.ui.common.WebMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class DetailsViewModel(val url: String) : ViewModel() {
    data class State(
        val details: MangaDetails? = null,
        val loading: Boolean = details == null,
        val refreshing: Boolean = false,
        val error: Throwable? = null,
    )

    private val _state = MutableStateFlow(State(App.api.cachedDetails(url)))
    val state = _state.asStateFlow()

    init {
        if (_state.value.details == null) load(false)
        viewModelScope.launch { App.retrySignal.drop(1).collect { if (_state.value.error != null) load(true) } }
    }

    fun load(force: Boolean) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = _state.value.details == null, refreshing = _state.value.details != null, error = null)
        try {
            val d = App.api.details(url, force)
            App.library.refreshItem(d.toItem())
            _state.value = State(d, loading = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, refreshing = false, error = e)
        }
    }
}

@Composable
fun DetailsScreen(url: String, title: String, cover: String?) {
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val vm: DetailsViewModel = viewModel(key = "details-$url") { DetailsViewModel(url) }
    val st by vm.state.collectAsState()
    val library by App.library.state.collectAsState()
    val dl by App.downloads.states.collectAsState()
    val metas by App.downloads.metas.collectAsState()
    var descending by rememberSaveable { mutableStateOf(false) }

    val d = st.details
    val item = d?.toItem() ?: MangaItem(url, title, cover)
    val isFav = library.favorites.any { it.manga.url == url }
    val last = library.history.firstOrNull { it.manga.url == url }
    val siteUrl = App.api.abs(url)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton { nav.back() } },
                actions = {
                    IconButton(onClick = { nav.web(siteUrl, WebMode.BROWSE) }) { Icon(Icons.AutoMirrored.Outlined.Comment, "Комментарии на сайте") }
                    IconButton(onClick = {
                        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, siteUrl)
                        ctx.startActivity(Intent.createChooser(i, null))
                    }) { Icon(Icons.Outlined.Share, "Поделиться") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        PullToRefreshBox(isRefreshing = st.refreshing, onRefresh = { vm.load(true) }, modifier = Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item { Header(item, d) }
                item {
                    Actions(
                        d = d,
                        isFav = isFav,
                        lastLabel = last?.let { "Продолжить · стр. ${it.page + 1}" },
                        onRead = {
                            if (d == null) return@Actions
                            val target = last?.chapterUrl?.takeIf { u -> d.chapters.any { it.url == u } }
                                ?: d.readingOrder.firstOrNull()?.url
                            if (target != null) nav.reader(url, target)
                        },
                        onFav = {
                            val added = App.library.toggleFavorite(item)
                            App.toast(if (added) "В избранном" else "Убрано из избранного")
                        },
                        onDownload = {
                            if (d != null) {
                                App.downloads.enqueue(item, d.readingOrder)
                                App.toast("Загрузка: ${d.chapters.size} гл.")
                            }
                        },
                    )
                }
                if (d != null) {
                    item { Info(d) }
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 4.dp, top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Главы · ${d.chapters.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (d.chapters.size > 1) {
                                IconButton(onClick = { descending = !descending }) { Icon(Icons.Outlined.SwapVert, "Порядок") }
                            }
                        }
                    }
                    val chapters = d.readingOrder.let { if (descending) it.reversed() else it }
                    items(chapters, key = { it.url }) { ch ->
                        ChapterRow(
                            ch = ch,
                            progress = library.progress[ch.url],
                            dl = dl[ch.url] ?: if (metas.any { it.chapter.url == ch.url }) DlState.Done else null,
                            onClick = { nav.reader(url, ch.url) },
                            onDownload = {
                                when (dl[ch.url]) {
                                    is DlState.Running, DlState.Queued -> App.downloads.cancel(ch.url)
                                    else -> if (metas.none { it.chapter.url == ch.url }) App.downloads.enqueue(item, listOf(ch))
                                }
                            },
                        )
                    }
                } else if (st.loading) {
                    item { LoadingBox(Modifier.height(240.dp)) }
                }
                st.error?.let { e -> item { ErrorBox(e, { vm.load(true) }) } }
            }
        }
    }
}

@Composable
private fun Header(item: MangaItem, d: MangaDetails?) {
    val nav = LocalNavigator.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Cover(
            d?.cover ?: item.cover,
            Modifier
                .width(120.dp)
                .height(172.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d?.title ?: item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val alt = d?.fullTitle?.substringAfter(" / ", "")?.trim()
            if (!alt.isNullOrEmpty()) {
                Text(alt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            d?.author?.let { a ->
                MetaLine(Icons.Outlined.Person, a.name) { nav.listing(Source.Url(a.url, a.name)) }
            } ?: item.author?.let { MetaLine(Icons.Outlined.Person, it, null) }
            d?.translator?.let { t -> MetaLine(Icons.Outlined.Translate, t.name) { nav.listing(Source.Url(t.url, t.name)) } }
            val meta = listOfNotNull(formatDate(d?.date ?: 0), (d?.pages ?: item.pages)?.let { "$it стр." }).joinToString(" · ")
            if (meta.isNotEmpty()) MetaLine(Icons.Outlined.Schedule, meta, null)
        }
    }
}

@Composable
private fun MetaLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Actions(
    d: MangaDetails?,
    isFav: Boolean,
    lastLabel: String?,
    onRead: () -> Unit,
    onFav: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onRead, enabled = d != null, modifier = Modifier.weight(1f).height(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null)
            Spacer(Modifier.width(8.dp))
            Text(lastLabel ?: "Читать", maxLines = 1)
        }
        FilledTonalIconButton(onClick = onFav, modifier = Modifier.size(48.dp)) {
            Icon(if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Избранное",
                tint = if (isFav) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSecondaryContainer)
        }
        FilledTonalIconButton(onClick = onDownload, enabled = d != null, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Download, "Скачать всё")
        }
    }
}

@Composable
private fun Info(d: MangaDetails) {
    val nav = LocalNavigator.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (d.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.tags.forEach { t ->
                    AssistChip(onClick = { nav.listing(Source.Url(t.url, t.name)) }, label = { Text(t.name) })
                }
            }
        }
        d.description?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .animateContentSize()
                    .clickable { expanded = !expanded },
            )
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ChapterRow(
    ch: Chapter,
    progress: app.moon.data.store.Progress?,
    dl: DlState?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val read = progress?.finished == true
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ch.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                formatDate(ch.date),
                ch.translator,
                progress?.let { if (it.finished) "прочитано" else "стр. ${it.page + 1}/${it.total}" },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (read) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        IconButton(onClick = onDownload) {
            when (dl) {
                is DlState.Running -> Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { if (dl.total == 0) 0f else dl.done / dl.total.toFloat() }, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
                DlState.Queued -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                DlState.Done -> Icon(Icons.Outlined.DownloadDone, "Скачано", tint = MaterialTheme.colorScheme.primary)
                is DlState.Failed -> Icon(Icons.Outlined.ErrorOutline, "Ошибка", tint = MaterialTheme.colorScheme.error)
                null -> Icon(Icons.Outlined.Download, "Скачать", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
