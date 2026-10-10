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
 * CP8: in-app licenses screen (Settings entry). VC1: the entry list and
 * the header text live per flavor ([flavorLicenses], [flavorLicenseHeader]
 * in the `core`/`full` source sets), so each build lists exactly what it
 * ships. Versions match `gradle/libs.versions.toml` exactly.
 */
data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val note: String
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
                text = flavorLicenseHeader,
                style = MaterialTheme.typography.bodyMedium
            )
            for (entry in flavorLicenses) {
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
