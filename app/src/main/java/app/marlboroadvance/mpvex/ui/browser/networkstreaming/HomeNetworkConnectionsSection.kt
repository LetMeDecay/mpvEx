package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.repository.NetworkRepository
import org.koin.compose.koinInject

/**
 * Section shown on the Home screen that lists network connections marked with "Display on Home".
 * Tapping a connection opens the network browser for that connection (auto-connects on demand).
 */
@Composable
fun HomeNetworkConnectionsSection(
  onConnectionClick: (NetworkConnection) -> Unit,
  modifier: Modifier = Modifier,
) {
  val repository = koinInject<NetworkRepository>()
  val connections by repository.getHomeConnections().collectAsState(initial = emptyList())
  val statuses by repository.connectionStatuses.collectAsState()

  if (connections.isEmpty()) return

  Column(modifier = modifier) {
    Text(
      text = stringResource(R.string.network),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    connections.forEach { connection ->
      val status = statuses[connection.id]
      HomeNetworkConnectionCard(
        connection = connection,
        isConnected = status?.isConnected == true,
        isConnecting = status?.isConnecting == true,
        error = status?.error,
        onClick = { onConnectionClick(connection) },
        modifier = Modifier
          .padding(horizontal = 8.dp, vertical = 4.dp)
          .fillMaxWidth(),
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
  }
}

@Composable
private fun HomeNetworkConnectionCard(
  connection: NetworkConnection,
  isConnected: Boolean,
  isConnecting: Boolean,
  error: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.clickable(onClick = onClick),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .padding(4.dp),
        contentAlignment = Alignment.Center,
      ) {
        if (isConnecting) {
          CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
          )
        } else {
          Icon(
            imageVector = Icons.Filled.Language,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = connection.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${connection.protocol.displayName} • ${connection.host}:${connection.port}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (error != null) {
          Text(
            text = stringResource(R.string.network_error, error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Text(
        text = when {
          isConnecting -> stringResource(R.string.network_connecting)
          isConnected -> stringResource(R.string.network_connected)
          else -> stringResource(R.string.network_open)
        },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (isConnected) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      )
    }
  }
}
