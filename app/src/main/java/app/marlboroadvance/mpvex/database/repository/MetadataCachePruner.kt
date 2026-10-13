package app.marlboroadvance.mpvex.database.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun pruneMissingMetadata(
  paths: List<String>,
  exists: (String) -> Boolean,
  delete: suspend (List<String>) -> Unit,
): Int {
  val stale = paths.filterNot(exists)
  for (chunk in stale.chunked(999)) {
    currentCoroutineContext().ensureActive()
    delete(chunk)
  }
  return stale.size
}
