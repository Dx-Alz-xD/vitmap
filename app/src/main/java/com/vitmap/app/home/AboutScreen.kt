package com.vitmap.app.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vitmap.app.BuildConfig
import com.vitmap.app.ui.components.AuroraBackground
import com.vitmap.app.ui.components.Panel
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.ui.theme.MonoStyle

private data class Credit(val name: String, val detail: String, val licenseAsset: String?)

private val credits = listOf(
    Credit("Chess pieces (cburnett)", "Colin M.L. Burnett, via Wikimedia Commons and lichess.org. BSD / GPL / GFDL / CC BY-SA 3.0.", null),
    Credit("WordNet 3.1", "Princeton University. Powers the offline dictionary, synonyms and antonyms.", "licenses/wordnet-db.txt"),
    Credit("Color Name List", "David Aerne and contributors, MIT licence. 5,000 colour names.", "licenses/color-name-list.txt"),
    Credit("Outfit typeface", "The Outfit Project Authors, SIL Open Font Licence 1.1.", "licenses/outfit-ofl.txt"),
    Credit("JetBrains Mono", "The JetBrains Mono Project Authors, SIL Open Font Licence 1.1.", "licenses/jetbrains-mono-ofl.txt"),
    Credit("PeSTO evaluation tables", "Ronald Friederich. Piece-square tables used by the chess engine.", null),
    Credit("Google ML Kit", "On-device translation, language identification and text recognition.", null),
    Credit("AndroidX & Jetpack Compose", "The Android Open Source Project, Apache 2.0.", null),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    ToolScaffold(title = "About", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    AuroraBackground(listOf(Accents.Brand.start, Accents.Brand.end, Accents.Chess.start), Modifier.fillMaxSize(), intensity = 0.5f)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Vitmap", style = MaterialTheme.typography.displaySmall)
                        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionTitle("Built with") }
            items(credits) { c -> CreditCard(c) }
        }
    }
}

@Composable
private fun CreditCard(c: Credit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val text = remember(c.licenseAsset, expanded) {
        if (expanded && c.licenseAsset != null) runCatching { context.assets.open(c.licenseAsset).bufferedReader().readText() }.getOrNull() else null
    }
    Panel(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = c.licenseAsset != null) { expanded = !expanded },
    ) {
        Text(c.name, style = MaterialTheme.typography.titleMedium)
        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (c.licenseAsset != null && !expanded) {
            Text("Tap to read the licence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
        }
        if (text != null) {
            Text(text, style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize), color = Color.Unspecified, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
