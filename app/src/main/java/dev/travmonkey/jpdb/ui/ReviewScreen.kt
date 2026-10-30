package dev.travmonkey.jpdb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.travmonkey.jpdb.data.Card
import dev.travmonkey.jpdb.data.CardContent
import dev.travmonkey.jpdb.data.GradeMode
import dev.travmonkey.jpdb.data.Net
import dev.travmonkey.jpdb.data.RepoState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: MainViewModel) {
    val s by vm.repo.state.collectAsState()
    val cfg by vm.config.collectAsState()
    val online by vm.online.collectAsState()
    val card = s.queue.firstOrNull()
    val revealed = card != null && vm.revealedKey == card.key

    BackHandler { vm.openWeb() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::openWeb) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to jpdb") }
                },
                title = {
                    Column {
                        Text("${s.queue.size} due", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val net = if (!online) Net.Offline else s.net
                            StatusDot(net)
                            Text(
                                statusLabel(net, s.outbox),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = s.canUndo) { Text("↶", fontSize = 22.sp) }
                    IconButton(onClick = { vm.sync() }, enabled = !s.syncing && online) {
                        if (s.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, "Fetch due cards from jpdb")
                    }
                    IconButton(onClick = vm::openSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            if (card != null) GradeBar(revealed, cfg.mode, onReveal = { vm.reveal(card) }, onGrade = { vm.grade(card, it) })
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .clickable(
                    enabled = card != null && !revealed,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { card?.let(vm::reveal) },
            contentAlignment = Alignment.Center,
        ) {
            if (card == null) EmptyState(s, online, onSync = { vm.sync() })
            else CardFace(card, s.content[card.key], revealed, s.error)
        }
    }
}

private fun statusLabel(net: Net, pending: Int): String {
    val label = when (net) {
        Net.Ok -> if (pending > 0) "sending" else "synced"
        Net.Offline -> "offline"
        Net.Retrying -> "retrying"
    }
    return if (pending > 0) "$label · $pending queued" else label
}

@Composable
private fun CardFace(card: Card, ct: CardContent?, revealed: Boolean, error: String?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(ct?.spelling ?: "?", style = JapaneseText.copy(fontSize = 60.sp, fontWeight = FontWeight.Medium), textAlign = TextAlign.Center)
        if (!revealed) {
            Text("tap to reveal", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        } else if (ct != null) {
            if ((ct.reading != null && ct.reading != ct.spelling) || ct.pitch.isNotEmpty()) {
                PitchReading(ct.reading ?: ct.spelling, ct.pitch, Modifier.padding(top = 6.dp))
            }
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 18.dp)) {
                ct.meanings.forEachIndexed { i, m ->
                    HorizontalDivider()
                    Column(Modifier.padding(vertical = 8.dp)) {
                        if (i == 0 && ct.pos.isNotEmpty()) {
                            Text(ct.pos.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${i + 1}. $m", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            val meta = listOfNotNull(if (card.state == "failed") "previously failed" else null, ct.freq?.let { "top $it" }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun EmptyState(s: RepoState, online: Boolean, onSync: () -> Unit) {
    Column(Modifier.padding(32.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (s.syncing) {
            CircularProgressIndicator()
            Text(s.progress.ifEmpty { "syncing…" }, modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (s.error != null) Text(s.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp))
        Text(
            when {
                s.lastSync == 0L -> "No cards downloaded yet."
                else -> "No due cards in the offline queue."
            },
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.skipped > 0) {
            Text(
                "${s.skipped} offline grade(s) skipped: those cards were already reviewed on jpdb.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        FilledTonalButton(onClick = onSync, enabled = online, modifier = Modifier.padding(top = 20.dp)) {
            Text(if (online) "Check jpdb for due cards" else "Offline — connect to download cards")
        }
    }
}

@Composable
private fun GradeBar(revealed: Boolean, mode: GradeMode, onReveal: () -> Unit, onGrade: (String) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tall = Modifier.height(60.dp)
            when {
                !revealed -> Button(onClick = onReveal, modifier = tall.weight(1f)) { Text("Show answer", fontSize = 17.sp) }
                mode == GradeMode.Five -> listOf("nothing" to "Nothing", "something" to "Something", "hard" to "Hard", "okay" to "Okay", "easy" to "Easy")
                    .forEachIndexed { i, (g, label) ->
                        GradeButton(label, fail = i < 2, small = true, modifier = tall.weight(1f)) { onGrade(g) }
                    }
                else -> {
                    GradeButton("✘ Fail", fail = true, modifier = tall.weight(1f)) { onGrade("fail") }
                    GradeButton("✔ Pass", fail = false, modifier = tall.weight(1f)) { onGrade("pass") }
                }
            }
        }
    }
}

@Composable
private fun GradeButton(label: String, fail: Boolean, modifier: Modifier, small: Boolean = false, onClick: () -> Unit) {
    val colors = if (fail) {
        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
    } else {
        ButtonDefaults.buttonColors(containerColor = passContainer(), contentColor = onPassContainer())
    }
    Button(
        onClick = onClick,
        colors = colors,
        modifier = modifier,
        contentPadding = if (small) ButtonDefaults.TextButtonContentPadding else ButtonDefaults.ContentPadding,
    ) {
        Text(label, fontSize = if (small) 13.sp else 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = Color.Unspecified)
    }
}
