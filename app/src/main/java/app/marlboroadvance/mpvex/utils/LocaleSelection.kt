package app.marlboroadvance.mpvex.utils

import java.util.Locale

/** Pure locale selection used when an API<=32 app locale is reset to system. */
internal object LocaleSelection {
  fun firstValidLocale(locales: Iterable<Locale>): Locale? =
    locales.firstOrNull { it.language.isNotBlank() }
}
