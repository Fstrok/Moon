package app.moon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.moon.App
import app.moon.BuildConfig
import app.moon.data.ReaderMode
import app.moon.data.Sort
import app.moon.data.store.DEFAULT_BASE_URL
import app.moon.security.Auth
import app.moon.security.Biometric
import app.moon.security.Credentials
import app.moon.ui.common.LocalNavigator
import app.moon.ui.common.WebMode
import app.moon.ui.common.findActivity
import coil3.SingletonImageLoader

@Composable
fun MoreScreen() {
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val loggedIn by App.session.loggedIn.collectAsState()
    val savedLogin by App.vault.savedLogin.collectAsState()
    val autoLogin by App.autoLogin.collectAsState()
    val s by App.settings.state.collectAsState()
    val cats by App.categories.state.collectAsState()
    val catsRefreshing by App.categories.refreshing.collectAsState()
    var credDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { App.session.refresh() }

    Scaffold(topBar = { TopAppBar(title = { Text("Ещё") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AccountCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                when {
                                    autoLogin != null -> "Входим…"
                                    loggedIn -> s.lastLogin.ifEmpty { "Вход на сайт выполнен" }
                                    else -> "Вход не выполнен"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                savedLogin?.let { "Вход по отпечатку: $it" } ?: "Вход по отпечатку не настроен",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!loggedIn) {
                            if (savedLogin != null) {
                                Button(onClick = {
                                    ctx.findActivity()?.let { Auth.loginWithFingerprint(it) { nav.login() } }
                                }) {
                                    Icon(Icons.Outlined.Fingerprint, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Войти по отпечатку")
                                }
                            }
                            OutlinedButton(onClick = { nav.login() }) {
                                Icon(Icons.AutoMirrored.Outlined.Login, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Войти")
                            }
                        } else {
                            OutlinedButton(onClick = {
                                App.session.logout()
                                App.toast("Вы вышли")
                            }) {
                                Icon(Icons.AutoMirrored.Outlined.Logout, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Выйти")
                            }
                        }
                        if (savedLogin == null) {
                            TextButton(onClick = { credDialog = true }) { Text("Настроить отпечаток") }
                        } else {
                            TextButton(onClick = {
                                App.vault.clear()
                                App.toast("Сохранённый вход удалён")
                            }) { Text("Забыть пароль") }
                        }
                    }
                }
            }
            NavRow(
                Icons.Outlined.Category,
                if (catsRefreshing) "Категории сайта · обновляю…" else "Категории сайта · ${cats.list.size}",
                "Берутся из меню сайта, обновляются при входе. Нажми, чтобы обновить сейчас",
            ) { App.categories.refresh() }
            NavRow(Icons.Outlined.DownloadForOffline, "Загрузки", "Главы для чтения без интернета") { nav.downloads() }
            NavRow(Icons.Outlined.Sell, "Теги", "Подбор по нескольким тегам") { nav.tags() }
            NavRow(Icons.Outlined.Language, "Открыть сайт", "Комментарии, профиль и всё остальное") { nav.web(App.api.base + "/", WebMode.BROWSE) }
            NavRow(Icons.Outlined.Settings, "Настройки", "Защита, читалка, вид") { nav.settings() }
            Text(
                "Moon ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (credDialog) {
        CredentialsDialog(onDismiss = { credDialog = false }) { c ->
            credDialog = false
            ctx.findActivity()?.let { a -> Auth.saveCredentials(a, c) }
        }
    }
}

@Composable
fun CredentialsDialog(initialLogin: String = "", onDismiss: () -> Unit, onSave: (Credentials) -> Unit) {
    var login by rememberSaveable { mutableStateOf(initialLogin) }
    var pass by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вход по отпечатку") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Логин и пароль от сайта зашифруются ключом, который открывается только твоим отпечатком.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(login, { login = it }, label = { Text("Логин") }, singleLine = true)
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Пароль") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = login.isNotBlank() && pass.isNotEmpty(), onClick = { onSave(Credentials(login.trim(), pass)) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val s by App.settings.state.collectAsState()
    val canLock = remember { Biometric.canUnlock(ctx) }
    var baseUrl by rememberSaveable { mutableStateOf(s.baseUrl) }
    var confirmCookies by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }, navigationIcon = { BackButton { nav.back() } }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Header("Защита")
            SwitchRow(
                "Блокировка отпечатком",
                if (canLock) "При запуске и после выхода из приложения" else "На телефоне не настроена блокировка экрана",
                s.lockEnabled && canLock,
                enabled = canLock,
            ) { v -> App.settings.update { it.copy(lockEnabled = v) } }
            if (s.lockEnabled && canLock) {
                ChipsRow("Блокировать через", listOf(0 to "сразу", 30 to "30 с", 60 to "1 мин", 300 to "5 мин", 900 to "15 мин"), s.lockDelaySec) { v ->
                    App.settings.update { it.copy(lockDelaySec = v) }
                }
            }
            SwitchRow("Скрывать в недавних", "Чёрный экран в списке приложений, запрет скриншотов", s.secureScreen) { v ->
                App.settings.update { it.copy(secureScreen = v) }
            }

            Header("Читалка")
            ChipsRow("Режим по умолчанию", ReaderMode.entries.map { it to it.label }, s.readerMode) { v ->
                App.settings.update { it.copy(readerMode = v) }
            }
            SwitchRow("Зоны нажатия", "Тап по краю — листать, по центру — меню", s.tapZones) { v -> App.settings.update { it.copy(tapZones = v) } }
            SwitchRow("Листать кнопками громкости", null, s.volumeKeys) { v -> App.settings.update { it.copy(volumeKeys = v) } }
            SwitchRow("Не гасить экран", null, s.keepScreenOn) { v -> App.settings.update { it.copy(keepScreenOn = v) } }

            Header("Вид")
            SwitchRow("Чёрная тема (AMOLED)", null, s.amoled) { v -> App.settings.update { it.copy(amoled = v) } }
            ChipsRow("Колонок в сетке", listOf(0 to "авто", 2 to "2", 3 to "3", 4 to "4"), s.gridColumns) { v ->
                App.settings.update { it.copy(gridColumns = v) }
            }
            ChipsRow("Сортировка каталога", Sort.entries.map { it to it.label }, s.defaultSort) { v ->
                App.settings.update { it.copy(defaultSort = v) }
            }

            Header("Сайт")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    baseUrl, { baseUrl = it }, label = { Text("Адрес (зеркало)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = baseUrl.trim().trimEnd('/') != s.baseUrl && baseUrl.trim().startsWith("https://"),
                        onClick = {
                            val v = baseUrl.trim().trimEnd('/')
                            App.settings.update { it.copy(baseUrl = v) }
                            App.session.ensureDefaultCookies()
                            App.session.refresh()
                            App.toast("Адрес сохранён")
                        },
                    ) { Text("Сохранить") }
                    OutlinedButton(onClick = {
                        baseUrl = DEFAULT_BASE_URL
                        App.settings.update { it.copy(baseUrl = DEFAULT_BASE_URL) }
                        App.session.ensureDefaultCookies()
                        App.session.refresh()
                    }) { Text("По умолчанию") }
                }
            }

            Header("Данные")
            ActionRow("Очистить кэш картинок", "Скачанные главы не затрагиваются") {
                val loader = SingletonImageLoader.get(ctx)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
                App.toast("Кэш очищен")
            }
            ActionRow("Очистить историю поиска", null) {
                App.library.clearSearches()
                App.toast("Готово")
            }
            ActionRow("Сбросить cookies сайта", "Выход из аккаунта и сброс проверки Cloudflare") { confirmCookies = true }
        }
    }
    if (confirmCookies) {
        AlertDialog(
            onDismissRequest = { confirmCookies = false },
            title = { Text("Сбросить cookies?") },
            text = { Text("Придётся снова войти на сайт.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCookies = false
                    App.session.clearAllCookies { App.toast("Cookies сброшены") }
                }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmCookies = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChipsRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) ->
                FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
