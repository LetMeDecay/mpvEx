package app.marlboroadvance.mpvex.utils

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocaleSelectionTest {
  @Test
  fun skipsInvalidLocaleEntries() {
    val selected = LocaleSelection.firstValidLocale(listOf(Locale("", ""), Locale.US))

    assertEquals(Locale.US, selected)
  }

  @Test
  fun returnsNullWhenSystemConfigurationHasNoLanguage() {
    assertNull(LocaleSelection.firstValidLocale(listOf(Locale("", ""))))
  }
}
