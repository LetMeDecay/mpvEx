package app.marlboroadvance.mpvex.utils

import android.content.Context
import android.content.res.Configuration
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * App-level language switcher.
 *
 * Persists the selected language tag in the app's default SharedPreferences
 * (same store as [app.marlboroadvance.mpvex.preferences.AppearancePreferences.appLocale])
 * and wraps contexts with the matching configuration. Activities and the
 * Application must call [wrap] in [android.content.ContextWrapper.attachBaseContext]
 * so that Compose's stringResource and direct getString() calls resolve in the
 * selected language. An empty tag means "follow the system locale".
 */
object LocaleManager {
  const val KEY_LANGUAGE = "app_locale"

  fun setLanguage(context: Context, languageTag: String) {
    PreferenceManager
      .getDefaultSharedPreferences(context)
      .edit()
      .putString(KEY_LANGUAGE, languageTag)
      .apply()
  }

  fun getLanguageTag(context: Context): String =
    PreferenceManager
      .getDefaultSharedPreferences(context)
      .getString(KEY_LANGUAGE, "")
      ?: ""

  fun wrap(context: Context): Context {
    val tag = getLanguageTag(context)
    if (tag.isBlank()) return context
    val locale = Locale.forLanguageTag(tag)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config)
  }
}
