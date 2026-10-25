package com.vitmap.app.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitmap.app.data.AppSettings
import com.vitmap.app.nav.Connectivity
import com.vitmap.app.nav.Tool
import com.vitmap.app.nav.ToolCategory
import com.vitmap.app.nav.ToolIcon
import com.vitmap.app.nav.Tools
import com.vitmap.app.nav.sharedToolBounds
import com.vitmap.app.ui.components.AuroraBackground
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.Panel
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.StatusPill
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.vitmap
import kotlinx.coroutines.delay
import java.time.LocalTime

@Composable
fun HomeScreen(onOpenTool: (String) -> Unit, onOpenSettings: () -> Unit) {
    val app = LocalContext.current.vitmap
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var introPlayed by rememberSaveable { mutableStateOf(false) }
    val animateIn = remember { !introPlayed }
    LaunchedEffect(Unit) {
        delay(1500)
        introPlayed = true
    }

    val filtered = Tools.all.filter { it.matches(query) && (category == null || it.category.name == category) }
    val scroll = rememberScrollState()
    val auroraColors = listOf(Accents.Brand.start, Accents.Chess.start, Accents.Translator.start, Accents.ColorPicker.start)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AuroraBackground(
            colors = auroraColors,
            intensity = 0.42f,
            modifier = Modifier
                .fillMaxWidth()
                .height(460.dp)
                .graphicsLayer {
                    translationY = -scroll.value * 0.45f
                    alpha = 1f - (scroll.value / 1100f).coerceIn(0f, 1f)
                },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Header(onOpenSettings, animateIn)
            Spacer(Modifier.height(20.dp))
            SearchField(query) { query = it }
            Spacer(Modifier.height(14.dp))
            CategoryChips(category) { category = it }

            AnimatedVisibility(
                visible = settings.recentTools.isNotEmpty() && query.isBlank() && category == null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                RecentRow(settings.recentTools.mapNotNull { Tools.byId(it) }, onOpenTool)
            }

            Spacer(Modifier.height(8.dp))
            Column(Modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = 300f))) {
                if (filtered.isEmpty()) {
                    EmptyState(Icons.Rounded.SearchOff, "No tools match \"$query\"", "Try another word, like \"units\" or \"camera\".")
                }
                var index = 0
                for (cat in ToolCategory.entries) {
                    val tools = filtered.filter { it.category == cat }
                    if (tools.isEmpty()) continue
                    SectionTitle(cat.title, Modifier.padding(top = 10.dp)) {
                        Icon(cat.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    for (row in tools.chunked(2)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (tool in row) {
                                ToolCard(
                                    tool = tool,
                                    index = index++,
                                    animateIn = animateIn,
                                    wide = row.size == 1,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpenTool(tool.id) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OfflineBanner()
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Up late?"
}

@Composable
private fun Header(onOpenSettings: () -> Unit, animateIn: Boolean) {
    val appear = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 140f)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .graphicsLayer {
                    alpha = appear.value.coerceIn(0f, 1f)
                    translationX = (1 - appear.value) * -60.dp.toPx()
                },
        ) {
            Text(greeting(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Vitmap", style = MaterialTheme.typography.displayMedium)
            Text(
                "${Tools.all.size} tools · ${Tools.all.count { it.connectivity != Connectivity.ONLINE }} work offline",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search tools") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f),
        ),
    )
}

@Composable
private fun CategoryChips(selected: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") },
            shape = CircleShape,
            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)),
        )
        for (c in ToolCategory.entries) {
            FilterChip(
                selected = selected == c.name,
                onClick = { onSelect(if (selected == c.name) null else c.name) },
                label = { Text(c.title) },
                leadingIcon = { Icon(c.icon, null, Modifier.size(18.dp)) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)),
            )
        }
    }
}

@Composable
private fun RecentRow(tools: List<Tool>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        SectionTitle("Jump back in")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (t in tools) {
                Row(
                    Modifier
                        .bouncyClickable(shape = CircleShape) { onOpen(t.id) }
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
                        .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(t.accent.brush), contentAlignment = Alignment.Center) {
                        ToolIconView(t.icon, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(t.title, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun ToolIconView(icon: ToolIcon, modifier: Modifier = Modifier, tint: Color = Color.White) {
    when (icon) {
        is ToolIcon.Vector -> Icon(icon.icon, null, modifier, tint = tint)
        is ToolIcon.Drawable -> Image(painterResource(icon.res), null, modifier)
    }
}

@Composable
private fun ToolCard(tool: Tool, index: Int, animateIn: Boolean, wide: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val appear = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animateIn) {
            delay(120L + index * 75L)
            appear.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = 170f))
        }
    }
    val float = rememberInfiniteTransition(label = "float")
    val bob by float.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2200 + index * 230, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .graphicsLayer {
                val a = appear.value
                alpha = a.coerceIn(0f, 1f)
                translationY = (1f - a) * 90.dp.toPx()
                val s = 0.86f + 0.14f * a
                scaleX = s
                scaleY = s
            }
            .height(if (wide) 160.dp else 200.dp)
            .sharedToolBounds("tool-${tool.id}")
            .bouncyClickable(shape = shape, onClick = onClick)
            .background(tool.accent.brush),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.12f), radius = size.width * 0.42f, center = Offset(size.width * 0.98f, size.height * 0.02f))
            drawCircle(Color.White.copy(alpha = 0.07f), radius = size.width * 0.32f, center = Offset(size.width * 0.05f, size.height * 1.02f))
        }
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Box(
                Modifier
                    .graphicsLayer { translationY = bob.dp.toPx() }
                    .size(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                ToolIconView(tool.icon, Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(tool.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
            Text(
                tool.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.86f),
                maxLines = if (wide) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            StatusPill(
                tool.connectivity.label,
                Color.White.copy(alpha = 0.22f),
                icon = if (tool.connectivity == Connectivity.ONLINE) Icons.Rounded.Wifi else Icons.Rounded.CloudOff,
            )
        }
    }
}

@Composable
private fun OfflineBanner() {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Accents.Brand.brush), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CloudOff, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Offline first", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Everything except the browser works without internet. The translator downloads each language once, then works offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
