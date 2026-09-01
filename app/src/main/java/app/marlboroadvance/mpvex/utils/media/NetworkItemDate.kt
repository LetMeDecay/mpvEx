package app.marlboroadvance.mpvex.utils.media

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Parses common container metadata date formats into epoch milliseconds. */
object NetworkItemDate {
  private val offsetFormats = listOf(
    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSX"),
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX"),
  )

  fun parse(value: String?): Long {
    val date = value?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
    runCatching { return Instant.parse(date).toEpochMilli() }
    offsetFormats.forEach { format ->
      runCatching { return OffsetDateTime.parse(date, format).toInstant().toEpochMilli() }
    }
    runCatching {
      return LocalDateTime.parse(date, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    runCatching {
      return LocalDate.parse(date.take(10), DateTimeFormatter.ISO_LOCAL_DATE)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    return 0L
  }
}
