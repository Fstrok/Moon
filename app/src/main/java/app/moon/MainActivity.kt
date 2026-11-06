package app.moon

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.moon.ui.AppRoot
import app.moon.ui.deepLinkPath
import app.moon.ui.theme.MoonTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        applySecure(App.settings.value.secureScreen)
        lifecycleScope.launch {
            App.settings.state.map { it.secureScreen }.distinctUntilChanged().collect(::applySecure)
        }
        if (savedInstanceState == null) deepLink = deepLinkPath(intent?.data)
        setContent {
            val s by App.settings.state.collectAsState()
            MoonTheme(amoled = s.amoled) {
                AppRoot(deepLink) { deepLink = null }
            }
        }
    }

    private fun applySecure(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkPath(intent.data)?.let { deepLink = it }
    }

    override fun onStart() {
        super.onStart()
        App.lock.onForeground()
        App.session.refresh()
    }

    override fun onStop() {
        super.onStop()
        App.lock.onBackground()
        CookieManager.getInstance().flush()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (App.readerActive && App.settings.value.volumeKeys &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            App.volumeKeys.tryEmit(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
