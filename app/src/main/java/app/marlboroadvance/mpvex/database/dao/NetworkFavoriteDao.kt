package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import app.marlboroadvance.mpvex.database.entities.NetworkFavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkFavoriteDao {
  @Query("SELECT * FROM network_favorites ORDER BY connectionId, path")
  fun observeAll(): Flow<List<NetworkFavoriteEntity>>

  @Query("SELECT EXISTS(SELECT 1 FROM network_favorites WHERE connectionId = :connectionId AND path = :path)")
  fun observeSaved(connectionId: Long, path: String): Flow<Boolean>

  @Query("SELECT EXISTS(SELECT 1 FROM network_favorites WHERE connectionId = :connectionId AND path = :path)")
  suspend fun isSaved(connectionId: Long, path: String): Boolean

  // A directory screen can outlive deletion of its connection. Do not resurrect it.
  @Query("INSERT OR IGNORE INTO network_favorites(connectionId, path) SELECT :connectionId, :path WHERE EXISTS(SELECT 1 FROM network_connections WHERE id = :connectionId)")
  suspend fun add(connectionId: Long, path: String)

  @Query("DELETE FROM network_favorites WHERE connectionId = :connectionId AND path = :path")
  suspend fun remove(connectionId: Long, path: String)

  @Transaction
  suspend fun toggle(connectionId: Long, path: String) {
    if (isSaved(connectionId, path)) remove(connectionId, path) else add(connectionId, path)
  }
}
