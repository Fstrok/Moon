package app.moon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.widget.Toast
import app.moon.data.download.Downloads
import app.moon.data.net.CloudflareInterceptor
import app.moon.data.net.CookieModeInterceptor
import app.moon.data.net.HeadersInterceptor
import app.moon.data.net.WebViewCookieJar
import app.moon.data.site.NudeMoonApi
import app.moon.data.store.AppSettings
import app.moon.data.store.Categories
import app.moon.data.store.CategoriesData
import app.moon.data.store.SiteFavorites
import app.moon.data.store.SyncData
import app.moon.data.site.SiteActions
import app.moon.data.store.JsonStore
import app.moon.data.store.Library
import app.moon.data.store.LibraryData
import app.moon.security.AppLock
import app.moon.security.CredentialVault
import app.moon.security.LoginRequest
import app.moon.security.LoginResult
import app.moon.security.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Простой service locator. */
object App {
    lateinit var context: Context
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: JsonStore<AppSettings>
        private set
    lateinit var library: Library
        private set
    lateinit var client: OkHttpClient
        private set
    lateinit var api: NudeMoonApi
        private set
    lateinit var downloads: Downloads
        private set
    lateinit var vault: CredentialVault
        private set
    lateinit var session: Session
        private set
    lateinit var lock: AppLock
        private set
    lateinit var categories: Categories
        private set
    val siteActions = SiteActions()
    lateinit var siteFavorites: SiteFavorites
        private set

    var userAgent: String = ""
        private set

    /** Растёт после прохождения Cloudflare или входа — экраны с ошибкой перезагружаются. */
    val retrySignal = MutableStateFlow(0)

    /** Запрос входа на сайт (логин/пароль введены или расшифрованы отпечатком). */
    val autoLogin = MutableStateFlow<LoginRequest?>(null)

    /** Результаты входа для экрана входа. */
    val loginResults = MutableSharedFlow<LoginResult>(extraBufferCapacity = 4)

    /** Клавиши громкости для читалки: +1 вперёд, -1 назад. */
    val volumeKeys = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    @Volatile
    var readerActive = false

    fun init(ctx: Context) {
        context = ctx.applicationContext
        settings = JsonStore(File(context.filesDir, "settings.json"), AppSettings.serializer(), AppSettings(), scope)
        library = Library(JsonStore(File(context.filesDir, "library.json"), LibraryData.serializer(), LibraryData(), scope))
        userAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
        )
        val base = { settings.value.baseUrl }
        session = Session(base)
        session.ensureDefaultCookies()
        session.refresh()
        client = OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .addInterceptor(HeadersInterceptor({ userAgent }, base))
            .addInterceptor(CloudflareInterceptor())
            .addNetworkInterceptor(CookieModeInterceptor())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
        api = NudeMoonApi(client, base) { session.check() }
        downloads = Downloads(File(context.filesDir, "downloads"), { api }, { client }, scope)
        vault = CredentialVault(context)
        lock = AppLock({ settings.value.lockEnabled }, { settings.value.lockDelaySec })
        categories = Categories(
            JsonStore(File(context.filesDir, "categories.json"), CategoriesData.serializer(), CategoriesData(), scope),
            { api },
            { session.check() },
            scope,
        ) { added -> toast("Новые разделы: " + added.joinToString { it.name }) }
        siteFavorites = SiteFavorites(
            JsonStore(File(context.filesDir, "site_favorites.json"), SyncData.serializer(), SyncData(), scope),
            library,
            { api },
            { categories.value.bookmarksUrl },
            { session.check() },
            siteActions,
            scope,
            ::toast,
        )
        library.onUserToggle = { item, added -> siteFavorites.onLocalToggle(item, added) }

        // при запуске: если уже вошли — обновить разделы и сверить избранное с сайтом
        scope.launch {
            delay(1500)
            if (session.check()) {
                if (categories.isStale()) categories.refresh().join()
                siteFavorites.syncIfStale()
            }
        }

        // вход/выход меняет содержимое сайта: пересобираем категории и теги
        scope.launch {
            session.loggedIn.drop(1).collect { inNow ->
                api.invalidate()
                signalRetry()
                categories.refresh().join()
                if (inNow) siteFavorites.sync()
            }
        }
        // сменили адрес сайта (зеркало)
        scope.launch {
            settings.state.map { it.baseUrl }.distinctUntilChanged().drop(1).collect {
                session.ensureDefaultCookies()
                session.refresh()
                api.invalidate()
                categories.refresh()
            }
        }
        // после прохождения Cloudflare догружаем то, что не получилось
        scope.launch { retrySignal.drop(1).collect { categories.refreshIfStale() } }
    }

    fun signalRetry() {
        retrySignal.value = retrySignal.value + 1
    }

    private val main = Handler(Looper.getMainLooper())

    fun toast(msg: String) {
        main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}
