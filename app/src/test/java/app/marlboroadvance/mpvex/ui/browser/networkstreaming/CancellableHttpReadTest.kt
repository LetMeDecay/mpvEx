package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.readCancellable
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class CancellableHttpReadTest {
  @Test fun cancellationAfterHeadersCancelsTheActualHttpCall() = runBlocking {
    withTimeout(10000) {
      val release = CountDownLatch(1)
      val headers = CompletableDeferred<Unit>()
      val callbackFinished = CompletableDeferred<Unit>()
      val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = newFixedLengthResponse(
          Response.Status.OK, "application/octet-stream", object : InputStream() {
            override fun read(): Int { release.await(5, TimeUnit.SECONDS); return -1 }
          }, 1024,
        )
      }
      server.start()
      val client = OkHttpClient()
      val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/test").build())
      try {
        val reader = async {
          call.readCancellable { response ->
            headers.complete(Unit)
            try { response.body!!.byteStream().read() } finally { callbackFinished.complete(Unit) }
          }
        }
        headers.await()
        reader.cancelAndJoin()
        assertTrue(call.isCanceled())
        callbackFinished.await()
      } finally {
        release.countDown()
        server.stop()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
      }
    }
  }
}
