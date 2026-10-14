package app.marlboroadvance.mpvex.ui.player.controls.components.panels

import app.marlboroadvance.mpvex.ui.player.MpvGateway
import app.marlboroadvance.mpvex.ui.player.PlaybackTuning
import app.marlboroadvance.mpvex.ui.player.PlaybackControls
import app.marlboroadvance.mpvex.ui.player.LocalPlaybackControls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.preferences.preference.deleteAndGet
import app.marlboroadvance.mpvex.presentation.components.ExpandableCard
import app.marlboroadvance.mpvex.presentation.components.SliderItem
import app.marlboroadvance.mpvex.ui.player.controls.CARDS_MAX_WIDTH
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.toFixed
import app.marlboroadvance.mpvex.ui.player.controls.panelCardsColors
import app.marlboroadvance.mpvex.ui.theme.spacing
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject

@Composable
fun SubtitlesMiscellaneousCard(modifier: Modifier = Modifier) {
  val controls = LocalPlaybackControls.current
  val preferences = koinInject<SubtitlesPreferences>()
  var isExpanded by remember { mutableStateOf(true) }
  ExpandableCard(
    isExpanded,
    title = {
      Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        Icon(Icons.Default.Tune, null)
        Text(stringResource(R.string.player_sheets_sub_misc_card_title))
      }
    },
    onExpand = { isExpanded = !isExpanded },
    modifier.widthIn(max = CARDS_MAX_WIDTH),
    colors = panelCardsColors(),
  ) {
    ProvidePreferenceLocals {
      Column {
        var overrideAssSubs by remember {
          mutableStateOf(MpvGateway.getPropertyString("sub-ass-override") == "force")
        }
        SwitchPreference(
          overrideAssSubs,
          onValueChange = {
            overrideAssSubs = it
            preferences.overrideAssSubs.set(it)
            controls.setTuning(PlaybackTuning.SubAssOverride, if (it) "force" else "scale")
            controls.setTuning(PlaybackTuning.SecondarySubAssOverride, if (it) "force" else "scale")
          },
          { Text(stringResource(R.string.player_sheets_sub_override_ass)) },
        )
        var scaleByWindow by remember {
          mutableStateOf(MpvGateway.getPropertyString("sub-scale-by-window") == "yes")
        }
        SwitchPreference(
          scaleByWindow,
          onValueChange = {
            scaleByWindow = it
            preferences.scaleByWindow.set(it)
            val value = if (it) "yes" else "no"
            controls.setTuning(PlaybackTuning.SubScaleByWindow, value)
            controls.setTuning(PlaybackTuning.SubUseMargins, value)
          },
          { Text(stringResource(R.string.player_sheets_sub_scale_by_window)) },
          summary = { Text(stringResource(R.string.player_sheets_sub_scale_by_window_summary)) },
        )
        val subScale by MpvGateway.propFloat["sub-scale"].collectAsState()
        val subPos by MpvGateway.propInt["sub-pos"].collectAsState()
        SliderItem(
          label = stringResource(R.string.player_sheets_sub_scale),
          value = subScale ?: preferences.subScale.get(),
          valueText = (subScale ?: preferences.subScale.get()).toFixed(2).toString(),
          onChange = {
            preferences.subScale.set(it)
            controls.setTuning(PlaybackTuning.SubScale, it)
          },
          max = 5f,
          icon = {
            Icon(
              Icons.Default.FormatSize,
              null,
            )
          },
        )
        SliderItem(
          label = stringResource(R.string.player_sheets_sub_position),
          value = subPos ?: preferences.subPos.get(),
          valueText = (subPos ?: preferences.subPos.get()).toString(),
          onChange = {
            preferences.subPos.set(it)
            controls.setTuning(PlaybackTuning.SubPos, it)
          },
          max = 150,
          icon = {
            Icon(
              Icons.Default.AlignVerticalCenter,
              null,
            )
          },
        )
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(end = MaterialTheme.spacing.medium, bottom = MaterialTheme.spacing.medium),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            onClick = {
              preferences.subPos.deleteAndGet().let {
                controls.setTuning(PlaybackTuning.SubPos, it)
              }
              preferences.subScale.deleteAndGet().let {
                controls.setTuning(PlaybackTuning.SubScale, it)
              }
              val defaultOverride = preferences.overrideAssSubs.deleteAndGet()
              overrideAssSubs = defaultOverride
              controls.setTuning(PlaybackTuning.SubAssOverride, if (defaultOverride) "force" else "scale")
              controls.setTuning(PlaybackTuning.SecondarySubAssOverride, if (defaultOverride) "force" else "scale")
              val defaultScaleByWindow = preferences.scaleByWindow.deleteAndGet()
              scaleByWindow = defaultScaleByWindow
              val scaleValue = if (defaultScaleByWindow) "yes" else "no"
              controls.setTuning(PlaybackTuning.SubScaleByWindow, scaleValue)
              controls.setTuning(PlaybackTuning.SubUseMargins, scaleValue)
            },
          ) {
            Row {
              Icon(Icons.Default.EditOff, null)
              Text(stringResource(R.string.generic_reset))
            }
          }
        }
      }
    }
  }
}
