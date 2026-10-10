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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auloud.player.BuildConfig
import app.auloud.player.battery.BatteryPromptDialog
import app.auloud.player.battery.BatterySettingsIntents
import app.auloud.player.battery.PrefsBatteryPromptStore
import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import app.auloud.player.bundle.Manifest
import app.auloud.player.reader.ReaderFontSize
import app.auloud.player.render.AndroidAudioEncoder
import app.auloud.player.render.BeepSelfCheck
import app.auloud.player.render.DebugRenderEngines
import app.auloud.player.storage.WatchFolder
import app.auloud.player.storage.WatchFolders
import app.auloud.player.storage.BooksRootResolver
import app.auloud.player.tts.AndroidSystemTtsDriver
import app.auloud.player.tts.AudioTrackAudioPlayer
import app.auloud.player.tts.EngineRegistry
import app.auloud.player.tts.ModelPack
import app.auloud.player.tts.ModelPacks
import app.auloud.player.tts.PrefsTtsStore
import app.auloud.player.tts.SherpaKittenEngine
import app.auloud.player.tts.SherpaPiperEngine
import app.auloud.player.tts.SystemTtsAdapter
import app.auloud.player.tts.VoiceAuditionScreen
import app.auloud.player.tts.VoiceAuditionViewModel
import app.auloud.player.tts.detectKittenPack
import app.auloud.player.tts.kittenEngineOrNull
import java.io.File

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
    var showVoices by remember { mutableStateOf(false) }
    // RA10: reader settings live in the shared prefs store (read once per
    // Settings visit; the reader re-reads on open, so changes apply then).
    val readerStore = remember(appContext) {
        PrefsReaderModeStore.fromContext(appContext)
    }
    var fontSize by remember { mutableStateOf(readerStore.fontSize()) }
    var keepScreenOn by remember { mutableStateOf(readerStore.keepScreenOn()) }
    var dialogueMarking by remember { mutableStateOf(readerStore.dialogueMarking()) }

    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
        return
    }
    if (showVoices) {
        VoiceAuditionHost(onBack = { showVoices = false })
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
            dialogueMarking = dialogueMarking,
            onDialogueMarking = {
                readerStore.setDialogueMarking(it)
                dialogueMarking = it
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        VoiceSettingsEntry(
            onClick = { showVoices = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        // KT2: problem rows ride the same scan (one pass, shared roots).
        val modelRoots = remember(appContext) { modelPackRoots(appContext) }
        ModelPacksSection(
            packs = remember(modelRoots) { ModelPacks.scan(modelRoots) },
            problems = remember(modelRoots) { ModelPacks.scanProblems(modelRoots) },
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
    dialogueMarking: Boolean,
    onDialogueMarking: (Boolean) -> Unit,
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
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Mark dialogue in color",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Dialogue sentences draw in the accent color.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = dialogueMarking, onCheckedChange = onDialogueMarking)
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

/** PW7a: sideloaded model packs (files only; engine binding is PW7b). */
@Composable
private fun ModelPacksSection(
    packs: List<ModelPack>,
    problems: List<ModelPacks.PackProblem> = emptyList(),
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Model packs",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        if (packs.isEmpty() && problems.isEmpty()) {
            Text(
                text = "No voice models found. Copy a pack folder " +
                    "(.onnx files) into /Auloud/models/ here or " +
                    "Auloud/models/ on the SD card with a file manager.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            packs.forEach { pack ->
                // KT2: Kitten folders read as voice counts (their `.onnx`
                // stem is the shared model file, not a voice); other packs
                // keep the stem listing.
                val kitten = detectKittenPack(File(pack.dirPath))
                Text(
                    text = pack.label,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = if (kitten != null) {
                        "${kitten.speakerCount} Kitten voice(s), "
                    } else {
                        "${pack.voices.size} voice(s), "
                    } + "%.1f MB".format(pack.bytesTotal / 1048576.0),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = if (kitten != null) {
                        "kitten:0..${kitten.speakerCount - 1} (user-supplied pack)"
                    } else {
                        pack.voices.joinToString(", ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
            }
            // KT2: incomplete attempts with the missing piece, so a bad
            // copy reads as a fixable row instead of silence.
            problems.forEach { problem ->
                Text(
                    text = problem.label,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Incomplete pack: ${problem.reason}. " +
                        "Fix the folder with a file manager.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

private fun modelPackRoots(appContext: android.content.Context): List<java.io.File> {
    return try {
        val internal = java.io.File(BooksRootResolver.defaultBooksRoot(appContext))
        val removable = try {
            BooksRootResolver.findRemovableRoot(appContext)
        } catch (_: Exception) {
            null
        }
        ModelPacks.roots(internal, removable)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun scanModelPacks(appContext: android.content.Context): List<ModelPack> {
    return try {
        ModelPacks.scan(modelPackRoots(appContext))
    } catch (_: Exception) {
        emptyList()
    }
}
/** PW8: narrator/dialogue voices entry (pushes the audition screen). */
@Composable
private fun VoiceSettingsEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Voices",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Narrator and dialogue voices for on-device reading.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onClick) { Text("Choose voices") }
    }
}

/**
 * PW8: audition host — builds the TTS graph by hand (P3) and tears it
 * down on dispose. The driver starts the system TTS service async;
 * [VoiceAuditionViewModel.refresh] picks up voices when it reports
 * ready (the screen shows the empty state until then).
 */
@Composable
private fun VoiceAuditionHost(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val sherpaHolder = remember(appContext) { arrayOfNulls<SherpaPiperEngine>(1) }
    val kittenHolder = remember(appContext) { arrayOfNulls<SherpaKittenEngine>(1) }
    val viewModel = remember(appContext) {
        val scratch = File(appContext.cacheDir, "tts-audition")
        val driver = AndroidSystemTtsDriver(appContext)
        val adapter = SystemTtsAdapter(driver, scratch)
        // PW7b: sherpa Piper joins the registry when complete packs are
        // present (constructor never loads models; voices stay lazy).
        // No packs, no engine — System tier alone, exactly as before.
        val packs = scanModelPacks(appContext)
        val sherpa = SherpaPiperEngine(packs).takeIf { it.voices().isNotEmpty() }
        sherpaHolder[0] = sherpa
        // KT2: Kitten joins the registry on the same rule as Piper.
        val kitten = packs.kittenEngineOrNull()
        kittenHolder[0] = kitten
        // RN10: the beep engine joins the registry in debug builds only
        // (null in release, so the release list is exactly what it was
        // before RN10). The voice-lab beep card renders through it.
        val registry = EngineRegistry(
            listOfNotNull(
                adapter,
                sherpa,
                kitten,
                DebugRenderEngines.beepEngineIfDebug(BuildConfig.DEBUG)
            )
        )
        val store = PrefsTtsStore.fromContext(appContext)
        VoiceAuditionViewModel(registry, store, AudioTrackAudioPlayer())
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.clear()
            sherpaHolder[0]?.release()
            sherpaHolder[0] = null
            kittenHolder[0]?.release()
            kittenHolder[0] = null
        }
    }
    val state by viewModel.state.collectAsState()
    // RN10: minimal beep self-check trigger (debug only; the card hides
    // in release). Runs the real RN4+RN5+RN6 chain on IO and reports the
    // bundle path plus validation, for the RN11 offset measurement.
    var beepStatus by remember { mutableStateOf<String?>(null) }
    var beepRunning by remember { mutableStateOf(false) }
    val beepScope = rememberCoroutineScope()
    // Re-poll once the async TTS init lands (cheap: registry + prefs read).
    LaunchedEffect(Unit) {
        delay(2000)
        viewModel.refresh()
    }
    VoiceAuditionScreen(
        state = state,
        onBack = onBack,
        onSelectEngine = viewModel::selectEngine,
        onSelectVoice = viewModel::selectVoice,
        onSetSpeed = viewModel::setSpeed,
        onPreview = viewModel::preview,
        onStop = viewModel::stop,
        onCalibrateLevels = viewModel::calibrateLevels,
        modifier = modifier,
        beepCheckAvailable = isBeepSelfCheckAvailable(BuildConfig.DEBUG),
        beepStatus = beepStatus,
        onRunBeepCheck = {
            if (!beepRunning) {
                beepRunning = true
                beepStatus = "Rendering beep chapter..."
                beepScope.launch {
                    beepStatus = withContext(Dispatchers.IO) {
                        runBeepCheck(appContext)
                    }
                    beepRunning = false
                }
            }
        }
    )
}

/**
 * RN10: debug beep render behind the voice-lab card (IO thread only).
 *
 * Renders the 4-tone test chapter through the real RN4 spool plus RN5
 * assembly plus the platform RN6 encoder into a bundle dir under cache,
 * writes the manifest plus chapter JSON next to the audio, and
 * re-validates the files on disk. Returns one status line for the card.
 * Only reachable from the debug-gated card; the [BeepSelfCheck] gate
 * refuses release builds as well.
 */
private suspend fun runBeepCheck(appContext: android.content.Context): String {
    try {
        val outDir = File(appContext.cacheDir, "beep-check")
        val spoolDir = File(appContext.cacheDir, "beep-spool")
        try {
            outDir.deleteRecursively()
        } catch (_: Exception) {
        }
        try {
            spoolDir.deleteRecursively()
        } catch (_: Exception) {
        }
        val audioPath = File(outDir, BeepSelfCheck.CHAPTER_AUDIO_PATH).absolutePath
        val encoder = try {
            AndroidAudioEncoder(
                chapterNumber = BeepSelfCheck.CHAPTER_NUMBER,
                finalPath = audioPath
            )
        } catch (e: Exception) {
            return "Beep check failed: ${e.message}"
        }
        val outcome = try {
            BeepSelfCheck.run(
                spoolDir = spoolDir.absolutePath,
                spoolIo = app.auloud.player.render.JavaFileSpoolIo(),
                readSpoolBytes = { fileName -> File(spoolDir, fileName).readBytes() },
                encoder = encoder,
                isDebugBuild = BuildConfig.DEBUG
            )
        } catch (e: Exception) {
            try {
                encoder.abort()
            } catch (_: Exception) {
            }
            return "Beep check failed: ${e.message}"
        }
        if (outcome !is BeepSelfCheck.Outcome.Success) {
            return "Beep check failed: ${(outcome as BeepSelfCheck.Outcome.Failure).reason}"
        }
        val bundle = outcome.bundle
        try {
            val textFile = File(outDir, BeepSelfCheck.CHAPTER_TEXT_PATH)
            textFile.parentFile?.mkdirs()
            textFile.writeText(bundle.chapterJson, Charsets.UTF_8)
            val manifestFile = File(outDir, "manifest.json")
            manifestFile.writeText(
                BundleParser.json.encodeToString(Manifest.serializer(), bundle.manifest),
                Charsets.UTF_8
            )
        } catch (e: Exception) {
            return "Beep check failed: cannot write bundle (${e.message})"
        }
        val diskErrors = try {
            BundleValidator.validateBundle(outDir)
        } catch (e: Exception) {
            return "Beep check failed: disk validation crashed (${e.message})"
        }
        if (bundle.validationErrors.isNotEmpty() || diskErrors.isNotEmpty()) {
            val first = (bundle.validationErrors + diskErrors).first()
            return "Beep chapter rendered but invalid: $first"
        }
        val starts = bundle.timings.joinToString(", ") { it.startMs.toString() }
        return "Beep chapter ok: ${bundle.timings.size} tones at $starts ms, " +
            "duration ${bundle.durationMs} ms, audio $audioPath"
    } catch (e: Exception) {
        return "Beep check failed: ${e.message}"
    }
}
