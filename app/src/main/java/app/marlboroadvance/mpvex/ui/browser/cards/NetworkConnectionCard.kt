package app.marlboroadvance.mpvex.ui.browser.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.presentation.components.ExpandableCard
import app.marlboroadvance.mpvex.presentation.components.OutlinedNumericChooser

/**
 * Pre-warm setting fields editable per connection.
 */
enum class PreloadSetting {
  Depth,
  PerDir,
  Total,
  Threads,
}

@Composable
fun NetworkConnectionCard(
  connection: NetworkConnection,
  onConnect: (NetworkConnection) -> Unit,
  onDisconnect: (NetworkConnection) -> Unit,
  onEdit: (NetworkConnection) -> Unit,
  onDelete: (NetworkConnection) -> Unit,
  onBrowse: (NetworkConnection) -> Unit,
  onAutoConnectChange: (NetworkConnection, Boolean) -> Unit,
  onDisplayInHomeChange: (NetworkConnection, Boolean) -> Unit = { _, _ -> },
  onPreloadCacheChange: (NetworkConnection, Boolean) -> Unit = { _, _ -> },
  onPreloadConfigChange: (NetworkConnection, PreloadSetting, Int) -> Unit = { _, _, _ -> },
  onClearPreviewCache: (NetworkConnection) -> Unit = {},
  modifier: Modifier = Modifier,
  isConnected: Boolean = false,
  isConnecting: Boolean = false,
  error: String? = null,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
    ) {
      // Header with name and actions
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = connection.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "${connection.protocol.displayName} • ${connection.host}:${connection.port}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Row {
          IconButton(
            onClick = { onClearPreviewCache(connection) },
            modifier = Modifier.padding(end = 4.dp),
          ) {
            Icon(
              Icons.Filled.DeleteSweep,
              contentDescription = "Clear preview cache",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = { onEdit(connection) }) {
            Icon(
              Icons.Filled.Edit,
              contentDescription = "Edit",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = { onDelete(connection) }) {
            Icon(
              Icons.Filled.Delete,
              contentDescription = "Delete",
              tint = MaterialTheme.colorScheme.error,
            )
          }
        }
      }

      // Connection details
      if (connection.path != "/") {
        Text(
          text = "Path: ${connection.path}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      if (connection.username.isNotEmpty() && !connection.isAnonymous) {
        Text(
          text = "User: ${connection.username}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      // Error message
      if (error != null) {
        Text(
          text = "Error: $error",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 8.dp),
        )
      }

      // Auto-connect checkbox
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(
          checked = connection.autoConnect,
          onCheckedChange = { checked ->
            onAutoConnectChange(connection, checked)
          },
        )
        Text(
          text = "Connect automatically on app launch",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Display on Home checkbox
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(
          checked = connection.displayInHome,
          onCheckedChange = { checked ->
            onDisplayInHomeChange(connection, checked)
          },
        )
        Text(
          text = "Display on Home",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Preload cache checkbox
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(
          checked = connection.preloadCache,
          onCheckedChange = { checked ->
            onPreloadCacheChange(connection, checked)
          },
        )
        Text(
          text = "Refresh cache on app launch",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Preload settings (collapsible, only shown when preload is enabled)
      if (connection.preloadCache) {
        var preloadExpanded by remember { mutableStateOf(false) }
        ExpandableCard(
          isExpanded = preloadExpanded,
          title = {
            Text(
              text = "Preload Settings",
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 4.dp),
            )
          },
          onExpand = { preloadExpanded = it },
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedNumericChooser(
              label = { Text("Max depth") },
              value = connection.preloadDepth,
              onChange = { onPreloadConfigChange(connection, PreloadSetting.Depth, it) },
              max = 100,
              step = 1,
              min = 1,
            )
            OutlinedNumericChooser(
              label = { Text("Per directory") },
              value = connection.preloadPerDir,
              onChange = { onPreloadConfigChange(connection, PreloadSetting.PerDir, it) },
              max = 1000,
              step = 1,
              min = 0,
            )
            OutlinedNumericChooser(
              label = { Text("Total") },
              value = connection.preloadTotal,
              onChange = { onPreloadConfigChange(connection, PreloadSetting.Total, it) },
              max = 100000,
              step = 100,
              min = 0,
            )
            OutlinedNumericChooser(
              label = { Text("Threads") },
              value = connection.preloadThreads,
              onChange = { onPreloadConfigChange(connection, PreloadSetting.Threads, it) },
              max = 20,
              step = 1,
              min = 1,
            )
          }
        }
      }

      // Connection button
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
      ) {
        when {
          isConnecting -> {
            FilledTonalButton(
              onClick = { },
              enabled = false,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                Text(
                  "Connecting",
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
              }
            }
          }

          isConnected -> {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              FilledTonalButton(
                onClick = { onBrowse(connection) },
                colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
              ) {
                Icon(
                  Icons.Filled.FolderOpen,
                  contentDescription = null,
                  modifier = Modifier.padding(end = 8.dp),
                )
                Text("Browse")
              }

              FilledTonalButton(
                onClick = { onDisconnect(connection) },
                colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = MaterialTheme.colorScheme.secondaryContainer,
                  contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
              ) {
                Icon(
                  Icons.Filled.LinkOff,
                  contentDescription = null,
                  modifier = Modifier.padding(end = 8.dp),
                )
                Text("Disconnect")
              }
            }
          }

          else -> {
            FilledTonalButton(
              onClick = { onConnect(connection) },
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
            ) {
              Icon(
                Icons.Filled.Link,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
              )
              Text("Connect")
            }
          }
        }
      }
    }
  }
}
