package com.vitmap.app.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vitmap.app.data.packs.Pack
import com.vitmap.app.data.packs.PackCatalog
import com.vitmap.app.data.packs.PackState
import com.vitmap.app.ui.components.Panel
import com.vitmap.app.ui.components.PackProgress
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.formatBytes
import com.vitmap.app.ui.components.rememberPackState
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.vitmap
import kotlinx.coroutines.flow.combine

/** Every optional download, what it's for, and how much space it uses. */
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val manager = LocalContext.current.vitmap.packs
    val packs = remember { PackCatalog.all }
    val totalFlow = remember { combine(packs.map { manager.state(it) }) { states -> states.sumOf { (it as? PackState.Ready)?.bytes ?: 0L } } }
    val total by totalFlow.collectAsState(0L)
    var confirm by remember { mutableStateOf<Pack?>(null) }

    ToolScaffold(title = "Downloads", subtitle = "${formatBytes(total)} used on this device", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Storage, null, tint = Accents.Brand.start)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Models, dictionaries and runtimes aren't bundled with the app. Each one downloads the first time a tool needs it and then works offline.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            packs.groupBy { it.group }.toSortedMap(compareBy { it.ordinal }).forEach { (group, list) ->
                item(key = "h-${group.name}") { SectionTitle(group.title) }
                items(list, key = { it.id }) { pack -> PackRow(pack, onDelete = { confirm = pack }) }
            }
        }
    }

    confirm?.let { pack ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove ${pack.title}?") },
            text = {
                Text(
                    if (pack.installer.removable) "Frees up space. Tools that need it will offer to download it again."
                    else "Google Play services manages this model. It will remove it when no app needs it any more.",
                )
            },
            confirmButton = { TextButton(onClick = { manager.remove(pack); confirm = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun PackRow(pack: Pack, onDelete: () -> Unit) {
    val manager = LocalContext.current.vitmap.packs
    val state by rememberPackState(pack)
    Panel(Modifier.fillMaxWidth().animateContentSize(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(pack.title, style = MaterialTheme.typography.titleSmall)
                val status = when (val s = state) {
                    is PackState.Ready -> if (s.bytes > 0) "Downloaded · ${formatBytes(s.bytes)}" else "Downloaded"
                    is PackState.Working -> s.step
                    is PackState.Failed -> s.message
                    PackState.Checking -> "Checking…"
                    PackState.Missing -> "Not downloaded · about ${formatBytes(pack.totalBytes)}"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is PackState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state) {
                is PackState.Ready -> {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Accents.Measure.start, modifier = Modifier.size(20.dp))
                    IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Remove") }
                }
                is PackState.Working -> {}
                is PackState.Failed -> FilledTonalButton(onClick = { manager.install(pack) }) {
                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
                else -> FilledTonalButton(onClick = { manager.install(pack) }) {
                    Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Get")
                }
            }
        }
        (state as? PackState.Working)?.let {
            Spacer(Modifier.size(8.dp))
            PackProgress(it, Accents.Brand, onCancel = { manager.cancel(pack) })
        }
        Text(pack.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}
