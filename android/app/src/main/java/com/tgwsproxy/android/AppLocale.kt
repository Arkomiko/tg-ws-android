package com.tgwsproxy.android

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Язык и тема приложения.
 *
 * Реализовано подменой конфигурации в `attachBaseContext`, а не через
 * `LocaleManager.setApplicationLocales` и `UiModeManager.setApplicationNightMode`.
 * Причина: системные API применяются только к тем экранам, которые будут
 * созданы позже, а уже открытые остаются со старыми ресурсами — на устройстве
 * это выглядело как «переключение не работает». Подмена конфигурации даёт
 * предсказуемый результат на всех версиях Android и не требует AndroidX.
 *
 * Набор вариантов совпадает с десктопной версией (`ui/i18n`, `ctk_tray_ui.py`):
 *   язык — «Русский» и «English», без пункта «как в системе»;
 *   тема  — «Авто», «Светлая», «Тёмная».
 * При первом запуске язык выбирается по системному, как в
 * `ui/i18n/detect_system_language()`.
 */
object AppLocale {

    const val THEME_AUTO = "auto"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val LANG_RU = "ru"
    const val LANG_EN = "en"

    /** Языки, для которых есть каталог в app/localization. */
    val SUPPORTED = listOf(LANG_RU, LANG_EN)

    /**
     * Оборачивает контекст выбранными языком и темой.
     * Вызывается из `attachBaseContext` каждой Activity и сервиса.
     */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)

        val locale = Locale(resolveLanguage(base))
        Locale.setDefault(locale)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        when (ProxyConfigStore.theme(base)) {
            THEME_LIGHT -> config.setNightMode(Configuration.UI_MODE_NIGHT_NO)
            THEME_DARK -> config.setNightMode(Configuration.UI_MODE_NIGHT_YES)
            else -> Unit  // «Авто» — оставляем как в системе
        }

        return base.createConfigurationContext(config)
    }

    /** Сохранённый язык, а если его ещё нет — системный, иначе английский. */
    fun resolveLanguage(context: Context): String {
        val stored = ProxyConfigStore.language(context)
        if (stored in SUPPORTED) return stored
        val system = Locale.getDefault().language.lowercase()
        return if (system in SUPPORTED) system else LANG_EN
    }

    private fun Configuration.setNightMode(mode: Int) {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
    }
}

/**
 * Базовая Activity: подставляет выбранные язык и тему до создания экрана.
 * Наследуются все экраны приложения.
 */
abstract class ThemedActivity : android.app.Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }
}
