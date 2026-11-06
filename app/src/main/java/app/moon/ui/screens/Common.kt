package app.moon.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
}

private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("ru"))

fun formatDate(ms: Long): String? = if (ms <= 0) null else dateFmt.format(Date(ms))

fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 60 -> "только что"
        d < 3600 -> "${d / 60} мин назад"
        d < 86400 -> "${d / 3600} ч назад"
        d < 86400 * 7 -> "${d / 86400} дн назад"
        else -> formatDate(ms).orEmpty()
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f ГБ".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f МБ".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%d КБ".format(b / (1L shl 10))
    else -> "$b Б"
}
