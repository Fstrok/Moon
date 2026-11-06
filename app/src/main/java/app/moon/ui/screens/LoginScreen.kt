package app.moon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.moon.App
import app.moon.security.Auth
import app.moon.security.Biometric
import app.moon.security.Credentials
import app.moon.security.LoginRequest
import app.moon.ui.common.LocalNavigator
import app.moon.ui.common.WebMode
import app.moon.ui.common.findActivity

/** Вход в аккаунт сайта нативной формой; сама форма сайта заполняется в фоне. */
@Composable
fun LoginScreen() {
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val canFinger = remember { Biometric.canStrong(ctx) }
    val savedLogin by App.vault.savedLogin.collectAsState()
    val loggedIn by App.session.loggedIn.collectAsState()
    val pending by App.autoLogin.collectAsState()
    val settings by App.settings.state.collectAsState()

    var login by rememberSaveable { mutableStateOf(savedLogin ?: settings.lastLogin) }
    var pass by rememberSaveable { mutableStateOf("") }
    var showPass by rememberSaveable { mutableStateOf(false) }
    var useFinger by rememberSaveable { mutableStateOf(canFinger) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        App.session.refresh()
        App.loginResults.collect { r ->
            if (!waiting) return@collect
            waiting = false
            if (r.ok) {
                val a = ctx.findActivity()
                val needSave = useFinger && canFinger && a != null && pass.isNotEmpty() && savedLogin != r.creds.login
                if (needSave) {
                    Auth.saveCredentials(a!!, r.creds) { nav.back() }
                } else {
                    App.toast("Вход выполнен")
                    nav.back()
                }
            } else {
                error = r.message
            }
        }
    }

    fun submit() {
        if (login.isBlank() || pass.isEmpty() || pending != null) return
        focus.clearFocus()
        error = null
        waiting = true
        App.autoLogin.value = LoginRequest(Credentials(login.trim(), pass), quiet = true)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Аккаунт") }, navigationIcon = { BackButton { nav.back() } }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.NightsStay, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            if (loggedIn) {
                Text("Вход выполнен", style = MaterialTheme.typography.headlineSmall)
                if (settings.lastLogin.isNotEmpty()) Text(settings.lastLogin, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Категории и поиск для авторизованных уже доступны в каталоге.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = {
                    App.session.logout()
                    App.toast("Вы вышли")
                }) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Выйти")
                }
                return@Column
            }

            Text("Вход на сайт", style = MaterialTheme.typography.headlineSmall)
            if (savedLogin != null) {
                Button(
                    onClick = { ctx.findActivity()?.let { Auth.loginWithFingerprint(it) {} } },
                    enabled = pending == null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Outlined.Fingerprint, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Войти по отпечатку · $savedLogin")
                }
                Text("или введи заново", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text("Логин") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Пароль") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Показать пароль")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (canFinger) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { useFinger = !useFinger },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Дальше входить по отпечатку")
                        Text(
                            "Пароль шифруется ключом телефона, открывается только пальцем",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useFinger, onCheckedChange = { useFinger = it })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            Button(
                onClick = ::submit,
                enabled = login.isNotBlank() && pass.isNotEmpty() && pending == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (pending != null) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Входим…")
                } else {
                    Text("Войти")
                }
            }
            TextButton(onClick = { nav.web(App.api.base + "/", WebMode.LOGIN) }) {
                Icon(Icons.Outlined.Language, null)
                Spacer(Modifier.width(6.dp))
                Text("Войти через сайт (капча, регистрация)")
            }
        }
    }
}
