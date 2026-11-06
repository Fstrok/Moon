package app.moon.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Блокировка приложения отпечатком при запуске и после ухода в фон. */
class AppLock(private val enabled: () -> Boolean, private val delaySec: () -> Int) {
    private val _locked = MutableStateFlow(enabled())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundAt = 0L

    /** Пока открыт системный диалог (отпечаток/PIN/выбор файла), уход в фон не считаем. */
    @Volatile
    var suppress = false

    fun onBackground() {
        if (!suppress) backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        if (suppress || backgroundAt == 0L) return
        val away = SystemClock.elapsedRealtime() - backgroundAt
        backgroundAt = 0L
        if (enabled() && away >= delaySec() * 1000L) _locked.value = true
    }

    fun lockNow() {
        if (enabled()) _locked.value = true
    }

    fun unlock() {
        _locked.value = false
    }
}
