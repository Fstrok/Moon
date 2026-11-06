package app.moon.data.store

import app.moon.data.ReaderMode
import app.moon.data.Sort
import kotlinx.serialization.Serializable

const val DEFAULT_BASE_URL = "https://nude-moon.org"

@Serializable
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val lockEnabled: Boolean = true,
    val lockDelaySec: Int = 30,
    val secureScreen: Boolean = true,
    val readerMode: ReaderMode = ReaderMode.WEBTOON,
    val keepScreenOn: Boolean = true,
    val volumeKeys: Boolean = true,
    val tapZones: Boolean = true,
    val amoled: Boolean = false,
    val gridColumns: Int = 0,
    val defaultSort: Sort = Sort.DATE,
    /** Логин последнего успешного входа (для подсказки в форме; пароль тут не хранится). */
    val lastLogin: String = "",
)
