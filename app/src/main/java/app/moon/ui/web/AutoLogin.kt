package app.moon.ui.web

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import app.moon.App
import app.moon.security.LoginRequest
import app.moon.security.LoginResult
import app.moon.security.LoginScripts
import kotlinx.coroutines.delay

/**
 * Невидимый WebView: открывает сайт, заполняет форму входа расшифрованным паролем и отправляет её.
 * Так вход работает при любой разметке формы, а cookies сразу общие с приложением.
 */
@Composable
fun AutoLoginHost() {
    val req by App.autoLogin.collectAsState()
    val r = req ?: return
    key(r) { AutoLoginRun(r) }
}

private class RunState {
    var done = false
    var submitted = false
    var urlIndex = 0
    var view: WebView? = null
}

@Composable
private fun AutoLoginRun(req: LoginRequest) {
    val c = req.creds
    val st = remember { RunState() }
    val urls = remember { listOf(App.api.base + "/", App.api.base + "/login.php") }

    fun finish(ok: Boolean, msg: String) {
        if (st.done) return
        st.done = true
        CookieManager.getInstance().flush()
        App.session.refresh()
        if (ok) {
            App.signalRetry()
            App.settings.update { it.copy(lastLogin = c.login) }
        }
        if (!req.quiet) App.toast(msg)
        App.loginResults.tryEmit(LoginResult(ok, msg, c))
        App.autoLogin.value = null
    }

    LaunchedEffect(Unit) {
        repeat(40) {
            delay(750)
            if (st.done) return@LaunchedEffect
            if (App.session.check()) {
                finish(true, "Вход выполнен")
                return@LaunchedEffect
            }
        }
        finish(false, "Сайт не ответил на вход. Попробуй «Войти через сайт»")
    }

    AndroidView(
        // нормальный размер вьюпорта, но за пределами экрана и с нулевым размером в разметке — не перехватывает касания
        modifier = Modifier.layout { measurable, _ ->
            val p = measurable.measure(Constraints.fixed(1080, 1920))
            layout(0, 0) { p.place(-20000, 0) }
        },
        factory = { ctx ->
            WebView(ctx).apply {
                setupForSite()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (st.done) return
                        if (App.session.check()) {
                            finish(true, "Вход выполнен")
                            return
                        }
                        if (isChallengeTitle(view.title)) return // Cloudflare может пропустить сам; иначе — таймаут
                        if (st.submitted) {
                            finish(false, "Сайт не принял логин или пароль")
                            return
                        }
                        view.evaluateJavascript(LoginScripts.fill(c)) { r ->
                            if (r?.contains("ok") == true) {
                                st.submitted = true
                            } else {
                                st.urlIndex++
                                if (st.urlIndex < urls.size) view.loadUrl(urls[st.urlIndex])
                                else finish(false, "Не нашёл форму входа на сайте. Попробуй «Войти через сайт»")
                            }
                        }
                    }
                }
                loadUrl(urls[0])
                st.view = this
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            st.view?.apply {
                stopLoading()
                destroy()
            }
        }
    }
}
