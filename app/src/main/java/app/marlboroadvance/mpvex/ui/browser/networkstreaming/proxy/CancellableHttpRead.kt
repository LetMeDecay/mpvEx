package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Keep cancellation attached for the entire body read, not only until headers arrive. */
internal suspend fun <T> Call.readCancellable(read: (Response) -> T): T = suspendCancellableCoroutine { waiting ->
  waiting.invokeOnCancellation { cancel() }
  enqueue(object : Callback {
    override fun onFailure(call: Call, e: IOException) { waiting.resumeWith(Result.failure(e)) }
    override fun onResponse(call: Call, response: Response) {
      val result = runCatching {
        response.use {
          if (!waiting.isActive) throw IOException("HTTP read cancelled")
          read(it)
        }
      }
      waiting.resumeWith(result)
    }
  })
}
