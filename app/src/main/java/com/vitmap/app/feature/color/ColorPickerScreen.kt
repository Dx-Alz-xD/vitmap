package com.vitmap.app.feature.color

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vitmap.app.ui.components.ToolScaffold

@Composable
fun ColorPickerScreen(onBack: () -> Unit) {
    ToolScaffold(title = "ColorPickerScreen", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Coming up") }
    }
}
