package app.moon.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moon.App
import app.moon.data.Chapter
import app.moon.data.ReaderMode
import app.moon.ui.common.ErrorBox
import app.moon.ui.common.LocalNavigator
import app.moon.ui.common.findActivity
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(mangaUrl: String, chapterUrl: String) {
    val nav = LocalNavigator.current
    val vm: ReaderViewModel = viewModel(key = "reader-$mangaUrl-$chapterUrl") { ReaderViewModel(mangaUrl, chapterUrl) }
    val st by vm.state.collectAsState()
    val settings by App.settings.state.collectAsState()
    var overlay by remember { mutableStateOf(false) }

    SystemBars(hidden = !overlay)
    KeepScreenOn(settings.keepScreenOn)
    DisposableEffect(Unit) {
        App.readerActive = true
        onDispose { App.readerActive = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            st.loading && st.pages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            st.error != null -> Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorBox(st.error!!, { vm.open(st.chapter?.url ?: chapterUrl, false) }, siteUrl = App.api.abs(st.chapter?.url ?: chapterUrl))
                    TextButton(onClick = { nav.back() }) { Text("Назад") }
                }
            }
            else -> key(st.loadId) {
                var current by remember { mutableIntStateOf(st.startPage) }
                LaunchedEffect(current) { vm.onPage(current) }
                Prefetch(st.pages, current)
                val mode = settings.readerMode
                key(mode) {
                    val goNext: (() -> Unit)? = st.next?.let { n -> { vm.open(n.url, true) } }
                    if (mode == ReaderMode.WEBTOON) {
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = current)
                        WebtoonReader(
                            pages = st.pages,
                            state = listState,
                            tapZones = settings.tapZones,
                            onPage = { current = it },
                            onToggleOverlay = { overlay = !overlay },
                            next = st.next,
                            onNext = goNext,
                        )
                        ReaderOverlay(
                            visible = overlay, st = st, current = current, mode = mode,
                            onJump = { p -> listState.scrollToItem(p) },
                            onOpen = { vm.open(it.url, true) },
                        )
                    } else {
                        val pagerState = rememberPagerState(initialPage = current) { st.pages.size + 1 }
                        PagerReader(
                            pages = st.pages,
                            state = pagerState,
                            rtl = mode == ReaderMode.PAGER_RTL,
                            tapZones = settings.tapZones,
                            onPage = { current = it },
                            onToggleOverlay = { overlay = !overlay },
                            next = st.next,
                            onNext = goNext,
                        )
                        ReaderOverlay(
                            visible = overlay, st = st, current = current, mode = mode,
                            onJump = { p -> pagerState.scrollToPage(p) },
                            onOpen = { vm.open(it.url, true) },
                        )
                    }
                }
            }
        }
        if (st.loading && st.pages.isNotEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun pageRequest(model: Any): ImageRequest {
    val ctx = LocalPlatformContext.current
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val target = (widthPx * 1.5f).roundToInt().coerceIn(720, 2160)
    return remember(model, target) {
        ImageRequest.Builder(ctx)
            .data(model)
            .size(Size(Dimension(target), Dimension.Undefined))
            .crossfade(false)
            .build()
    }
}

/** Заранее качает следующие страницы в дисковый кэш. */
@Composable
private fun Prefetch(pages: List<Any>, current: Int) {
    val ctx = LocalPlatformContext.current
    LaunchedEffect(current) {
        val loader = SingletonImageLoader.get(ctx)
        for (i in (current + 1)..minOf(current + 5, pages.lastIndex)) {
            val m = pages[i]
            if (m is String) {
                loader.enqueue(
                    ImageRequest.Builder(ctx).data(m).memoryCachePolicy(CachePolicy.DISABLED).size(Size(Dimension(64), Dimension(64))).build(),
                )
            }
        }
    }
}

@Composable
private fun WebtoonReader(
    pages: List<Any>,
    state: LazyListState,
    tapZones: Boolean,
    onPage: (Int) -> Unit,
    onToggleOverlay: () -> Unit,
    next: Chapter?,
    onNext: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state, pages.size) {
        snapshotFlow { if (!state.canScrollForward) pages.lastIndex else state.firstVisibleItemIndex.coerceAtMost(pages.lastIndex) }
            .collect(onPage)
    }
    LaunchedEffect(state) {
        App.volumeKeys.collect { dir ->
            val h = state.layoutInfo.viewportSize.height
            state.animateScrollBy(dir * h * 0.8f / scale)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            val maxX = size.width * (scale - 1f) / 2f
                            offsetX = if (scale == 1f) 0f else (offsetX + pan.x).coerceIn(-maxX, maxX)
                            if (pan.y != 0f) state.dispatchRawDelta(-pan.y / scale)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                }
                .pointerInput(tapZones, w, h) {
                    detectTapGestures(
                        onDoubleTap = { p ->
                            if (scale > 1.05f) {
                                scale = 1f
                                offsetX = 0f
                            } else {
                                scale = 2f
                                val maxX = w * (scale - 1f) / 2f
                                offsetX = ((p.x - w / 2f) * (1f - scale)).coerceIn(-maxX, maxX)
                            }
                        },
                        onTap = { p ->
                            when {
                                tapZones && p.y < h * 0.25f -> scope.launch { state.animateScrollBy(-h * 0.8f / scale) }
                                tapZones && p.y > h * 0.75f -> scope.launch { state.animateScrollBy(h * 0.8f / scale) }
                                else -> onToggleOverlay()
                            }
                        },
                    )
                },
        ) {
            itemsIndexed(pages, key = { i, _ -> i }) { i, model -> WebtoonPage(model, i) }
            item(key = "end") { ChapterEnd(next, onNext) }
        }
    }
}

