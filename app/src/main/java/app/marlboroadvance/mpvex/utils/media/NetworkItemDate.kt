package app.marlboroadvance.mpvex.utils.media

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Parses common container metadata date formats into epoch milliseconds. */
object NetworkItemDate {
  private val offsetFormats = listOf(
    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSX"),
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX"),
  )

  fun parse(value: String?): Long {
    val date = value?.trim()?.removePrefix("UTC ")?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
    date.toLongOrNull()?.let { timestamp ->
      when (date.length) {
        10 -> return timestamp * 1000L
        13 -> return timestamp
      }
    }
    runCatching { return Instant.parse(date).toEpochMilli() }
    runCatching {
      return ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }
    offsetFormats.forEach { format ->
      runCatching { return OffsetDateTime.parse(date, format).toInstant().toEpochMilli() }
    }
    runCatching {
      return LocalDateTime.parse(date, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss.SSS").forEach { pattern ->
      runCatching {
        return LocalDateTime.parse(date, DateTimeFormatter.ofPattern(pattern))
          .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
      }
    }
    runCatching {
      return LocalDate.parse(date.take(10), DateTimeFormatter.ISO_LOCAL_DATE)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    return 0L
  }
}
