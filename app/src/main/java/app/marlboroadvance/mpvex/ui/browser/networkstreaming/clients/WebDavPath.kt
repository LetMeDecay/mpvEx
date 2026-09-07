package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

/** Connection-relative WebDAV paths always start after the URL authority. */
internal fun webDavPath(basePath: String, relativePath: String): String =
  "/" + listOf(basePath.trim('/'), relativePath.trim('/')).filter { it.isNotEmpty() }.joinToString("/")
