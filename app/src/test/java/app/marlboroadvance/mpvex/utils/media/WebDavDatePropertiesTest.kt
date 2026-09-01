package app.marlboroadvance.mpvex.utils.media

import java.time.Instant
import javax.xml.namespace.QName
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavDatePropertiesTest {
  @Test
  fun readsFileCreationDateAliases() {
    val properties = mapOf(QName("urn:test", "Win32CreationTime") to "2024-08-21T12:34:56Z")

    assertEquals(
      Instant.parse("2024-08-21T12:34:56Z").toEpochMilli(),
      WebDavDateProperties.fileCreationDate(properties),
    )
  }

  @Test
  fun readsVideoItemDate() {
    val properties = mapOf(QName("urn:test", "itemdate") to "20240821T123456Z")

    assertEquals(
      Instant.parse("2024-08-21T12:34:56Z").toEpochMilli(),
      WebDavDateProperties.videoCreationDate(properties),
    )
  }
}
