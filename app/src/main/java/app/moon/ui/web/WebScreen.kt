package app.moon.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import app.moon.App
import app.moon.data.site.NudeMoonParser
import app.moon.security.Auth
import app.moon.security.Biometric
import app.moon.security.Credentials
import app.moon.security.LoginScripts
import app.moon.ui.common.LocalNavigator
import app.moon.ui.common.WebMode
import app.moon.ui.common.findActivity
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val CHALLENGE_TITLES = listOf("just a moment", "один момент", "attention required", "проверка")

fun isChallengeTitle(t: String?): Boolean = t != null && CHALLENGE_TITLES.any { t.lowercase().contains(it) }

class CredentialBridge(private val onCaptured: (Credentials) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onCredentials(login: String?, password: String?) {
        if (login.isNullOrBlank() || password.isNullOrEmpty()) return
        main.post { onCaptured(Credentials(login.trim(), password)) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun WebView.setupForSite() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.mediaPlaybackRequiresUserGesture = true
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

@Composable
fun WebScreen(url: String, mode: WebMode) {
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(url) }
    var captured by remember { mutableStateOf<Credentials?>(null) }
    var askSave by remember { mutableStateOf<Credentials?>(null) }
    var finished by remember { mutableStateOf(false) }

    fun onLoggedIn() {
        if (finished) return
        finished = true
        App.session.refresh()
        App.signalRetry()
        val c = captured
        if (c != null) App.settings.update { it.copy(lastLogin = c.login) }
        if (c != null && Biometric.canStrong(ctx) && App.vault.savedLogin.value != c.login) {
            askSave = c
        } else {
            App.toast("Вход выполнен")
            nav.back()
        }
    }

    fun onPageDone(view: WebView, u: String?) {
        currentUrl = u ?: currentUrl
        canBack = view.canGoBack()
        CookieManager.getInstance().flush()
        when (mode) {
            WebMode.LOGIN -> {
                view.evaluateJavascript(LoginScripts.capture, null)
                if (App.session.check()) onLoggedIn()
            }
            WebMode.CLOUDFLARE -> if (!finished && !isChallengeTitle(view.title)) {
                finished = true
                App.signalRetry()
                App.toast("Проверка пройдена")
                nav.back()
            }
            WebMode.BROWSE -> App.session.refresh()
        }
    }

    val onDone by rememberUpdatedState(::onPageDone)

    if (mode == WebMode.LOGIN) {
        LaunchedEffect(Unit) {
            while (!finished) {
                delay(1500)
                if (App.session.check()) onLoggedIn()
            }
        }
    }

    val isMangaPage = mode == WebMode.BROWSE && currentUrl.toHttpUrlOrNull()?.encodedPath?.let { it.endsWith(".html") && it.contains("--") } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (mode) {
                                WebMode.LOGIN -> "Вход на сайт"
                                WebMode.CLOUDFLARE -> "Проверка Cloudflare"
                                WebMode.BROWSE -> title.ifBlank { "Сайт" }
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when (mode) {
                                WebMode.LOGIN -> "Войди как обычно — логин можно будет сохранить под отпечаток"
                                WebMode.CLOUDFLARE -> "Пройди проверку, окно закроется само"
                                WebMode.BROWSE -> currentUrl.toHttpUrlOrNull()?.encodedPath.orEmpty()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.Outlined.Close, "Закрыть") } },
                actions = {
                    if (isMangaPage) {
                        IconButton(onClick = { nav.details(NudeMoonParser.path(currentUrl)) }) {
                            Icon(Icons.AutoMirrored.Outlined.MenuBook, "Открыть в приложении")
                        }
                    }
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, "Обновить") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        setupForSite()
                        if (mode == WebMode.LOGIN) addJavascriptInterface(CredentialBridge { captured = it }, LoginScripts.BRIDGE)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView, t: String?) {
                                title = t.orEmpty()
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                                u?.let { currentUrl = it }
                            }

                            override fun onPageFinished(view: WebView, u: String?) = onDone(view, u)

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme.orEmpty()
                                return scheme != "http" && scheme != "https"
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
            )
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }

    BackHandler(enabled = canBack) { webView?.goBack() }
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    askSave?.let { c ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Входить по отпечатку?") },
            text = { Text("Сохраню логин «${c.login}» и пароль в зашифрованном виде. Когда сайт разлогинит, вход будет по пальцу.") },
            confirmButton = {
                TextButton(onClick = {
                    askSave = null
                    val a = ctx.findActivity()
                    if (a == null) nav.back() else Auth.saveCredentials(a, c) { nav.back() }
                }) { Text("Да") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askSave = null
                    App.toast("Вход выполнен")
                    nav.back()
                }) { Text("Нет") }
            },
        )
    }
}