@Composable
private fun WebtoonPage(model: Any, index: Int) {
    var retry by remember { mutableIntStateOf(0) }
    var painterState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val request = pageRequest(model)
    val ok = painterState is AsyncImagePainter.State.Success
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (ok) Modifier else Modifier.aspectRatio(0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        key(retry) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
                onState = { painterState = it },
            )
        }
        when (painterState) {
            is AsyncImagePainter.State.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Страница ${index + 1} не загрузилась", color = Color.White)
                OutlinedButton(onClick = { retry++ }) { Text("Повторить") }
            }
            is AsyncImagePainter.State.Success -> {}
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("${index + 1}", color = Color.Gray)
            }
        }
    }
}

@Composable
private fun PagerReader(
    pages: List<Any>,
    state: PagerState,
    rtl: Boolean,
    tapZones: Boolean,
    onPage: (Int) -> Unit,
    onToggleOverlay: () -> Unit,
    next: Chapter?,
    onNext: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state, pages.size) {
        snapshotFlow { state.currentPage.coerceAtMost(pages.lastIndex) }.collect(onPage)
    }
    LaunchedEffect(state) {
        App.volumeKeys.collect { dir ->
            state.animateScrollToPage((state.currentPage + dir).coerceIn(0, state.pageCount - 1))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        HorizontalPager(
            state = state,
            reverseLayout = rtl,
            beyondViewportPageCount = 2,
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            if (page < pages.size) {
                PagerPage(pages[page], page) { p: Offset ->
                    val forward = if (rtl) p.x < w * 0.33f else p.x > w * 0.67f
                    val backward = if (rtl) p.x > w * 0.67f else p.x < w * 0.33f
                    when {
                        tapZones && forward -> scope.launch { state.animateScrollToPage((state.currentPage + 1).coerceAtMost(state.pageCount - 1)) }
                        tapZones && backward -> scope.launch { state.animateScrollToPage((state.currentPage - 1).coerceAtLeast(0)) }
                        else -> onToggleOverlay()
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ChapterEnd(next, onNext) }
            }
        }
    }
}

/** Страница с зумом (щипок, двойной тап); пока не увеличена — свайп листает страницы. */
@Composable
private fun PagerPage(model: Any, index: Int, onTap: (Offset) -> Unit) {
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 5f))
    var retry by remember { mutableIntStateOf(0) }
    var painterState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val request = pageRequest(model)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        key(retry) {
            AsyncImage(
                model = request,
                contentDescription = null,
                // как в документации telephoto: Inside + scaledInsideAndCenterAligned, вписывание делает сам zoomable
                contentScale = ContentScale.Inside,
                onState = { st ->
                    painterState = st
                    if (st is AsyncImagePainter.State.Success) {
                        zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(st.painter.intrinsicSize))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(zoom, onClick = onTap),
            )
        }
        when (painterState) {
            is AsyncImagePainter.State.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Страница ${index + 1} не загрузилась", color = Color.White)
                OutlinedButton(onClick = { retry++ }) { Text("Повторить") }
            }
            is AsyncImagePainter.State.Success -> {}
            else -> CircularProgressIndicator(color = Color.Gray)
        }
    }
}

@Composable
private fun ChapterEnd(next: Chapter?, onNext: (() -> Unit)?) {
    val nav = LocalNavigator.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Конец главы", color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (next != null && onNext != null) {
            Button(onClick = onNext) { Text("Дальше: ${next.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        OutlinedButton(onClick = { nav.back() }) { Text("К тайтлу") }
    }
}

@Composable
private fun ReaderOverlay(
    visible: Boolean,
    st: ReaderViewModel.State,
    current: Int,
    mode: ReaderMode,
    onJump: suspend (Int) -> Unit,
    onOpen: (Chapter) -> Unit,
) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val bg = Color(0xE6101015)
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(st.manga?.title.orEmpty(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(st.chapter?.name.orEmpty(), color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                if (st.offline) Icon(Icons.Outlined.OfflinePin, "Офлайн", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = {
                    val nextMode = ReaderMode.entries[(mode.ordinal + 1) % ReaderMode.entries.size]
                    App.settings.update { it.copy(readerMode = nextMode) }
                }) {
                    Icon(if (mode == ReaderMode.WEBTOON) Icons.Outlined.ViewDay else Icons.Outlined.ViewCarousel, null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(mode.label, color = Color.White)
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(bg)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                val n = st.pages.size
                var slider by remember(current) { mutableFloatStateOf(current.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { st.prev?.let(onOpen) }, enabled = st.prev != null) {
                        Icon(Icons.Outlined.SkipPrevious, "Предыдущая глава", tint = if (st.prev != null) Color.White else Color.DarkGray)
                    }
                    if (n > 1) {
                        Slider(
                            value = slider,
                            onValueChange = { slider = it },
                            onValueChangeFinished = { scope.launch { onJump(slider.roundToInt()) } },
                            valueRange = 0f..(n - 1).toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    IconButton(onClick = { st.next?.let(onOpen) }, enabled = st.next != null) {
                        Icon(Icons.Outlined.SkipNext, "Следующая глава", tint = if (st.next != null) Color.White else Color.DarkGray)
                    }
                }
                Text(
                    "${slider.roundToInt() + 1} / $n",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SystemBars(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = view.context.findActivity()?.window
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) c?.hide(WindowInsetsCompat.Type.systemBars()) else c?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
