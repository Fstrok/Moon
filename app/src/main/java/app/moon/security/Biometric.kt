package app.moon.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

object Biometric {
    private fun unlockAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Есть ли на телефоне хоть какая-то блокировка (отпечаток/лицо/PIN). */
    fun canUnlock(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(unlockAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    /** Есть ли сильная биометрия (отпечаток) — нужна для хранения пароля. */
    fun canStrong(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun strongStatusText(ctx: Context): String? = when (BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "В телефоне не добавлен отпечаток"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "В телефоне нет сканера отпечатка"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Сканер отпечатка недоступен"
        else -> "Отпечаток недоступен"
    }

    /** Разблокировка приложения: отпечаток, при неудаче — PIN телефона. */
    fun unlock(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (code: Int, msg: String) -> Unit,
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Moon")
            .setSubtitle("Приложи палец, чтобы открыть")
            .setAllowedAuthenticators(unlockAuthenticators())
            .setConfirmationRequired(false)
            .build()
        prompt(activity, { onSuccess() }, onError).authenticate(info)
    }

    /** Открыть шифр отпечатком (для сохранения/чтения пароля сайта). */
    fun withCipher(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeText: String = "Отмена",
        onSuccess: (Cipher) -> Unit,
        onError: (code: Int, msg: String) -> Unit,
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(negativeText)
            .setConfirmationRequired(false)
            .build()
        prompt(activity, { r ->
            val c = r.cryptoObject?.cipher
            if (c != null) onSuccess(c) else onError(-1, "Ключ недоступен")
        }, onError).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun prompt(
        activity: FragmentActivity,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (Int, String) -> Unit,
    ) = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess(result)
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errorCode, errString.toString())
    })

    fun isNegative(code: Int) = code == BiometricPrompt.ERROR_NEGATIVE_BUTTON

    fun isCancel(code: Int) = code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
        code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_CANCELED
}
