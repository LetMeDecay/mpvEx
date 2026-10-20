package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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
        val snapshots = store(context)
        // The fallback path (no external config tree) can be matched by a cheap
        // fingerprint, which skips copying and hashing the bundled fonts entirely.
        val fingerprint = fastFingerprint(context, request)
        if (fingerprint != null) snapshots.reuse(fingerprint)?.let { return@withContext it }
        snapshots.acquire { target ->
          operationContext.ensureActive()
          copyAssets(context, target)
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
        }.also { lease ->
          acquired = lease
          if (fingerprint != null && !lease.usedFallback) snapshots.remember(fingerprint, lease.directory)
        }
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

  private val fontExtensions = setOf("ttf", "otf", "ttc", "woff", "woff2")

  private fun isFont(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in fontExtensions

  /**
   * Cheap identity for the common no-external-tree configuration, which lets an
   * unchanged launch reuse its snapshot instead of copying and hashing the bundled
   * fonts (about 15MB) again. Returns null when a SAF tree is configured, because
   * its contents cannot be identified without reading them.
   */
  private fun fastFingerprint(context: Context, request: Request): String? {
    if (request.directoryUri.isNotBlank() || request.fontsUri.isNotBlank()) return null
    val digest = MessageDigest.getInstance("SHA-256")
    fun put(value: String) {
      digest.update(value.toByteArray(Charsets.UTF_8))
      digest.update(0)
    }
    put("player-config-v1")
    put(appUpdateStamp(context))
    put(request.mpvConf)
    put(request.inputConf)
    File(context.filesDir, "fonts").listFiles().orEmpty()
      .filter { it.isFile && isFont(it.name) }
      .sortedBy { it.name }
      .forEach { put("${it.name}:${it.length()}:${it.lastModified()}") }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /** Changes on every app update, which is when bundled assets can change. */
  private fun appUpdateStamp(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
  }.getOrDefault("unknown")

  private fun copyAssets(context: Context, target: File) {
    val assets = File(context.filesDir, "player-assets/${appUpdateStamp(context)}")
    for (name in listOf("cacert.pem", "subfont.ttf")) {
      linkOrCopy(cachedAsset(context, assets, name), File(target, name))
    }
  }

  /** Extracts a bundled asset once per app update, then hard-links it into snapshots. */
  private fun cachedAsset(context: Context, directory: File, name: String): File {
    val cached = File(directory, name)
    if (cached.isFile && cached.length() > 0L) return cached
    check(directory.isDirectory || directory.mkdirs())
    val temporary = File(directory, "$name.tmp")
    try {
      context.assets.open(name).use { input -> temporary.outputStream().use(input::copyTo) }
      check(temporary.renameTo(cached)) { "Unable to cache $name" }
    } finally {
      temporary.delete()
    }
    return cached
  }

  private fun linkOrCopy(source: File, target: File) {
    target.delete()
    try {
      Files.createLink(target.toPath(), source.toPath())
    } catch (_: Exception) {
      source.copyTo(target, overwrite = true)
    }
  }
}
