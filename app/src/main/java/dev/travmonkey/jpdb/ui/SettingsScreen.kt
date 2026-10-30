package dev.travmonkey.jpdb.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.travmonkey.jpdb.data.GradeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val cfg by vm.config.collectAsState()
    val s by vm.repo.state.collectAsState()
    BackHandler { vm.closeSettings() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = vm::closeSettings) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = cfg.apiKey,
                onValueChange = { v -> vm.updateConfig { it.copy(apiKey = v.trim()) } },
                label = { Text("jpdb API key") },
                placeholder = { Text("bottom of jpdb.io/settings") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::fetchApiKey) { Text("Get it from my jpdb login") }
                vm.settingsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            HorizontalDivider()
            Text("Grading buttons (match your jpdb setting)", style = MaterialTheme.typography.titleSmall)
            listOf(GradeMode.PassFail to "Pass / Fail", GradeMode.Five to "5 grades (Nothing … Easy)").forEach { (mode, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = cfg.mode == mode, onClick = { vm.updateConfig { it.copy(mode = mode) } }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = cfg.mode == mode, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }

            HorizontalDivider()
            NumberField("Background refresh every N hours", cfg.syncHours, min = 1) { n -> vm.updateConfig { it.copy(syncHours = n) } }
            NumberField("Show “jpdb is slow” after N seconds", (cfg.slowMs / 1000).toInt(), min = 1) { n -> vm.updateConfig { it.copy(slowMs = n * 1000L) } }
            NumberField("Re-read deck lists every N hours (0 = every sync)", cfg.deckTtlHours, min = 0) { n -> vm.updateConfig { it.copy(deckTtlHours = n) } }

            HorizontalDivider()
            Text(
                buildString {
                    append("${s.queue.size} cards ready offline · ${s.outbox} grades waiting to upload\n")
                    append("Last sync: ")
                    append(if (s.lastSync == 0L) "never" else DateUtils.getRelativeTimeSpanString(s.lastSync).toString())
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { vm.sync(forceDecks = true) }, enabled = !s.syncing) {
                Text(if (s.syncing) s.progress.ifEmpty { "Syncing…" } else "Full resync (re-read all decks)")
            }
            Text(
                "Grades are uploaded with your normal jpdb login in this app, so stay logged in. " +
                    "For reliable background refresh, set this app's battery usage to Unrestricted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, min: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter(Char::isDigit).take(4)
            text.toIntOrNull()?.takeIf { it >= min }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
