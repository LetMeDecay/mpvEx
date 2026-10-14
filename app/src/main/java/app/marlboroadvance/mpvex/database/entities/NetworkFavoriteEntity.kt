package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import app.marlboroadvance.mpvex.domain.network.NetworkConnection

/** A shortcut refers to its connection; credentials remain in that connection only. */
@Entity(
  tableName = "network_favorites",
  primaryKeys = ["connectionId", "path"],
  foreignKeys = [ForeignKey(
    entity = NetworkConnection::class,
    parentColumns = ["id"], childColumns = ["connectionId"],
    onDelete = ForeignKey.CASCADE,
  )],
)
data class NetworkFavoriteEntity(val connectionId: Long, val path: String)
