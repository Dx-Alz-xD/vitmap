package com.vitmap.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vitmap.app.data.packs.Pack
import com.vitmap.app.data.packs.PackState
import com.vitmap.app.ui.theme.Accent
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.vitmap
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (v >= 100 || i == 0) String.format(Locale.US, "%.0f %s", v, units[i]) else String.format(Locale.US, "%.1f %s", v, units[i])
}

@Composable
fun rememberPackState(pack: Pack): State<PackState> {
    val manager = LocalContext.current.vitmap.packs
    val flow = remember(pack.id) { manager.state(pack) }
    return flow.collectAsState()
}

/**
 * Shows [content] once [pack] is installed. Until then it shows what the download is, how big it
 * is, and a button that fetches it with live progress. [header] stays visible above the prompt
 * (e.g. a script picker that switches packs).
 */
@Composable
fun PackGate(
    pack: Pack,
    modifier: Modifier = Modifier,
    accent: Accent = Accents.Brand,
    icon: ImageVector = Icons.Rounded.CloudDownload,
    header: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val state by rememberPackState(pack)
    AnimatedContent(
        targetState = when (state) {
            is PackState.Ready -> 2
            PackState.Checking -> 0
            else -> 1
        },
        transitionSpec = { (fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.97f)) togetherWith fadeOut(tween(160)) },
        modifier = modifier,
        label = "packGate",
    ) { phase ->
        when (phase) {
            2 -> content()
            0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> Column(Modifier.fillMaxSize()) {
                header()
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    PackPrompt(pack, accent = accent, icon = icon, modifier = Modifier.padding(20.dp).widthIn(max = 520.dp))
                }
            }
        }
    }
}

/** The download card: what the pack is, its size, a download button and progress. */
@Composable
fun PackPrompt(pack: Pack, modifier: Modifier = Modifier, accent: Accent = Accents.Brand, icon: ImageVector = Icons.Rounded.CloudDownload, compact: Boolean = false) {
    val manager = LocalContext.current.vitmap.packs
    val state by rememberPackState(pack)
    Panel(modifier.fillMaxWidth(), padding = if (compact) 16.dp else 24.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!compact) GradientIcon(icon, accent, size = 64.dp)
            Text(pack.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(pack.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (pack.requires.isNotEmpty()) {
                Text(
                    "Also downloads " + pack.requires.joinToString { "${it.title} (${formatBytes(it.approxBytes)})" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(4.dp))
            when (val s = state) {
                is PackState.Working -> PackProgress(s, accent, onCancel = { manager.cancel(pack) })
                is PackState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = { manager.install(pack) }) { Text("Try again") }
                }
                is PackState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.OfflinePin, null, tint = accent.start)
                    Spacer(Modifier.size(6.dp))
                    Text("Downloaded", color = accent.start)
                }
                else -> {
                    Button(onClick = { manager.install(pack) }) {
                        Icon(Icons.Rounded.CloudDownload, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Download · ${formatBytes(pack.totalBytes)}")
                    }
                    Text("Downloaded once, then works offline. Remove it anytime in Settings → Downloads.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun PackProgress(s: PackState.Working, accent: Accent, onCancel: (() -> Unit)?) {
    val fraction = s.fraction
    val animated by animateFloatAsState(fraction ?: 0f, tween(250), label = "packProgress")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fraction != null) {
            LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().height(8.dp), color = accent.start, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {})
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp), color = accent.start, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(s.step, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (s.total > 0) {
                Text("${formatBytes(s.done)} / ${formatBytes(s.total)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onCancel != null) OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

/**
 * A dialog asking to download [pack]; calls [onReady] (and closes) as soon as it's installed. Use it
 * when a feature needs a pack in the middle of an action.
 */
@Composable
fun PackDialog(pack: Pack, onDismiss: () -> Unit, onReady: () -> Unit = {}, accent: Accent = Accents.Brand) {
    val state by rememberPackState(pack)
    LaunchedEffect(state) {
        if (state is PackState.Ready) {
            onReady()
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (state is PackState.Working) "Hide" else "Not now") } },
        text = { PackPrompt(pack, accent = accent, compact = true) },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        iconContentColor = Color.Unspecified,
    )
}
