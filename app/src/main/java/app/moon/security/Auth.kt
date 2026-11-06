package app.moon.security

import androidx.fragment.app.FragmentActivity
import app.moon.App

/** Сценарии входа по отпечатку. */
object Auth {
    /** Отпечаток → расшифровать пароль → автоматический вход на сайт. */
    fun loginWithFingerprint(activity: FragmentActivity, onNeedManual: () -> Unit) {
        val cipher = App.vault.decryptCipher()
        if (cipher == null) {
            App.toast("Сохранённый вход сброшен (менялись отпечатки) — войди на сайте ещё раз")
            onNeedManual()
            return
        }
        App.lock.suppress = true
        Biometric.withCipher(
            activity, cipher, "Вход на сайт", "Приложи палец",
            onSuccess = { c ->
                App.lock.suppress = false
                val creds = App.vault.read(c)
                if (creds == null) {
                    App.toast("Не удалось прочитать сохранённый вход")
                    onNeedManual()
                } else {
                    App.autoLogin.value = LoginRequest(creds)
                }
            },
            onError = { code, msg ->
                App.lock.suppress = false
                if (!Biometric.isCancel(code)) App.toast(msg)
            },
        )
    }

    /** Подтвердить отпечатком и сохранить логин/пароль. */
    fun saveCredentials(activity: FragmentActivity, creds: Credentials, onDone: (Boolean) -> Unit = {}) {
        Biometric.strongStatusText(activity)?.let {
            App.toast(it)
            onDone(false)
            return
        }
        val cipher = try {
            App.vault.encryptCipher()
        } catch (e: Exception) {
            App.toast("Не удалось создать ключ: ${e.message}")
            onDone(false)
            return
        }
        App.lock.suppress = true
        Biometric.withCipher(
            activity, cipher, "Вход по отпечатку", "Подтверди, чтобы сохранить вход",
            onSuccess = { c ->
                App.lock.suppress = false
                App.vault.store(c, creds)
                App.toast("Вход по отпечатку включён")
                onDone(true)
            },
            onError = { code, msg ->
                App.lock.suppress = false
                if (!Biometric.isCancel(code)) App.toast(msg)
                onDone(false)
            },
        )
    }
}
