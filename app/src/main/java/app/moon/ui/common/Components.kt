package app.moon.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moon.App
import app.moon.data.MangaItem
import app.moon.data.Sort
import app.moon.data.net.AuthRequiredException
import app.moon.data.net.CloudflareException
import app.moon.data.net.HttpError
import app.moon.data.net.NoPagesException
import app.moon.security.Auth
import coil3.compose.AsyncImage
import java.io.IOException
import java.net.UnknownHostException

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MangaCard(item: MangaItem, favorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, badge: String? = null) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 6.dp),
    ) {
        Box {
            Cover(
                item.cover,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(12.dp)),
            )
            if (favorite) {
                Icon(
                    Icons.Filled.Favorite, null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xAA000000), CircleShape)
                        .padding(4.dp)
                        .size(14.dp),
                )
            }
            val tag = badge ?: item.pages?.let { "$it стр." }
            if (tag != null) {
                Text(
                    tag,
                    fontSize = 10.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

fun gridCells(columns: Int): GridCells = if (columns <= 0) GridCells.Adaptive(112.dp) else GridCells.Fixed(columns)

/** Сетка тайтлов с бесконечной прокруткой. */
@Composable
fun MangaGrid(
    items: List<MangaItem>,
    loading: Boolean,
    error: Throwable?,
    endReached: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    emptyText: String = "Ничего не найдено",
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val nav = LocalNavigator.current
    val library by App.library.state.collectAsState()
    val settings by App.settings.state.collectAsState()
    val favs = remember(library.favorites) { library.favorites.map { it.manga.url }.toHashSet() }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(state, items.size, endReached, loading, error) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (!loading && !endReached && error == null && items.isNotEmpty() && last >= state.layoutInfo.totalItemsCount - 10) onLoadMore()
            }
    }

    LazyVerticalGrid(
        columns = gridCells(settings.gridColumns),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        header?.invoke(this)
        items(items, key = { it.url }) { item ->
            MangaCard(
                item = item,
                favorite = item.url in favs,
                onClick = { nav.details(item) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val added = App.library.toggleFavorite(item)
                    App.toast(if (added) "Добавлено в избранное" else "Убрано из избранного")
                },
            )
        }
        // без ключа: иначе при первой загрузке сетка «прилипает» к футеру и уезжает вниз
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                error != null && items.isEmpty() -> ErrorBox(error, onRetry, Modifier.padding(top = 48.dp))
                error != null -> ErrorBox(error, onRetry, compact = true)
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                items.isEmpty() && endReached -> EmptyBox(Icons.Outlined.SearchOff, emptyText, Modifier.padding(top = 48.dp))
                else -> Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun SortChips(selected: Sort, onSelect: (Sort) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Sort.entries) { s ->
            FilterChip(selected = s == selected, onClick = { onSelect(s) }, label = { Text(s.label) })
        }
    }
}

@Composable
fun EmptyBox(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

fun errorText(e: Throwable): String = when (e) {
    is CloudflareException -> "Сайт просит пройти проверку Cloudflare"
    is AuthRequiredException -> e.message ?: "Нужен вход на сайт"
    is NoPagesException -> "Страница открылась, но картинок на ней не нашлось. Открой на сайте; если там читается — пришли «Диагностику»"
    is HttpError -> when (e.code) {
        404 -> "Сайт ответил 404: тайтл удалён или скрыт"
        in 500..599 -> "Сайт не отвечает (${e.code})"
        else -> "Ошибка сайта: ${e.code}"
    }
    is UnknownHostException -> "Нет интернета или сайт недоступен"
    is IOException -> "Сетевая ошибка: ${e.message ?: e.javaClass.simpleName}"
    else -> e.message ?: e.javaClass.simpleName
}

/** Ошибка с подходящим действием: повтор, проверка Cloudflare, вход. */
@Composable
fun ErrorBox(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, siteUrl: String? = null) {
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val savedLogin by App.vault.savedLogin.collectAsState()
    val icon = when (error) {
        is CloudflareException -> Icons.Outlined.Security
        is AuthRequiredException -> Icons.Outlined.Lock
        is IOException -> Icons.Outlined.CloudOff
        else -> Icons.Outlined.ErrorOutline
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(if (compact) 12.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!compact) Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(errorText(error), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (error) {
                is CloudflareException -> Button(onClick = { nav.web(error.url, WebMode.CLOUDFLARE) }) { Text("Пройти проверку") }
                is AuthRequiredException -> {
                    if (savedLogin != null) {
                        Button(onClick = {
                            ctx.findActivity()?.let { a -> Auth.loginWithFingerprint(a) { nav.login() } }
                        }) {
                            Icon(Icons.Outlined.Fingerprint, null)
                            Spacer(Modifier.size(6.dp))
                            Text("Войти")
                        }
                    } else {
                        Button(onClick = { nav.login() }) { Text("Войти") }
                    }
                }
                else -> {}
            }
            OutlinedButton(onClick = onRetry) { Text("Повторить") }
        }
        if (siteUrl != null && error !is CloudflareException) {
            androidx.compose.material3.TextButton(onClick = { nav.web(siteUrl, WebMode.BROWSE) }) { Text("Открыть на сайте") }
        }
    }
}
