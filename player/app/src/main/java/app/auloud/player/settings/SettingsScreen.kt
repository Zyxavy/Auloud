package app.auloud.player.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.auloud.player.battery.BatteryPromptDialog
import app.auloud.player.battery.BatterySettingsIntents
import app.auloud.player.battery.PrefsBatteryPromptStore

/**
 * WP8: minimal settings UI. Scope is deliberately one entry only -- the
 * battery-optimization prompt reopened on demand ("Help" entry). A full
 * settings screen is out of scope.
 *
 * The on-demand dialog shows regardless of the shown-once flag (the user
 * asked for it); the automatic first-playback dialog in the player screen
 * still appears only once. Either dialog button marks the store so the
 * auto-dialog never nags afterwards.
 *
 * DEVICE-TEST (user on the Tab E): open this entry, follow the deep link,
 * and document the real Samsung settings screen/path here.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val batteryStore = remember(appContext) {
        PrefsBatteryPromptStore.fromContext(appContext)
    }
    var showBatteryDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)
        }
        BatteryOptimizationEntry(
            onClick = { showBatteryDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
    }

    if (showBatteryDialog) {
        BatteryPromptDialog(
            onOpenSettings = {
                batteryStore.markShown()
                showBatteryDialog = false
                BatterySettingsIntents.openBatterySettings(appContext)
            },
            onDismiss = {
                batteryStore.markShown()
                showBatteryDialog = false
            }
        )
    }
}

/** Single WP8 entry: narrow scope passes only a click callback. */
@Composable
private fun BatteryOptimizationEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Battery optimization",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Keep Auloud playing with the screen off.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onClick) { Text("Battery settings help") }
    }
}
