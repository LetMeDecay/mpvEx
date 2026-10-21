package app.marlboroadvance.mpvex.ui.browser

import android.content.Context

/**
 * Durable "last location" used to restore the browsing context after the process
 * is killed in the background (LMK/UMMS) or after a cold start.
 *
 * The Compose navigation back stack is only restored when the system hands the
 * saved instance state back; a killed process frequently starts with a fresh
 * stack, which used to drop the user on the home tab. Persisting the tab and the
 * open network folder here keeps that context across those restarts.
 */
object SessionStateStore {
  private const val FILE = "session_state"
  private const val KEY_TAB = "last_tab"
  private const val KEY_CONNECTION_ID = "network_connection_id"
  private const val KEY_CONNECTION_NAME = "network_connection_name"
  private const val KEY_PATH = "network_path"

  data class NetworkLocation(
    val connectionId: Long,
    val connectionName: String,
    val path: String,
  )

  private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  fun lastTab(context: Context): Int = prefs(context).getInt(KEY_TAB, 0)

  fun saveTab(context: Context, tab: Int) {
    prefs(context).edit().putInt(KEY_TAB, tab).apply()
  }

  fun networkLocation(context: Context): NetworkLocation? {
    val prefs = prefs(context)
    val connectionId = prefs.getLong(KEY_CONNECTION_ID, -1L)
    val path = prefs.getString(KEY_PATH, null) ?: return null
    if (connectionId < 0) return null
    return NetworkLocation(
      connectionId = connectionId,
      connectionName = prefs.getString(KEY_CONNECTION_NAME, "").orEmpty(),
      path = path,
    )
  }

  fun saveNetwork(context: Context, connectionId: Long, connectionName: String, path: String) {
    prefs(context).edit()
      .putLong(KEY_CONNECTION_ID, connectionId)
      .putString(KEY_CONNECTION_NAME, connectionName)
      .putString(KEY_PATH, path)
      .apply()
  }

  fun clearNetwork(context: Context) {
    prefs(context).edit()
      .remove(KEY_CONNECTION_ID)
      .remove(KEY_CONNECTION_NAME)
      .remove(KEY_PATH)
      .apply()
  }
}
