package app.marlboroadvance.mpvex.utils.media

/**
 * Stable identifiers for files exposed through the local network proxy.
 *
 * Proxy URLs contain an ephemeral stream id, so they must never be used as a
 * playback-history key. The connection id and remote path are stable across
 * proxy instances and app launches.
 */
object NetworkMediaIdentity {
  fun forFile(connectionId: Long, filePath: String): String =
    "network_${connectionId}_${filePath.hashCode()}"
}
