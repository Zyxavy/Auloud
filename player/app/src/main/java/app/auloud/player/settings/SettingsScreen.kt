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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auloud.player.BuildConfig
import app.auloud.player.battery.BatteryPromptDialog
import app.auloud.player.battery.BatterySettingsIntents
import app.auloud.player.battery.PrefsBatteryPromptStore
import app.auloud.player.reader.ReaderFontSize
import app.auloud.player.storage.WatchFolder
import app.auloud.player.storage.WatchFolders

/**
 * WP8 minimal settings UI plus the WP3/WP5 refinement watch-folder list.
 *
 * Battery entry is unchanged (one entry, on-demand dialog). Watch folders:
 * the default shared-internal `/Auloud` plus user-picked folders (system
 * folder picker, SAF persistable grants). Adding launches the picker via
 * [onAddFolder]; removing drops the entry and rescans (host responsibility).
 * State stays hoisted: [folders] + callbacks in, no store access here, so
 * rows recompose narrowly on the slow Tab E.
 *
 * DEVICE-TEST (user on the Tab E): add internal `Auloud/`, the SD-card
 * `Auloud/`, and a nested folder; remove each; confirm rescan aggregates
 * books across the rest and permission prompts behave.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    folders: List<WatchFolder> = emptyList(),
    onAddFolder: () -> Unit = {},
    onRemoveFolder: (WatchFolder) -> Unit = {},
    onOpenSpike: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val batteryStore = remember(appContext) {
        PrefsBatteryPromptStore.fromContext(appContext)
    }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    // RA10: reader settings live in the shared prefs store (read once per
    // Settings visit; the reader re-reads on open, so changes apply then).
    val readerStore = remember(appContext) {
        PrefsReaderModeStore.fromContext(appContext)
    }
    var fontSize by remember { mutableStateOf(readerStore.fontSize()) }
    var keepScreenOn by remember { mutableStateOf(readerStore.keepScreenOn()) }

    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)
        }
        WatchFoldersSection(
            folders = folders,
            onAddFolder = onAddFolder,
            onRemoveFolder = onRemoveFolder,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        BatteryOptimizationEntry(
            onClick = { showBatteryDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        ReaderSettingsSection(
            fontSize = fontSize,
            onFontSize = {
                readerStore.setFontSize(it)
                fontSize = it
            },
            keepScreenOn = keepScreenOn,
            onKeepScreenOn = {
                readerStore.setKeepScreenOn(it)
                keepScreenOn = it
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        // RA0 throwaway: debug builds only, deleted with the spike screen.
        // CP8: gated through isReaderPreviewAvailable so the entry (and the
        // screen behind it) is unreachable in release builds.
        if (isReaderPreviewAvailable(BuildConfig.DEBUG)) {
            SpikeEntry(
                onClick = onOpenSpike,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
        }
        LicensesEntry(
            onClick = { showLicenses = true },
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

/** Watch-folder list: one narrow row per folder plus an add button. */
@Composable
private fun WatchFoldersSection(
    folders: List<WatchFolder>,
    onAddFolder: () -> Unit,
    onRemoveFolder: (WatchFolder) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Book folders",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Auloud scans every folder below for book bundles.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        if (folders.isEmpty()) {
            Text(
                text = "No folders yet. Add one to get started.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
        } else {
            for (folder in folders) {
                WatchFolderRow(
                    folder = folder,
                    onRemove = { onRemoveFolder(folder) }
                )
            }
        }
        Button(onClick = onAddFolder) { Text("Add folder") }
    }
}

/** Single stable row: display name, decoded detail, and remove. */
@Composable
private fun WatchFolderRow(
    folder: WatchFolder,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = WatchFolders.displayName(folder),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Decoded detail (`/storage/...` path or `primary:Auloud/...`
            // grant label): raw `content://` URIs and `<tree>|<rel>` tokens
            // must never render here.
            Text(
                text = when (folder) {
                    is WatchFolder.FilePath -> folder.path
                    is WatchFolder.TreeUri ->
                        WatchFolders.treeDocumentLabel(folder.uriString)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

/** RA4 temporary entry: previews the real reader screen (debug only). */
@Composable
private fun SpikeEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Reader preview (RA4)",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Temporary look-check of the real reader. Wired to playback in RA7.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onClick) { Text("Open preview") }
    }
}

/** RA10: reader font size + keep-screen-on (persisted global settings). */
@Composable
private fun ReaderSettingsSection(
    fontSize: ReaderFontSize,
    onFontSize: (ReaderFontSize) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Reading",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Font size",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FontSizeButton("Small", ReaderFontSize.Small, fontSize, onFontSize)
            FontSizeButton("Medium", ReaderFontSize.Medium, fontSize, onFontSize)
            FontSizeButton("Large", ReaderFontSize.Large, fontSize, onFontSize)
            FontSizeButton("Huge", ReaderFontSize.ExtraLarge, fontSize, onFontSize)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Keep screen on while reading",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Applies in Read and Read + listen.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = keepScreenOn, onCheckedChange = onKeepScreenOn)
        }
    }
}

@Composable
private fun FontSizeButton(
    label: String,
    size: ReaderFontSize,
    current: ReaderFontSize,
    onFontSize: (ReaderFontSize) -> Unit
) {
    if (size == current) {
        Button(onClick = { onFontSize(size) }) { Text(label) }
    } else {
        TextButton(onClick = { onFontSize(size) }) { Text(label) }
    }
}
/** CP8: in-app licenses screen entry (ships in release). */
@Composable
private fun LicensesEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Licenses",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Open-source licenses for the libraries and voices Auloud uses.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onClick) { Text("View licenses") }
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
