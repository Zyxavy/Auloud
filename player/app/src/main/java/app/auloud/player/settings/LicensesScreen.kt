package app.auloud.player.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * CP8: in-app licenses screen (Settings entry).
 *
 * A static list authored from `docs/08-Licenses.md` section 2 plus the
 * voice-model rows (Kokoro-82M, espeak-ng, en_core_web_sm). Versions match
 * `gradle/libs.versions.toml` exactly. The voice models and espeak-ng run on
 * the PC (Scribe) and are NOT bundled in this app; they are listed because
 * the books you hear are made with them.
 */
data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val note: String
)

/** Player dependencies shipped in the APK, then Scribe-side voice items. */
val playerLicenses: List<LicenseEntry> = listOf(
    LicenseEntry("Kotlin standard library", "2.0.21", "Apache-2.0", "App code language"),
    LicenseEntry("kotlinx-coroutines (Android)", "1.9.0", "Apache-2.0", "Background work and UI state"),
    LicenseEntry("Jetpack Compose BOM", "2024.09.00", "Apache-2.0", "Reader and library UI"),
    LicenseEntry("androidx.activity (Compose)", "1.9.3", "Apache-2.0", "App entry point"),
    LicenseEntry("Media3 (ExoPlayer, Session)", "1.5.1", "Apache-2.0", "Audio playback"),
    LicenseEntry("Room (Runtime, KTX)", "2.6.1", "Apache-2.0", "Library and progress storage"),
    LicenseEntry("kotlinx-serialization (JSON)", "1.7.3", "Apache-2.0", "Bundle manifest and chapter parsing"),
    LicenseEntry("Coil (Compose)", "2.6.0", "Apache-2.0", "Cover art loading"),
    LicenseEntry("JUnit", "4.13.2", "EPL-1.0", "Unit tests only, not shipped in the app"),
    LicenseEntry("MockK", "1.13.12", "Apache-2.0", "Unit tests only, not shipped in the app"),
    LicenseEntry("Turbine", "1.2.1", "Apache-2.0", "Unit tests only, not shipped in the app"),
    LicenseEntry(
        "Kokoro-82M voices",
        "v1.0 (54 voices)",
        "Apache-2.0",
        "Scribe-side (PC): narrator am_onyx, bf_isabella, bm_lewis, " +
            "im_nicola, jf_alpha, zf_xiaoxiao, am_eric, af_bella, am_adam. " +
            "Not bundled in this app."
    ),
    LicenseEntry(
        "espeak-ng",
        "1.52.0",
        "GPL-3.0-or-later",
        "Scribe-side (PC) phonemizer. Not bundled in this app."
    ),
    LicenseEntry(
        "spaCy en_core_web_sm",
        "3.8.0",
        "MIT",
        "Scribe-side (PC) language model. Not bundled in this app."
    )
)

@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(text = "Licenses", style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Auloud Player is open source. Dev builds also link sherpa/espeak-ng (GPL); " +
                    "do not distribute this build until VC1 ships the licensed flavors (D-126). " +
                    "The voice items below run on the PC (Scribe).",
                style = MaterialTheme.typography.bodyMedium
            )
            for (entry in playerLicenses) {
                LicenseRow(entry = entry)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LicenseRow(entry: LicenseEntry, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "${entry.version} - ${entry.license}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = entry.note,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
