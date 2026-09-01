package app.marlboroadvance.mpvex.utils.media

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkItemDateTest {
  @Test
  fun parsesIsoContainerDate() {
    assertEquals(
      Instant.parse("2024-08-21T12:34:56Z").toEpochMilli(),
      NetworkItemDate.parse("2024-08-21T12:34:56Z"),
    )
  }

  @Test
  fun parsesCompactContainerDate() {
    assertEquals(
      Instant.parse("2024-08-21T12:34:56Z").toEpochMilli(),
      NetworkItemDate.parse("20240821T123456Z"),
    )
  }

  @Test
  fun invalidDateIsUnknown() {
    assertEquals(0L, NetworkItemDate.parse("not-a-date"))
  }

  @Test
  fun parsesUtcPrefixedVideoCreationDate() {
    assertEquals(
      Instant.parse("2024-08-21T12:34:56Z").toEpochMilli(),
      NetworkItemDate.parse("UTC 2024-08-21T12:34:56Z"),
    )
  }

  @Test
  fun parsesEpochSecondsFromWebDavProperty() {
    assertEquals(1_724_243_696_000L, NetworkItemDate.parse("1724243696"))
  }

  @Test
  fun parsesRfc1123WebDavDate() {
    assertEquals(
      Instant.parse("2024-08-21T12:34:56Z").toEpochMilli(),
      NetworkItemDate.parse("Wed, 21 Aug 2024 12:34:56 GMT"),
    )
  }
}
