package app.moon.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.moon.App
import app.moon.data.Source
import app.moon.data.store.AppJson
import app.moon.ui.common.LocalNavigator
import app.moon.ui.common.Navigator
import app.moon.ui.common.Routes
import app.moon.ui.common.WebMode
import app.moon.ui.reader.ReaderScreen
import app.moon.ui.screens.CatalogScreen
import app.moon.ui.screens.DetailsScreen
import app.moon.ui.screens.DownloadsScreen
import app.moon.ui.screens.HistoryScreen
import app.moon.ui.screens.LibraryScreen
import app.moon.ui.screens.ListingScreen
import app.moon.ui.screens.LoginScreen
import app.moon.ui.screens.SectionsScreen
import app.moon.ui.screens.DiagnosticsScreen
import app.moon.ui.screens.MoreScreen
import app.moon.ui.screens.SearchScreen
import app.moon.ui.screens.SettingsScreen
import app.moon.ui.screens.TagsScreen
import app.moon.ui.web.AutoLoginHost
import app.moon.ui.web.SiteActionHostIfNeeded
import app.moon.ui.web.WebScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.CATALOG, "Каталог", Icons.Outlined.Explore, Icons.Filled.Explore),
    Tab(Routes.SEARCH, "Поиск", Icons.Outlined.Search, Icons.Filled.Search),
    Tab(Routes.LIBRARY, "Избранное", Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),
    Tab(Routes.HISTORY, "История", Icons.Outlined.History, Icons.Filled.History),
    Tab(Routes.MORE, "Ещё", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
)

private fun strArg(name: String) = navArgument(name) {
    type = NavType.StringType
    defaultValue = ""
}

@Composable
fun AppRoot(deepLink: String?, onDeepLinkHandled: () -> Unit) {
    val navController = rememberNavController()
    val navigator = remember(navController) { Navigator(navController) }
    val locked by App.lock.locked.collectAsState()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    val graphReady = entry != null
    LaunchedEffect(deepLink, graphReady) {
        if (deepLink != null && graphReady) {
            navigator.details(deepLink)
            onDeepLinkHandled()
        }
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    if (route in Routes.topLevel) {
                        NavigationBar {
                            tabs.forEach { t ->
                                val sel = route == t.route
                                NavigationBarItem(
                                    selected = sel,
                                    onClick = { if (!sel) navigator.top(t.route) },
                                    icon = { Icon(if (sel) t.selectedIcon else t.icon, null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    }
                },
            ) { pad ->
                NavHost(
                    navController = navController,
                    startDestination = Routes.CATALOG,
                    modifier = Modifier
                        .padding(pad)
                        .consumeWindowInsets(pad),
                    enterTransition = { fadeIn(tween(160)) },
                    exitTransition = { fadeOut(tween(160)) },
                ) {
                    composable(Routes.CATALOG) { CatalogScreen() }
                    composable(Routes.SEARCH) { SearchScreen() }
                    composable(Routes.LIBRARY) { LibraryScreen() }
                    composable(Routes.HISTORY) { HistoryScreen() }
                    composable(Routes.MORE) { MoreScreen() }
                    composable(Routes.TAGS) { TagsScreen() }
                    composable(Routes.DOWNLOADS) { DownloadsScreen() }
                    composable(Routes.SETTINGS) { SettingsScreen() }
                    composable(Routes.LOGIN) { LoginScreen() }
                    composable(Routes.SECTIONS) { SectionsScreen() }
                    composable(Routes.DIAG) { DiagnosticsScreen() }
                    composable(Routes.LISTING, listOf(strArg("src"))) { e ->
                        val raw = e.arguments?.getString("src").orEmpty()
                        val src = runCatching { AppJson.decodeFromString(Source.serializer(), raw) }
                            .getOrDefault(Source.Catalog(App.settings.value.defaultSort))
                        ListingScreen(src)
                    }
                    composable(Routes.DETAILS, listOf(strArg("url"), strArg("title"), strArg("cover"))) { e ->
                        val a = e.arguments
                        DetailsScreen(
                            url = a?.getString("url").orEmpty(),
                            title = a?.getString("title").orEmpty(),
                            cover = a?.getString("cover")?.ifBlank { null },
                        )
                    }
                    composable(Routes.READER, listOf(strArg("manga"), strArg("chapter"))) { e ->
                        ReaderScreen(e.arguments?.getString("manga").orEmpty(), e.arguments?.getString("chapter").orEmpty())
                    }
                    composable(Routes.WEB, listOf(strArg("url"), strArg("mode"))) { e ->
                        val mode = runCatching { WebMode.valueOf(e.arguments?.getString("mode").orEmpty()) }.getOrDefault(WebMode.BROWSE)
                        WebScreen(e.arguments?.getString("url").orEmpty().ifBlank { App.api.base + "/" }, mode)
                    }
                }
            }
            AutoLoginHost()
            SiteActionHostIfNeeded()
            if (locked) LockScreen()
        }
    }
}

/** https://nude-moon.org/123--name.html → /123--name.html */
fun deepLinkPath(uri: Uri?): String? {
    uri ?: return null
    if (uri.scheme != "https" && uri.scheme != "http") return null
    val path = uri.encodedPath ?: return null
    return if (path.endsWith(".html")) path else null
}
