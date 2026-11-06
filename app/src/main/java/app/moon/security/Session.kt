package app.moon.security

import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Состояние входа на сайт (cookie PHP-Fusion `fusion_user`). */
class Session(private val baseUrl: () -> String) {
    private val cm: CookieManager get() = CookieManager.getInstance()

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    fun check(): Boolean = cookies().any { (k, v) -> k == AUTH_COOKIE && v.isNotBlank() && v != "deleted" }

    fun refresh(): Boolean = check().also { _loggedIn.value = it }

    fun hasCloudflareClearance(): Boolean = cookies().any { it.first == "cf_clearance" }

    private fun cookies(): List<Pair<String, String>> =
        cm.getCookie(baseUrl())?.split(';')?.map { it.trim() }?.filter { '=' in it }
            ?.map { it.substringBefore('=') to it.substringAfter('=') } ?: emptyList()

    /** Cookies, которые ставит сайт/парсер: 18+ подтверждение и мобильная вёрстка (и для доменных копий). */
    fun ensureDefaultCookies() {
        val base = baseUrl()
        val host = base.toHttpUrlOrNull()?.host.orEmpty()
        for (c in listOf("NMfYa=1", "nm_mobile=1")) {
            cm.setCookie(base, "$c; Path=/; Max-Age=31536000")
            if (host.isNotEmpty()) cm.setCookie(base, "$c; Domain=.$host; Path=/; Max-Age=31536000")
        }
        cm.flush()
    }

    /** Сайт показал страницу гостя, хотя cookie входа есть: сессия на сайте закончилась. */
    fun markExpired() {
        if (!check()) return
        logout()
    }

    fun logout() {
        val base = baseUrl()
        val host = base.toHttpUrlOrNull()?.host.orEmpty()
        val gone = "Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
        cookies().map { it.first }.filter { it.startsWith("fusion_") }.forEach { name ->
            cm.setCookie(base, "$name=; $gone")
            if (host.isNotEmpty()) {
                cm.setCookie(base, "$name=; Domain=$host; $gone")
                cm.setCookie(base, "$name=; Domain=.$host; $gone")
            }
        }
        cm.flush()
        refresh()
    }

    fun clearAllCookies(done: () -> Unit) {
        cm.removeAllCookies {
            ensureDefaultCookies()
            refresh()
            done()
        }
    }

    companion object {
        const val AUTH_COOKIE = "fusion_user"
    }
}
