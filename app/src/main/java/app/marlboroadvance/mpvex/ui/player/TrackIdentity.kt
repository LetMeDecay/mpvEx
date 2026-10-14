package app.marlboroadvance.mpvex.ui.player

import java.security.MessageDigest

/** Numeric native IDs are scoped to one load, never a durable track identity. */
internal object TrackIdentity {
  const val OFF = "off"
  data class Track(val id: Int, val key: String)

  fun key(fields: List<String>): String {
    val canonical = fields.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
  }

  fun externalKey(type: String, uri: String): String = key(listOf("external-uri", type, uri))

  fun capture(selected: Int, tracks: List<Track>): String =
    if (selected <= 0) OFF else tracks.singleOrNull { it.id == selected }?.key.orEmpty()

  // Duplicate descriptors are ambiguous; do not fall back to an old numeric ID.
  fun resolve(key: String, tracks: List<Track>): Int? = when (key) {
    OFF -> -1
    "" -> null
    else -> tracks.singleOrNull { it.key == key }?.id
  }
}
