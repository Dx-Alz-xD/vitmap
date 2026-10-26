package com.vitmap.app.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitmap.app.data.AppSettings
import com.vitmap.app.ui.components.Panel
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.theme.ThemeMode
import com.vitmap.app.vitmap
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit, onDownloads: () -> Unit) {
    val app = LocalContext.current.vitmap
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()

    ToolScaffold(title = "Settings", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Appearance")
            Panel(Modifier.fillMaxWidth()) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(4.dp))
                val modes = listOf(
                    Triple(ThemeMode.SYSTEM, "System", Icons.Rounded.PhoneAndroid),
                    Triple(ThemeMode.LIGHT, "Light", Icons.Rounded.LightMode),
                    Triple(ThemeMode.DARK, "Dark", Icons.Rounded.DarkMode),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (mode, label, icon) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { scope.launch { app.settings.setTheme(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            icon = { Icon(icon, null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ToggleRow(
                    Icons.Rounded.AutoAwesome,
                    "Dynamic colours",
                    "Match the app to your wallpaper",
                    settings.dynamicColor,
                ) { scope.launch { app.settings.setDynamicColor(it) } }
            }
            SectionTitle("Feel")
            ToggleRow(Icons.Rounded.Vibration, "Haptic feedback", "Subtle vibrations on taps and moves", settings.haptics) {
                scope.launch { app.settings.setHaptics(it) }
            }
            SectionTitle("Storage")
            Panel(Modifier.fillMaxWidth().bouncyClickable(pressedScale = 0.98f, onClick = onDownloads)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudDownload, null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Downloads", style = MaterialTheme.typography.titleMedium)
                        Text("Models, dictionaries and runtimes the tools fetch on demand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                }
            }
            SectionTitle("About")
            Panel(Modifier.fillMaxWidth().bouncyClickable(pressedScale = 0.98f, onClick = onAbout)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("About Vitmap", style = MaterialTheme.typography.titleMedium)
                        Text("Version, credits and open-source licences", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                }
            }
            Spacer(Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
