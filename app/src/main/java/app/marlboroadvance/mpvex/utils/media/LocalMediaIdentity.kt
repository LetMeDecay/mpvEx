package app.marlboroadvance.mpvex.utils.media

import java.io.File
import java.net.URI
import java.security.MessageDigest

/** Filename-only legacy rows remain untouched: they cannot identify a unique file. */
internal object LocalMediaIdentity {
  fun forUri(value: String): String {
    val normalized = if (value.startsWith('/')) File(value).canonicalFile.toURI().toASCIIString() else {
      val uri = URI(value)
      if (uri.scheme.equals("file", ignoreCase = true)) {
        File(uri.path).canonicalFile.toURI().toASCIIString()
      } else uri.normalize().toASCIIString()
    }
    val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    return "local_v2_$hash"
  }
}
