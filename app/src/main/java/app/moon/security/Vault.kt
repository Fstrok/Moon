package app.moon.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import app.moon.data.store.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Credentials(val login: String, val password: String)

/** Запрос входа на сайт. quiet — результат покажет экран входа, без всплывающих сообщений. */
data class LoginRequest(val creds: Credentials, val quiet: Boolean = false)

data class LoginResult(val ok: Boolean, val message: String, val creds: Credentials)

/**
 * Логин/пароль от сайта, зашифрованные ключом Android Keystore.
 * Ключ открывается только отпечатком (BIOMETRIC_STRONG) и сбрасывается, если в телефон добавили новый отпечаток.
 */
class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private val _login = MutableStateFlow(prefs.getString(K_LOGIN, null)?.takeIf { prefs.contains(K_DATA) })
    /** Логин сохранённого входа (null — вход по отпечатку не настроен). */
    val savedLogin: StateFlow<String?> = _login.asStateFlow()

    val hasCredentials: Boolean get() = prefs.contains(K_DATA)

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun deleteKey() = runCatching { keyStore().deleteEntry(ALIAS) }

    /** Шифр для сохранения. Бросает исключение, если в телефоне нет отпечатков. */
    fun encryptCipher(): Cipher = try {
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
    } catch (e: KeyPermanentlyInvalidatedException) {
        deleteKey()
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
    }

    /** Шифр для чтения или null, если данных нет / ключ сброшен (новый отпечаток). */
    fun decryptCipher(): Cipher? {
        val iv = prefs.getString(K_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return try {
            val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: run { clear(); return null }
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear()
            null
        } catch (e: Exception) {
            null
        }
    }

    fun store(cipher: Cipher, creds: Credentials) {
        val data = cipher.doFinal(AppJson.encodeToString(Credentials.serializer(), creds).toByteArray())
        prefs.edit()
            .putString(K_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(K_DATA, Base64.encodeToString(data, Base64.NO_WRAP))
            .putString(K_LOGIN, creds.login)
            .apply()
        _login.value = creds.login
    }

    fun read(cipher: Cipher): Credentials? = runCatching {
        val data = Base64.decode(prefs.getString(K_DATA, null) ?: return null, Base64.NO_WRAP)
        AppJson.decodeFromString(Credentials.serializer(), String(cipher.doFinal(data)))
    }.getOrNull()

    fun clear() {
        prefs.edit().clear().apply()
        deleteKey()
        _login.value = null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "moon_site_credentials"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val K_IV = "iv"
        const val K_DATA = "data"
        const val K_LOGIN = "login"
    }
}
