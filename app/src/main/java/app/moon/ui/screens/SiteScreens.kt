package app.moon.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.CookieManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moon.App
import app.moon.BuildConfig
import app.moon.data.Source
import app.moon.data.net.CookieMode
import app.moon.data.site.NudeMoonParser
import app.moon.data.store.Section
import app.moon.data.store.SectionKind
import app.moon.ui.common.LocalNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Все разделы, найденные на сайте: что во вкладках, что только после входа, что ещё есть. */
@Composable
fun SectionsScreen() {
    val nav = LocalNavigator.current
    val d by App.categories.state.collectAsState()
    val refreshing by App.categories.refreshing.collectAsState()
    val loggedIn by App.session.loggedIn.collectAsState()
    val tabUrls = d.tabs.map { it.url }.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Разделы сайта") },
                navigationIcon = { BackButton { nav.back() } },
                actions = {
                    IconButton(onClick = { nav.diag() }) { Icon(Icons.Outlined.BugReport, "Диагностика") }
                    IconButton(onClick = { App.categories.refresh() }, enabled = !refreshing) { Icon(Icons.Outlined.Refresh, "Обновить") }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            loggedIn -> "Вход выполнен" + (d.username?.let { " · $it" } ?: "")
                            else -> "Гость: разделы для вошедших появятся после входа"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Проверено: ${if (d.fetchedAt > 0) ago(d.fetchedAt) else "ещё нет"} · переключатель — показывать во вкладках каталога",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    d.error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            val groups = listOf(
                SectionKind.LOGIN_ONLY to "Только после входа",
                SectionKind.LISTING to "Списки манги",
                SectionKind.DIRECTORY to "Указатели",
                SectionKind.OTHER to "Другие ссылки сайта",
            )
            groups.forEach { (kind, title) ->
                val list = d.sections.filter { it.kind == kind }
                if (list.isNotEmpty()) {
                    item(key = "h-$kind") {
                        Text(
                            "$title · ${list.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { "s-" + it.url }) { s ->
                        SectionRow(s, s.url in tabUrls) { nav.listing(Source.Url(s.url, s.name)) }
                    }
                }
            }
            if (d.sections.isEmpty() && !refreshing) {
                item {
                    Text(
                        "Разделы ещё не загружены. Нажми ↻ или открой «Диагностика».",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionRow(s: Section, visible: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                s.url + if (s.items > 0) " · тайтлов на 1-й стр.: ${s.items}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = visible, onCheckedChange = { App.categories.setVisible(s, it) })
    }
}

class DiagViewModel : ViewModel() {
    private val _report = MutableStateFlow<String?>(null)
    val report = _report.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    init {
        run()
    }

    fun run() {
        if (_running.value) return
        _running.value = true
        viewModelScope.launch {
            _report.value = try {
                build()
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun StringBuilder.part(title: String, block: suspend StringBuilder.() -> Unit) {
        appendLine()
        appendLine("=== $title ===")
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appendLine("ОШИБКА: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun build(): String = buildString {
        val a = App.api
        val base = a.base
        appendLine("Moon ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine("Сайт: $base")
        val names = CookieManager.getInstance().getCookie(base)?.split(';')?.map { it.trim().substringBefore('=') }.orEmpty()
        appendLine("Cookies (только имена): ${names.joinToString()}")
        appendLine("Вход по cookie fusion_user: ${App.session.check()}")

        part("Главная, как вошедший") {
            val doc = a.document("$base/")
            appendLine("title: ${doc.title()}")
            appendLine("по странице вошёл: ${NudeMoonParser.pageLoggedIn(doc)} · ник: ${NudeMoonParser.username(doc)}")
            val links = NudeMoonParser.parseMenuLinks(doc)
            appendLine("ссылок-кандидатов: ${links.size}")
            links.forEach { appendLine("  ${it.name} -> ${it.url}") }
            appendLine("--- HTML меню ---")
            appendLine(NudeMoonParser.menuHtml(doc))
        }
        if (App.session.check()) {
            part("Главная, как гость") {
                val links = NudeMoonParser.parseMenuLinks(a.document("$base/", CookieMode.GUEST))
                appendLine("ссылок: ${links.size}")
                links.forEach { appendLine("  ${it.name} -> ${it.url}") }
            }
        }
        part("Разделы после проверки") {
            val d = App.categories.value
            appendLine("проверено: ${d.fetchedAt} · вошёл: ${d.loggedIn} · закладки: ${d.bookmarksUrl} · ошибка: ${d.error}")
            d.sections.forEach { appendLine("  [${it.kind}] ${it.name} -> ${it.url} (${it.items})") }
        }
        val bm = App.categories.value.bookmarksUrl
        if (bm != null) {
            part("Избранное сайта: $bm") {
                val doc = a.document(a.abs(bm))
                val page = NudeMoonParser.parseListAny(doc)
                appendLine("тайтлов на 1-й странице: ${page.items.size} · далее: ${page.nextUrl}")
                page.items.take(3).forEach { appendLine("  ${it.title} -> ${it.url}") }
                val all = runCatching { a.favorites(bm) }.getOrNull()
                appendLine("всего по всем страницам: ${all?.items?.size} · ссылок удаления: ${all?.removeLinks?.size}")
                all?.removeLinks?.entries?.take(2)?.forEach { appendLine("  удалить ${it.key}: ${it.value}") }
                appendLine("--- ссылки did= и формы ---")
                doc.select("a[href*=did=]").take(6).forEach { appendLine("  " + it.outerHtml().replace(Regex("\\s+"), " ").take(200)) }
                doc.select("form").take(3).forEach { f ->
                    appendLine("  form action=${f.attr("action")} method=${f.attr("method")} inputs=" +
                        f.select("input,button,select").joinToString { i -> i.attr("type") + ":" + i.attr("name") + "=" + i.attr("value").take(20) })
                }
                appendLine("--- первые блоки ---")
                appendLine(doc.select("a[href~=--.+\\.html]").firstOrNull()?.parents()?.getOrNull(2)?.outerHtml()
                    ?.replace(Regex("\\s+"), " ")?.take(1500).orEmpty())
                appendLine("пагинация: " + doc.select("a[href*=rowstart], a:matchesOwn(^\\s*(>|»|\\d+)\\s*$)").take(8).joinToString { it.text() + "→" + it.attr("href") })
            }
        }
        part("Последние ошибки") {
            val log = a.errorLog.toList()
            if (log.isEmpty()) appendLine("нет")
            log.forEach { appendLine("  $it") }
        }
        val sync = App.siteFavorites.state.value
        appendLine()
        appendLine("Синхронизация: вкл=${sync.enabled} на сайте=${sync.siteCount} ждут +${sync.pendingAdd.size}/-${sync.pendingRemove.size} удаление=${sync.removeHint} статус=${sync.status}")
        val sample = App.library.value.favorites.firstOrNull()?.manga?.url
            ?: runCatching { a.catalog(app.moon.data.Sort.DATE, 0).items.firstOrNull()?.url }.getOrNull()
        if (sample != null) {
            part("Кнопки закладок на странице тайтла $sample") {
                val doc = a.document(a.abs(sample))
                val controls = NudeMoonParser.bookmarkControlsHtml(doc)
                appendLine("найдено: ${controls.size}")
                controls.forEach { appendLine("  $it") }
            }
        }
    }
}

/** Отчёт о том, что приложение видит на сайте, — чтобы прислать разработчику. */
@Composable
fun DiagnosticsScreen() {
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val vm: DiagViewModel = viewModel { DiagViewModel() }
    val report by vm.report.collectAsState()
    val running by vm.running.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика сайта") },
                navigationIcon = { BackButton { nav.back() } },
                actions = { IconButton(onClick = vm::run, enabled = !running) { Icon(Icons.Outlined.Refresh, "Повторить") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "Отчёт о меню, разделах, закладках и кнопках сайта. Паролей и значений cookies в нём нет; есть ник и ссылки меню.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = report != null, onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                    ctx.startActivity(Intent.createChooser(i, "Отчёт Moon"))
                }) { Text("Поделиться") }
                OutlinedButton(enabled = report != null, onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Moon", report))
                    App.toast("Скопировано")
                }) { Text("Копировать") }
            }
            if (running) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("  Проверяю сайт…")
                }
            }
            HorizontalDivider()
            report?.let {
                SelectionContainer {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}
