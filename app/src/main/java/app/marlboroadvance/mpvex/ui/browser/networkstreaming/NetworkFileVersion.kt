package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.domain.network.NetworkFile

internal fun NetworkFile.sameVersion(other: NetworkFile?): Boolean = other != null &&
  path == other.path && size == other.size && lastModified == other.lastModified && etag == other.etag
