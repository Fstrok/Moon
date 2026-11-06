package app.moon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.moon.App
import app.moon.security.Biometric
import app.moon.ui.common.findActivity
import kotlinx.coroutines.delay

/** Отпечаток → разблокировать приложение. Если сайт разлогинил, тем же касанием расшифровывается пароль и идёт вход. */
fun unlockPrompt(a: FragmentActivity) {
    if (!Biometric.canUnlock(a)) {
        App.lock.unlock()
        return
    }
    val needLogin = App.vault.hasCredentials && !App.session.check() && Biometric.canStrong(a)
    val cipher = if (needLogin) App.vault.decryptCipher() else null
    App.lock.suppress = true
    if (cipher != null) {
        Biometric.withCipher(
            a, cipher, "Moon", "Приложи палец — открою и войду на сайт",
            negativeText = "PIN-код",
            onSuccess = { c ->
                App.lock.suppress = false
                App.lock.unlock()
                App.vault.read(c)?.let { App.autoLogin.value = app.moon.security.LoginRequest(it) }
            },
            onError = { code, msg ->
                App.lock.suppress = false
                when {
                    Biometric.isNegative(code) -> plainUnlock(a)
                    Biometric.isCancel(code) -> {}
                    else -> App.toast(msg)
                }
            },
        )
    } else {
        plainUnlock(a)
    }
}

private fun plainUnlock(a: FragmentActivity) {
    App.lock.suppress = true
    Biometric.unlock(
        a,
        onSuccess = {
            App.lock.suppress = false
            App.lock.unlock()
        },
        onError = { code, msg ->
            App.lock.suppress = false
            if (!Biometric.isCancel(code)) App.toast(msg)
        },
    )
}

@Composable
fun LockScreen() {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    LaunchedEffect(Unit) {
        delay(200)
        activity?.let(::unlockPrompt)
    }
    BackHandler { activity?.moveTaskToBack(true) }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.NightsStay, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Moon", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { activity?.let(::unlockPrompt) }) {
                Icon(Icons.Outlined.Fingerprint, null)
                Spacer(Modifier.width(8.dp))
                Text("Разблокировать")
            }
        }
    }
}
