package app.marlboroadvance.mpvex.utils.media

/** Reads date values from WebDAV extension properties without accessing QName internals. */
object WebDavDateProperties {
  private val creationNames =
    setOf("creationdate", "creationtime", "win32creationtime", "birthtime", "crtime")
  private val videoCreationNames = setOf("itemdate", "videoitemdate")

  fun fileCreationDate(properties: Map<*, String>): Long =
    findDate(properties, creationNames)

  fun videoCreationDate(properties: Map<*, String>): Long =
    findDate(properties, videoCreationNames)

  private fun findDate(
    properties: Map<*, String>,
    acceptedNames: Set<String>,
  ): Long =
    properties.entries
      .firstNotNullOfOrNull { (key, value) ->
        val name =
          key
            ?.toString()
            ?.substringAfterLast('}')
            ?.substringAfterLast(':')
            ?.filter(Char::isLetterOrDigit)
            ?.lowercase()
        value.takeIf { name in acceptedNames }?.let(NetworkItemDate::parse)?.takeIf { it > 0 }
      }
      ?: 0L
}
