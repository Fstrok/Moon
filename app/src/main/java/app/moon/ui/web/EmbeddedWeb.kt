package app.moon.ui.web

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.moon.data.site.NudeMoonParser
import app.moon.ui.common.LocalNavigator

/**
 * Страница сайта внутри вкладки — для разделов, которые приложение не умеет разобрать само.
 * Ссылки на тайтлы открываются в приложении (карточка, читалка), остальное — прямо здесь.
 */
@Composable
fun EmbeddedSitePage(url: String, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val openDetails by rememberUpdatedState<(String) -> Unit>({ nav.details(it) })
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    setupForSite()
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (u.scheme != "http" && u.scheme != "https") return true
                            val path = u.encodedPath.orEmpty()
                            if (NudeMoonParser.isMangaPath(path)) {
                                openDetails(path)
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView, u: String?) {
                            canBack = view.canGoBack()
                        }
                    }
                    loadUrl(url)
                    web = this
                }
            },
        )
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
    }
    BackHandler(enabled = canBack) { web?.goBack() }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
}
