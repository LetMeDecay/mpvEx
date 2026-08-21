package app.marlboroadvance.mpvex.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * App-level language switcher.
 *
 * Persists the selected language tag in the app's default SharedPreferences
 * (same store as [app.marlboroadvance.mpvex.preferences.AppearancePreferences.appLocale])
 * and delegates resource updates to AndroidX per-app locales. An empty tag
 * means "follow the system locale".
 */
object LocaleManager {
  const val KEY_LANGUAGE = "app_locale"

  fun setLanguage(context: Context, languageTag: String) {
    val normalizedTag = languageTag.trim()
    PreferenceManager
      .getDefaultSharedPreferences(context)
      .edit()
      .putString(KEY_LANGUAGE, normalizedTag)
      .apply()
    updateLegacyApplicationResources(context, normalizedTag)
    AppCompatDelegate.setApplicationLocales(
      if (normalizedTag.isBlank()) LocaleListCompat.getEmptyLocaleList()
      else LocaleListCompat.forLanguageTags(normalizedTag),
    )
  }

  fun getLanguageTag(context: Context): String =
    PreferenceManager
      .getDefaultSharedPreferences(context)
      .getString(KEY_LANGUAGE, "")
      ?: ""

  fun applySavedLanguage(context: Context) {
    // Android 13+ owns the per-app locale. AppCompatDelegate mirrors that
    // system setting; applying our legacy preference at process start would
    // overwrite a newer choice made in Android Settings.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      syncFrameworkLocalePreference(context)
      return
    }
    val tag = getLanguageTag(context)
    AppCompatDelegate.setApplicationLocales(
      if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
      else LocaleListCompat.forLanguageTags(tag),
    )
  }

  /**
   * Wrap only the Application context on Android 12 and older. AppCompat
   * wraps Activities, but repositories/ViewModels often resolve strings from
   * Application.getString(), which otherwise remains in the system locale.
   * On Android 13+ the framework per-app locale (and generated localeConfig)
   * is the single source of truth, so this intentionally returns [base].
   */
  fun wrapApplicationContext(base: Context): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
    val tag = getLanguageTag(base).trim()
    if (tag.isBlank()) return base
    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isBlank()) return base
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(locale)
    return base.createConfigurationContext(configuration)
  }

  /** Keep an already-running legacy Application wrapper in sync after a change. */
  @Suppress("DEPRECATION")
  private fun updateLegacyApplicationResources(context: Context, languageTag: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    val application = context.applicationContext
    val locale =
      if (languageTag.isBlank()) systemLocale() ?: return
      else Locale.forLanguageTag(languageTag).takeIf { it.language.isNotBlank() } ?: return
    val configuration = Configuration(application.resources.configuration)
    configuration.setLocale(locale)
    application.resources.updateConfiguration(configuration, application.resources.displayMetrics)
  }

  /** Read the device locale, rather than Locale.getDefault(), which AppCompat may have changed. */
  private fun systemLocale(): Locale? {
    val locales = Resources.getSystem().configuration.locales
    return LocaleSelection.firstValidLocale((0 until locales.size()).map(locales::get))
  }

  /** API 33+ framework/AppCompat locale is authoritative; this only mirrors it for the UI. */
  private fun syncFrameworkLocalePreference(context: Context) {
    val frameworkTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    if (preferences.getString(KEY_LANGUAGE, "") != frameworkTag) {
      preferences.edit().putString(KEY_LANGUAGE, frameworkTag).apply()
    }
  }
}
