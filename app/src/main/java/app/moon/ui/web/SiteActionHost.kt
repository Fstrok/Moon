package app.moon.ui.web

import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import app.moon.App
import app.moon.data.site.BookmarkOp
import app.moon.data.site.BookmarkResult
import app.moon.data.store.AppJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.resume

/** Кнопка закладок, найденная на странице. state: add | remove | ? */
@Serializable
data class BookmarkControl(val i: Int, val tag: String, val text: String, val meta: String, val state: String)

object BookmarkScripts {
    /** Находит на странице кнопки «в закладки/избранное» и их состояние; кладёт элементы в window.__moonCtl. */
    const val DETECT = """
        (function(){
          var re=/закладк|избранн|bookmark|favou?rit/i;
          var rm=/удал|убра|убер|из закладок|из избранного|в закладках|в избранном|remove|delete|unbookmark|unfav/i;
          var add=/добав|в закладки|в избранное|add|bookmark|favou?rit|закладк|избранн/i;
          var on=/active|added|checked|selected|marked|\bon\b|in-?fav|is-?fav/i;
          var els=document.querySelectorAll('a,button,input[type=button],input[type=submit],input[type=image],[onclick],[role=button],img[title],img[alt],span[title],i[title]');
          var out=[]; window.__moonCtl=[];
          for(var k=0;k<els.length;k++){
            var el=els[k];
            if(el.closest&&el.closest('.tag-links'))continue;
            var t=((el.innerText||'')+' '+(el.value||'')+' '+(el.getAttribute('title')||'')+' '+(el.getAttribute('alt')||'')).replace(/\s+/g,' ').trim();
            var href=el.getAttribute('href')||'';
            var meta=(href+' '+(el.getAttribute('onclick')||'')+' '+(el.id||'')+' '+(typeof el.className==='string'?el.className:'')).trim();
            if(!re.test(t+' '+meta))continue;
            // обычная ссылка на страницу «Мои закладки» — это не кнопка
            if(el.tagName==='A'&&href&&!el.getAttribute('onclick')&&!/add|del|rem|toggle|act|id=|\d{2,}|javascript/i.test(href))continue;
            if(t.length>80)continue;
            var st=rm.test(t)?'remove':(/добав|^add/i.test(t)?'add':(on.test(meta)?'remove':(add.test(t)?'add':'?')));
            window.__moonCtl.push(el);
            out.push({i:out.length,tag:el.tagName,text:t.slice(0,60),meta:meta.slice(0,120),state:st});
          }
          return JSON.stringify(out);
        })();
    """

    fun click(i: Int) = "(function(){var e=window.__moonCtl&&window.__moonCtl[$i];if(!e)return 'none';e.click();return 'ok';})();"

    fun parse(raw: String?): List<BookmarkControl> = runCatching {
        val inner = AppJson.decodeFromString(String.serializer(), raw ?: return emptyList())
        AppJson.decodeFromString(ListSerializer(BookmarkControl.serializer()), inner)
    }.getOrDefault(emptyList())
}

/** Невидимый WebView, выполняющий [app.moon.data.site.SiteActions] по очереди. Живёт, пока есть работа. */
@Composable
fun SiteActionHostIfNeeded() {
    val active by App.siteActions.active.collectAsState()
    if (active) SiteActionHost()
}

@Composable
private fun SiteActionHost() {
    var web by remember { mutableStateOf<WebView?>(null) }
    val pages = remember { MutableSharedFlow<String>(extraBufferCapacity = 8) }

    AndroidView(
        modifier = Modifier.layout { m, _ ->
            val p = m.measure(Constraints.fixed(1080, 1920))
            layout(0, 0) { p.place(-20000, 0) }
        },
        factory = { ctx ->
            WebView(ctx).apply {
                setupForSite()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        pages.tryEmit(url.orEmpty())
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    // «Добавить в закладки?» — соглашаемся
                    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                        result.confirm()
                        return true
                    }

                    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                        result.confirm()
                        return true
                    }
                }
                web = this
            }
        },
    )

    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        while (true) {
            val op = withTimeoutOrNull(30_000) { App.siteActions.ops.receive() }
            if (op == null) {
                App.siteActions.idle()
                return@LaunchedEffect
            }
            val r = try {
                perform(w, pages, op)
            } catch (e: kotlinx.coroutines.CancellationException) {
                op.result.complete(BookmarkResult.Failed("прервано"))
                throw e
            } catch (e: Exception) {
                BookmarkResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            op.result.complete(r)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            web?.apply {
                stopLoading()
                destroy()
            }
        }
    }
}

private suspend fun WebView.js(code: String): String? = suspendCancellableCoroutine { c ->
    evaluateJavascript(code) { c.resume(it) }
}

private suspend fun open(w: WebView, pages: MutableSharedFlow<String>, url: String): Boolean {
    w.loadUrl(url)
    return withTimeoutOrNull(25_000) { pages.first() } != null
}

private suspend fun perform(w: WebView, pages: MutableSharedFlow<String>, op: BookmarkOp): BookmarkResult {
    val url = App.api.abs(op.mangaUrl)
    if (!open(w, pages, url)) return BookmarkResult.Failed("страница не открылась")
    if (isChallengeTitle(w.title)) {
        // Cloudflare может пропустить сам за пару секунд
        withTimeoutOrNull(10_000) { pages.first() }
        if (isChallengeTitle(w.title)) return BookmarkResult.Failed("проверка Cloudflare")
    }
    val want = if (op.on) "add" else "remove"
    val opposite = if (op.on) "remove" else "add"
    val before = BookmarkScripts.parse(w.js(BookmarkScripts.DETECT))
    if (before.isEmpty()) return BookmarkResult.NoControl
    if (before.none { it.state == want } && before.any { it.state == opposite }) return BookmarkResult.Already
    val target = before.firstOrNull { it.state == want } ?: before.firstOrNull { it.state == "?" } ?: return BookmarkResult.NoControl
    w.js(BookmarkScripts.click(target.i))
    // AJAX-кнопка — ждём немного; ссылка — дождёмся загрузки новой страницы
    withTimeoutOrNull(5_000) { pages.first() } ?: delay(500)
    if (!open(w, pages, url)) return BookmarkResult.Unverified
    val after = BookmarkScripts.parse(w.js(BookmarkScripts.DETECT))
    return when {
        after.any { it.state == opposite } && after.none { it.state == want } -> BookmarkResult.Done
        after.any { it.state == want } && after.none { it.state == opposite } && target.state != "?" -> BookmarkResult.Failed("сайт не принял действие")
        else -> BookmarkResult.Unverified
    }
}
