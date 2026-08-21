package app.marlboroadvance.mpvex

import android.app.Application
import android.content.Context
import app.marlboroadvance.mpvex.database.repository.VideoMetadataCacheRepository
import app.marlboroadvance.mpvex.di.DatabaseModule
import app.marlboroadvance.mpvex.di.FileManagerModule
import app.marlboroadvance.mpvex.di.PreferencesModule
import app.marlboroadvance.mpvex.presentation.crash.CrashActivity
import app.marlboroadvance.mpvex.presentation.crash.GlobalExceptionHandler
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkCacheWarmer
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkMetadataProbe
import app.marlboroadvance.mpvex.utils.LocaleManager
import app.marlboroadvance.mpvex.utils.media.MediaLibraryEvents
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.annotation.KoinExperimentalAPI

@OptIn(KoinExperimentalAPI::class)
class App : Application() {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val metadataCache: VideoMetadataCacheRepository by inject()

  override fun attachBaseContext(newBase: Context) {
    // Keep Application.getString() aligned with the selected locale on API
    // <=32. Activities continue to use AppCompat's per-app locale handling.
    super.attachBaseContext(LocaleManager.wrapApplicationContext(newBase))
  }

  override fun onCreate() {
    super.onCreate()
    LocaleManager.applySavedLanguage(this)

    // Initialize Koin
    startKoin {
      androidContext(this@App)
      modules(
        PreferencesModule,
        DatabaseModule,
        FileManagerModule,
        app.marlboroadvance.mpvex.di.domainModule,
      )
    }

    Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(applicationContext, CrashActivity::class.java))

    FastThumbnails.initialize(this)

    // Initialize the persistent network metadata cache so it survives app restarts
    NetworkMetadataProbe.init(this)
    // Pre-warm duration/thumbnail caches for connections marked for it
    NetworkCacheWarmer.start(this)

    // Perform cache maintenance on app startup (non-blocking)
    applicationScope.launch {
      runCatching {
        metadataCache.performMaintenance()
      }
    }
    
    // Trigger media scan on app launch to detect new videos
    applicationScope.launch {
      runCatching {
        triggerMediaScanOnLaunch()
      }
    }
  }
  
  /**
   * Trigger a media scan on app launch to ensure MediaStore is up-to-date
   * This helps detect videos added by external apps while the app was closed
   */
  private fun triggerMediaScanOnLaunch() {
    try {
      val externalStorage = android.os.Environment.getExternalStorageDirectory()
      
      android.media.MediaScannerConnection.scanFile(
        this,
        arrayOf(externalStorage.absolutePath),
        null, // Let MediaScanner detect all media types
      ) { path, uri ->
        android.util.Log.d("App", "Launch media scan completed for: $path")
        // Notify the app that media library may have changed
        MediaLibraryEvents.notifyChanged()
      }
      
      android.util.Log.d("App", "Triggered media scan on app launch")
    } catch (e: Exception) {
      android.util.Log.e("App", "Failed to trigger media scan on launch", e)
    }
  }
}
