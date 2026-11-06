package app.moon

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Запуск приложения и проход по всем экранам без сети и телефона. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun walksThroughScreens() {
        // без настроенной блокировки экрана в «телефоне» приложение само снимает блокировку
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnUiThread { App.lock.unlock() }
        waitText("Каталог")
        rule.onNodeWithText("Популярное").performClick()

        rule.onNodeWithText("Поиск").performClick()
        waitText("Подбор по тегам")

        rule.onNodeWithText("Избранное").performClick()
        waitText("Избранное · 0")

        rule.onNodeWithText("История").performClick()
        waitText("Пока ничего не прочитано")

        rule.onNodeWithText("Ещё").performClick()
        waitText("Вход не выполнен")
        rule.onNodeWithText("Настройки").performClick()
        waitText("Блокировка отпечатком")
        rule.onNodeWithContentDescription("Назад").performClick()

        rule.onNodeWithText("Загрузки").performClick()
        waitText("Загрузки · 0 Б")
        rule.onNodeWithContentDescription("Назад").performClick()

        rule.onNodeWithText("Теги").performClick()
        waitText("Теги")
    }
}
