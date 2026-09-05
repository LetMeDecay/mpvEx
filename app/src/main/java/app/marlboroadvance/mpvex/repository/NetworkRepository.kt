package app.marlboroadvance.mpvex.repository

import app.marlboroadvance.mpvex.database.dao.NetworkConnectionDao
import app.marlboroadvance.mpvex.domain.network.ConnectionStatus
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClient
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClientFactory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Manages saved connections and serializes operations on each protocol client. */
class NetworkRepository(
  private val dao: NetworkConnectionDao,
  private val createClient: (NetworkConnection) -> NetworkClient = NetworkClientFactory::createClient,
) {
  private val activeClients = ConcurrentHashMap<Long, NetworkClient>()
  // Keep the lock after disconnect/delete: a suspended caller may still hold it.
  private val connectionLocks = ConcurrentHashMap<Long, Mutex>()
  private fun connectionLock(id: Long): Mutex = connectionLocks.computeIfAbsent(id) { Mutex() }

  private val _connectionStatuses = MutableStateFlow<Map<Long, ConnectionStatus>>(emptyMap())
  val connectionStatuses: StateFlow<Map<Long, ConnectionStatus>> = _connectionStatuses.asStateFlow()

  fun getAllConnections(): Flow<List<NetworkConnection>> = dao.getAllConnections()
  fun getHomeConnections(): Flow<List<NetworkConnection>> = dao.getHomeConnections()
  suspend fun getPreloadCacheConnections(): List<NetworkConnection> = dao.getPreloadCacheConnections()
  suspend fun getAutoConnectConnections(): List<NetworkConnection> = dao.getAutoConnectConnections()
  suspend fun getConnectionById(id: Long): NetworkConnection? = dao.getConnectionById(id)
  suspend fun addConnection(connection: NetworkConnection): Long = dao.insert(connection)

  suspend fun updateConnection(connection: NetworkConnection) {
    connectionLock(connection.id).withLock {
      dao.update(connection)
      closeClient(activeClients.remove(connection.id))
      updateConnectionStatus(connection.id, ConnectionStatus(connection.id))
    }
  }

  suspend fun deleteConnection(connection: NetworkConnection) {
    connectionLock(connection.id).withLock {
      dao.delete(connection)
      closeClient(activeClients.remove(connection.id))
      _connectionStatuses.update { it - connection.id }
    }
  }

  suspend fun connect(connection: NetworkConnection): Result<Unit> =
    connectionLock(connection.id).withLock {
      try {
        val latest = dao.getConnectionById(connection.id)
          ?: return@withLock Result.failure(IllegalStateException("Connection not found"))
        getOrConnectLocked(latest)
        Result.success(Unit)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  /** Called only while holding this connection's mutex. */
  private suspend fun getOrConnectLocked(connection: NetworkConnection): NetworkClient {
    activeClients[connection.id]?.takeIf { it.isConnected() }?.let { return it }
    closeClient(activeClients.remove(connection.id))
    updateConnectionStatus(connection.id, ConnectionStatus(connection.id, isConnecting = true))
    var client: NetworkClient? = null
    try {
      client = createClient(connection)
      client.connect().getOrThrow()
      currentCoroutineContext().ensureActive()
      dao.updateLastConnected(connection.id, System.currentTimeMillis())
      activeClients[connection.id] = client
      updateConnectionStatus(connection.id, ConnectionStatus(connection.id, isConnected = true))
      return client
    } catch (e: Exception) {
      closeClient(client)
      updateConnectionStatus(
        connection.id,
        ConnectionStatus(connection.id, error = e.message.takeUnless { e is CancellationException }),
      )
      throw e
    }
  }

  suspend fun disconnect(connection: NetworkConnection): Result<Unit> =
    connectionLock(connection.id).withLock {
      closeClient(activeClients.remove(connection.id))
      updateConnectionStatus(connection.id, ConnectionStatus(connection.id))
      Result.success(Unit)
    }

  suspend fun listFiles(connection: NetworkConnection, path: String): Result<List<NetworkFile>> =
    connectionLock(connection.id).withLock {
      try {
        // Never resurrect a deleted connection from a stale browser/preload snapshot.
        val latest = dao.getConnectionById(connection.id)
          ?: return@withLock Result.failure(IllegalStateException("Connection not found"))
        val result = getOrConnectLocked(latest).listFiles(path)
        currentCoroutineContext().ensureActive()
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        result
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  fun getActiveClient(connectionId: Long): NetworkClient? = activeClients[connectionId]
  fun isConnected(connectionId: Long): Boolean = activeClients[connectionId]?.isConnected() == true

  suspend fun disconnectAll() {
    // Include connections whose client creation is still in progress.
    connectionLocks.keys.toList().forEach { id ->
      connectionLock(id).withLock {
        closeClient(activeClients.remove(id))
        _connectionStatuses.update { it - id }
      }
    }
  }

  private suspend fun closeClient(client: NetworkClient?) {
    if (client == null) return
    withContext(NonCancellable) {
      runCatching { client.disconnect() }
    }
  }

  private fun updateConnectionStatus(connectionId: Long, status: ConnectionStatus) {
    _connectionStatuses.update { it + (connectionId to status) }
  }
}
