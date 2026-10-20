package app.marlboroadvance.mpvex.repository

import app.marlboroadvance.mpvex.database.dao.NetworkConnectionDao
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClient
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NetworkRepositoryTest {
  private val connection = NetworkConnection(1, "test", NetworkProtocol.FTP, "localhost", 21)

  @Test fun concurrentConnectAndBrowseShareOneClient() = runBlocking {
    withTimeout(5000) {
      val gate = CompletableDeferred<Unit>()
      val client = FakeClient(gate)
      var creations = 0
      val repository = NetworkRepository(FakeDao(connection)) { creations++; client }
      val first = async(start = CoroutineStart.UNDISPATCHED) { repository.connect(connection) }
      val second = async(start = CoroutineStart.UNDISPATCHED) { repository.listFiles(connection, "/") }
      assertEquals(1, creations)
      gate.complete(Unit)
      assertTrue(first.await().isSuccess)
      assertTrue(second.await().isSuccess)
      assertEquals(1, creations)
      assertEquals(1, client.listings)
    }
  }

  @Test fun updateWaitsForListingAndNextConnectUsesNewSettings() = runBlocking {
    withTimeout(5000) {
      val client = FakeClient()
      val listingGate = CompletableDeferred<Unit>()
      client.listingGate = listingGate
      val created = mutableListOf<NetworkConnection>()
      val repository = NetworkRepository(FakeDao(connection)) { created += it; client }
      val listing = async(start = CoroutineStart.UNDISPATCHED) { repository.listFiles(connection, "/") }
      val updated = connection.copy(password = "changed")
      val update = async(start = CoroutineStart.UNDISPATCHED) { repository.updateConnection(updated) }
      assertEquals(0, client.disconnects)
      listingGate.complete(Unit)
      listing.await()
      update.await()
      assertEquals(1, client.disconnects)
      repository.connect(connection).getOrThrow()
      assertEquals(updated.password, created.last().password)
    }
  }

  @Test fun cancelledConnectClosesUnpublishedClient() = runBlocking {
    withTimeout(5000) {
      val client = FakeClient(CompletableDeferred())
      val repository = NetworkRepository(FakeDao(connection)) { client }
      val pending = async(start = CoroutineStart.UNDISPATCHED) { repository.connect(connection) }
      pending.cancelAndJoin()
      assertEquals(1, client.disconnects)
      assertFalse(repository.isConnected(connection.id))
      assertFalse(repository.connectionStatuses.value.getValue(connection.id).isConnecting)
    }
  }

  @Test fun deletedConnectionCannotBeReopenedFromOldSnapshot() = runBlocking {
    var creations = 0
    val repository = NetworkRepository(FakeDao(connection)) { creations++; FakeClient() }
    repository.connect(connection).getOrThrow()
    repository.deleteConnection(connection)
    assertTrue(repository.connect(connection).isFailure)
    assertTrue(repository.listFiles(connection, "/").isFailure)
    assertEquals(1, creations)
    assertFalse(repository.connectionStatuses.value.containsKey(connection.id))
  }

  @Test fun differentConnectionsCanConnectIndependently() = runBlocking {
    withTimeout(5000) {
      val other = connection.copy(id = 2)
      val gate = CompletableDeferred<Unit>()
      val dao = FakeDao(connection)
      dao.insert(other)
      val repository = NetworkRepository(dao) { FakeClient(if (it.id == 1L) gate else null) }
      val pending = async(start = CoroutineStart.UNDISPATCHED) { repository.connect(connection) }
      repository.connect(other).getOrThrow()
      assertTrue(repository.connectionStatuses.value.getValue(other.id).isConnected)
      assertTrue(repository.connectionStatuses.value.getValue(connection.id).isConnecting)
      gate.complete(Unit)
      pending.await().getOrThrow()
      assertTrue(repository.connectionStatuses.value.values.all { it.isConnected })
    }
  }

  private class FakeClient(private val connectGate: CompletableDeferred<Unit>? = null) : NetworkClient {
    var connected = false
    var disconnects = 0
    var listings = 0
    var listingGate: CompletableDeferred<Unit>? = null
    override suspend fun connect(): Result<Unit> {
      connectGate?.await()
      connected = true
      return Result.success(Unit)
    }
    override suspend fun disconnect() { disconnects++; connected = false }
    override fun isConnected() = connected
    override suspend fun listFiles(path: String): Result<List<NetworkFile>> {
      listings++
      listingGate?.await()
      return Result.success(emptyList())
    }
    override suspend fun getFileStream(path: String): Result<InputStream> = error("unused")
  }

  private class FakeDao(connection: NetworkConnection) : NetworkConnectionDao {
    private val rows = mutableMapOf(connection.id to connection)
    override fun getAllConnections() = flowOf(rows.values.toList())
    override fun getHomeConnections() = getAllConnections()
    override suspend fun getConnectionById(id: Long) = rows[id]
    override suspend fun getAutoConnectConnections() = rows.values.toList()
    override suspend fun getPreloadCacheConnections() = rows.values.toList()
    override suspend fun getAllConnectionsList() = rows.values.toList()
    override suspend fun insert(connection: NetworkConnection): Long {
      rows[connection.id] = connection
      return connection.id
    }
    override suspend fun insertAll(connections: List<NetworkConnection>) { connections.forEach { insert(it) } }
    override suspend fun update(connection: NetworkConnection) { rows[connection.id] = connection }
    override suspend fun delete(connection: NetworkConnection) { rows.remove(connection.id) }
    override suspend fun deleteById(id: Long) { rows.remove(id) }
    override suspend fun updateLastConnected(id: Long, timestamp: Long) {}
  }
}
