package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal object PlayerConfigRepository {
  data class Request(val directoryUri: String, val mpvConf: String, val inputConf: String,
                     val fontsUri: String)
  private var store: ConfigSnapshotStore? = null

  private fun store(context: Context): ConfigSnapshotStore = synchronized(this) {
    store ?: ConfigSnapshotStore(File(context.filesDir, "player-config")).also { store = it }
  }

  suspend fun prepare(context: Context, request: Request): ConfigSnapshotStore.Lease {
    var acquired: ConfigSnapshotStore.Lease? = null
    try {
      return withContext(Dispatchers.IO) {
        val operationContext = coroutineContext
        store(context).acquire { target ->
          operationContext.ensureActive()
          for (asset in listOf("cacert.pem", "subfont.ttf")) {
            context.assets.open(asset).use { input -> File(target, asset).outputStream().use(input::copyTo) }
          }
          val tree = request.directoryUri.takeIf { it.isNotBlank() }?.let {
            checkNotNull(DocumentFile.fromTreeUri(context, it.toUri()))
              .also { dir -> check(dir.exists() && dir.canRead()) { "Configuration directory unavailable" } }
          }
          val children = tree?.let { listChildren(context, it) }.orEmpty()
          for ((name, fallback) in listOf("mpv.conf" to request.mpvConf, "input.conf" to request.inputConf)) {
            val source = children.firstOrNull { it.isFile && it.name.equals(name, true) }
            if (source == null) File(target, name).writeText(fallback)
            else copyFile(context, source, File(target, name))
          }
          fun copyTree(source: DocumentFile, destination: File, depth: Int = 0) {
            operationContext.ensureActive()
            check(depth < 32) { "Configuration directory nesting too deep" }
            check(destination.isDirectory || destination.mkdirs())
            for (child in listChildren(context, source)) {
              val name = checkNotNull(child.name)
              check(name != "." && name != ".." && '/' !in name && '\\' !in name)
              val file = File(destination, name)
              if (child.isDirectory) copyTree(child, file, depth + 1)
              else if (child.isFile) copyFile(context, child, file)
            }
          }
          for (name in listOf("scripts", "script-opts", "shaders")) {
            children.firstOrNull { it.isDirectory && it.name.equals(name, true) }
              ?.let { copyTree(it, File(target, name)) }
          }
          val fonts = File(target, "fonts").apply { mkdirs() }
          val fontTree = children.firstOrNull { it.isDirectory && it.name.equals("fonts", true) }
          val fontFiles = fontTree?.let { listChildren(context, it) } ?: children
          fun isFont(name: String) = name.substringAfterLast('.', "").lowercase() in
            setOf("ttf", "otf", "ttc", "woff", "woff2")
          // Preserve fonts selected/imported through the existing preferences UI.
          File(context.filesDir, "fonts").takeIf { tree == null && request.fontsUri.isBlank() }?.listFiles()?.filter { it.isFile && isFont(it.name) }
            ?.forEach { it.copyTo(File(fonts, it.name), overwrite = true) }
          for (source in fontFiles.filter { it.isFile && isFont(it.name.orEmpty()) }) {
            copyFile(context, source, File(fonts, safeName(source)))
          }
          if (request.fontsUri.isNotBlank()) {
            val source = checkNotNull(DocumentFile.fromTreeUri(context, request.fontsUri.toUri()))
            check(source.exists() && source.canRead()) { "Font directory unavailable" }
            for (font in listChildren(context, source).filter { it.isFile && isFont(it.name.orEmpty()) }) {
              copyFile(context, font, File(fonts, safeName(font)))
            }
          }
          operationContext.ensureActive()
        }.also { acquired = it }
      }
    } catch (failure: Throwable) {
      withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { acquired?.close() }
      throw failure
    }
  }

  // DocumentFile.listFiles suppresses query failures as an empty directory.
  // A failed query must abort the snapshot instead of publishing deletions.
  private fun listChildren(context: Context, directory: DocumentFile): List<DocumentFile> {
    val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
      directory.uri, android.provider.DocumentsContract.getDocumentId(directory.uri),
    )
    return checkNotNull(context.contentResolver.query(childrenUri,
      arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)).use { cursor ->
      buildList {
        while (cursor.moveToNext()) {
          val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(directory.uri, cursor.getString(0))
          add(checkNotNull(DocumentFile.fromSingleUri(context, uri)))
        }
      }
    }
  }

  private fun safeName(file: DocumentFile): String = checkNotNull(file.name).also {
    check(it != "." && it != ".." && '/' !in it && '\\' !in it)
  }

  private fun copyFile(context: Context, source: DocumentFile, target: File) {
    check(target.name != "." && target.name != "..")
    checkNotNull(context.contentResolver.openInputStream(source.uri)).use { input ->
      target.outputStream().use { output -> input.copyTo(output) }
    }
  }
}
