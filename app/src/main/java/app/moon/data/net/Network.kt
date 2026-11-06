package app.moon.data.net

import android.webkit.CookieManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Сайт показал проверку Cloudflare — её надо пройти в WebView. */
class CloudflareException(val url: String) : IOException("Cloudflare challenge: $url")

class HttpError(val code: Int, val url: String) : IOException("HTTP $code")

/** Действие доступно только после входа на сайт. */
class AuthRequiredException : IOException("Нужен вход на сайт")

/** Общие cookies с WebView: вход и проверка Cloudflare в WebView сразу работают в приложении. */
class WebViewCookieJar : CookieJar {
    private val cm: CookieManager get() = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val u = url.toString()
        cookies.forEach { cm.setCookie(u, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = cm.getCookie(url.toString()) ?: return emptyList()
        return raw.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

class HeadersInterceptor(
    private val userAgent: () -> String,
    private val baseUrl: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val b = req.newBuilder()
            .header("User-Agent", userAgent())
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.6")
        if (req.header("Referer") == null) b.header("Referer", baseUrl().trimEnd('/') + "/")
        return chain.proceed(b.build())
    }
}

class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val resp = chain.proceed(req)
        if (resp.code in CF_CODES && resp.header("Server")?.contains("cloudflare", true) == true) {
            val mitigated = resp.header("cf-mitigated")?.contains("challenge", true) == true
            val body = if (mitigated) "" else runCatching { resp.peekBody(128 * 1024).string() }.getOrDefault("")
            if (mitigated || body.contains("challenge-platform") || body.contains("Just a moment")) {
                resp.close()
                throw CloudflareException(req.url.toString())
            }
        }
        return resp
    }

    private companion object {
        val CF_CODES = setOf(403, 429, 503)
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
