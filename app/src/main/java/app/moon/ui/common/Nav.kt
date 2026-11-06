package app.moon.ui.common

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import app.moon.data.MangaItem
import app.moon.data.Source
import app.moon.data.store.AppJson

enum class WebMode { BROWSE, LOGIN, CLOUDFLARE }

object Routes {
    const val CATALOG = "catalog"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val HISTORY = "history"
    const val MORE = "more"
    const val TAGS = "tags"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val SECTIONS = "sections"
    const val DIAG = "diag"
    const val LISTING = "listing?src={src}"
    const val DETAILS = "details?url={url}&title={title}&cover={cover}"
    const val READER = "reader?manga={manga}&chapter={chapter}"
    const val WEB = "web?url={url}&mode={mode}"
    val topLevel = setOf(CATALOG, SEARCH, LIBRARY, HISTORY, MORE)
}

class Navigator(val nav: NavHostController) {
    private fun e(s: String?) = Uri.encode(s.orEmpty())

    fun back() {
        nav.popBackStack()
    }

    fun details(item: MangaItem) = nav.navigate("details?url=${e(item.url)}&title=${e(item.title)}&cover=${e(item.cover)}")
    fun details(url: String) = nav.navigate("details?url=${e(url)}&title=&cover=")
    fun reader(mangaUrl: String, chapterUrl: String) = nav.navigate("reader?manga=${e(mangaUrl)}&chapter=${e(chapterUrl)}")
    fun listing(source: Source) = nav.navigate("listing?src=${e(AppJson.encodeToString(Source.serializer(), source))}")
    fun web(url: String, mode: WebMode = WebMode.BROWSE) = nav.navigate("web?url=${e(url)}&mode=${mode.name}")
    fun tags() = nav.navigate(Routes.TAGS)
    fun downloads() = nav.navigate(Routes.DOWNLOADS)
    fun settings() = nav.navigate(Routes.SETTINGS)
    fun sections() = nav.navigate(Routes.SECTIONS)
    fun diag() = nav.navigate(Routes.DIAG)
    fun login() = nav.navigate(Routes.LOGIN) { launchSingleTop = true }
    fun top(route: String) = nav.navigate(route) {
        popUpTo(Routes.CATALOG) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

fun Context.findActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
