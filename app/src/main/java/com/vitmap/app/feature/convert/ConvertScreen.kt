package com.vitmap.app.feature.convert

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Architecture
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.BlurCircular
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsBaseball
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Water
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.components.copyToClipboard
import com.vitmap.app.ui.theme.Accent
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.ui.theme.MonoStyle
import com.vitmap.core.math.Expression
import com.vitmap.core.math.NumberFormatter
import com.vitmap.core.units.Converter
import com.vitmap.core.units.UnitCategory
import com.vitmap.core.units.UnitDef
import com.vitmap.core.units.UnitGroup
import kotlinx.coroutines.launch

fun categoryIcon(key: String): ImageVector = when (key) {
    "straighten" -> Icons.Rounded.Straighten
    "scale" -> Icons.Rounded.Scale
    "thermostat" -> Icons.Rounded.Thermostat
    "square_foot" -> Icons.Rounded.SquareFoot
    "local_drink" -> Icons.Rounded.LocalDrink
    "schedule" -> Icons.Rounded.Schedule
    "speed" -> Icons.Rounded.Speed
    "rocket_launch" -> Icons.Rounded.RocketLaunch
    "compress" -> Icons.Rounded.Compress
    "bolt" -> Icons.Rounded.Bolt
    "power" -> Icons.Rounded.Power
    "fitness_center" -> Icons.Rounded.FitnessCenter
    "settings" -> Icons.Rounded.Settings
    "water_drop" -> Icons.Rounded.WaterDrop
    "storage" -> Icons.Rounded.Storage
    "network_check" -> Icons.Rounded.NetworkCheck
    "architecture" -> Icons.Rounded.Architecture
    "graphic_eq" -> Icons.Rounded.GraphicEq
    "local_gas_station" -> Icons.Rounded.LocalGasStation
    "electric_bolt" -> Icons.Rounded.ElectricBolt
    "battery_charging_full" -> Icons.Rounded.BatteryChargingFull
    "tune" -> Icons.Rounded.Tune
    "battery_std" -> Icons.Rounded.BatteryStd
    "cable" -> Icons.Rounded.Cable
    "battery_full" -> Icons.Rounded.BatteryFull
    "settings_input_component" -> Icons.Rounded.SettingsInputComponent
    "blur_circular" -> Icons.Rounded.BlurCircular
    "explore" -> Icons.Rounded.Explore
    "wifi_tethering" -> Icons.Rounded.WifiTethering
    "light_mode" -> Icons.Rounded.LightMode
    "brightness_high" -> Icons.Rounded.BrightnessHigh
    "flare" -> Icons.Rounded.Flare
    "warning" -> Icons.Rounded.Warning
    "health_and_safety" -> Icons.Rounded.HealthAndSafety
    "sensors" -> Icons.Rounded.Sensors
    "opacity" -> Icons.Rounded.Opacity
    "waves" -> Icons.Rounded.Waves
    "water" -> Icons.Rounded.Water
    "science" -> Icons.Rounded.Science
    "bubble_chart" -> Icons.Rounded.BubbleChart
    "rotate_right" -> Icons.Rounded.RotateRight
    "sports_baseball" -> Icons.Rounded.SportsBaseball
    "device_thermostat" -> Icons.Rounded.DeviceThermostat
    "local_fire_department" -> Icons.Rounded.LocalFireDepartment
    "wb_sunny" -> Icons.Rounded.WbSunny
    "text_fields" -> Icons.Rounded.TextFields
    "pin" -> Icons.Rounded.Pin
    else -> Icons.Rounded.Category
}

private val palette = listOf(
    Accents.Convert, Accents.Chess, Accents.Browser, Accents.Measure, Accents.ColorPicker, Accents.Translator, Accents.TicTacToe,
)

private fun accentFor(index: Int): Accent = palette[index % palette.size]

@Composable
fun ConvertScreen(onBack: () -> Unit) {
    val vm: ConvertViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = s.openCategory != null) { vm.open(null) }

    AnimatedContent(
        targetState = s.openCategory,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f)) { it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f)) { -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it / 4 } + fadeOut())
            }
        },
        label = "convert",
    ) { open ->
        val cat = s.categories.firstOrNull { it.id == open }
        if (open == null || cat == null) {
            CategoryGrid(s, vm, onBack)
        } else {
            CategoryDetail(s, cat, vm, onBack = { vm.open(null) })
        }
    }
}

@Composable
private fun CategoryGrid(s: ConvertState, vm: ConvertViewModel, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var showNewCategory by remember { mutableStateOf(false) }
    val totalUnits = s.categories.sumOf { it.units.size }
    ToolScaffold(
        title = "Conversion",
        subtitle = "${s.categories.size} categories · $totalUnits units",
        accent = Accents.Convert,
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showNewCategory = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Category") })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search any unit — furlong, kPa, tola…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            AnimatedContent(query.isBlank(), label = "search") { browsing ->
                if (browsing) {
                    val favs = s.categories.filter { it.id in s.prefs.favorites }
                    val ordered = favs + s.categories.filter { it.id !in s.prefs.favorites }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (favs.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Favourites") }
                        items(ordered, key = { it.id }) { cat ->
                            val index = s.categories.indexOf(cat)
                            CategoryCard(cat, accentFor(index), cat.id in s.prefs.favorites, index, Modifier.animateItem()) { vm.open(cat.id) }
                        }
                    }
                } else {
                    val q = query.trim()
                    val hits = s.categories.flatMap { c -> c.units.filter { it.matches(q) }.map { c to it } }
                        .sortedBy { (_, u) -> if (u.symbol.equals(q, true) || u.name.equals(q, true)) 0 else 1 }
                        .take(80)
                    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (hits.isEmpty()) item { Text("No units match \"$q\"", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(hits, key = { it.second.id }) { (c, u) ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth().bouncyClickable(pressedScale = 0.98f) { vm.openUnit(c.id, u.id) },
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(categoryIcon(c.icon), null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(u.name, style = MaterialTheme.typography.titleSmall)
                                        Text("${c.name} · ${u.group.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(u.symbol, style = MonoStyle)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showNewCategory) {
        NewCategoryDialog(onDismiss = { showNewCategory = false }) { name, base, symbol ->
            val id = vm.addCategory(name, base, symbol)
            showNewCategory = false
            vm.open(id)
        }
    }
}

@Composable
private fun CategoryCard(cat: UnitCategory, accent: Accent, favorite: Boolean, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index % 12) * 30L)
        appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 260f))
    }
    Column(
        modifier
            .graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1 - appear.value) * 40.dp.toPx()
            }
            .heightIn(min = 118.dp)
            .bouncyClickable(shape = RoundedCornerShape(24.dp), onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(accent.brush), contentAlignment = Alignment.Center) {
                Icon(categoryIcon(cat.icon), null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            if (favorite) Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFC145), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(cat.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${cat.units.size} units", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryDetail(s: ConvertState, cat: UnitCategory, vm: ConvertViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val from = cat.unit(s.fromId ?: "") ?: cat.defaultPair.first
    val to = cat.unit(s.toId ?: "") ?: cat.defaultPair.second
    val value = Expression.evaluate(s.input)
    val sig = s.prefs.significant
    val sci = s.prefs.scientific
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = from, false = to
    var showAdd by remember { mutableStateOf(false) }
    var showPrefs by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var swapSpin by remember { mutableFloatStateOf(0f) }
    val spin by animateFloatAsState(swapSpin, spring(dampingRatio = 0.55f, stiffness = 300f), label = "spin")
    val scope = rememberCoroutineScope()
    val catIndex = s.categories.indexOf(cat)
    val accent = accentFor(catIndex)
    var menu by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf(s.input) }
    val typed = remember { ArrayDeque<String>() }
    LaunchedEffect(s.input) {
        if (s.input !in typed) {
            inputText = s.input
            typed.clear()
        }
    }

    ToolScaffold(
        title = cat.name,
        subtitle = cat.description,
        accent = accent,
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.toggleFavorite(cat.id) }) {
                Icon(if (cat.id in s.prefs.favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favourite")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.Tune, "Options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Precision & format") }, onClick = { menu = false; showPrefs = true })
                    DropdownMenuItem(text = { Text("Add custom unit") }, onClick = { menu = false; showAdd = true })
                    if (cat.isCustom) DropdownMenuItem(text = { Text("Delete category") }, onClick = { menu = false; vm.deleteCategory(cat.id) })
                }
            }
        },
    ) { padding ->
        val result = value?.let { Converter.convert(it, from, to) }
        val grouped = cat.units.filter { it.matches(filter) }.groupBy { it.group }.toSortedMap(compareBy { it.ordinal })
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "converter") {
                Surface(shape = RoundedCornerShape(28.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.background(accent.brush).padding(16.dp)) {
                        UnitButton(from, onClick = { picking = true })
                        TextField(
                            value = inputText,
                            onValueChange = {
                                inputText = it
                                typed.addLast(it)
                                if (typed.size > 30) typed.removeFirst()
                                vm.setInput(it)
                            },
                            textStyle = MaterialTheme.typography.displaySmall.copy(color = Color.White),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.White.copy(alpha = 0.6f), unfocusedIndicatorColor = Color.White.copy(alpha = 0.3f),
                                cursorColor = Color.White,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                val isExpr = value != null && s.input.trim().toDoubleOrNull() == null && s.input.isNotBlank()
                                when {
                                    value == null && s.input.isNotBlank() -> Text("Not a number", color = Color.White.copy(alpha = 0.85f))
                                    isExpr -> Text("= ${NumberFormatter.format(value!!, sig)}", color = Color.White.copy(alpha = 0.85f))
                                    else -> {}
                                }
                            },
                        )
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            FilledIconButton(
                                onClick = { swapSpin += 180f; vm.swap() },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.25f), contentColor = Color.White),
                                modifier = Modifier.graphicsLayer { rotationZ = spin },
                            ) { Icon(Icons.Rounded.SwapVert, "Swap") }
                        }
                        UnitButton(to, onClick = { picking = false })
                        AnimatedContent(
                            targetState = result?.let { NumberFormatter.format(it, sig, sci) } ?: "—",
                            transitionSpec = {
                                (slideInVertically { it / 3 } + fadeIn(tween(180))) togetherWith (slideOutVertically { -it / 3 } + fadeOut(tween(120))) using SizeTransform(clip = false)
                            },
                            label = "result",
                        ) { text ->
                            Text(
                                text,
                                style = MaterialTheme.typography.displaySmall.copy(fontSize = if (text.length > 14) 30.sp else 40.sp),
                                color = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = result != null) { copyToClipboard(context, NumberFormatter.plain(result!!, sig), to.symbol) }
                                    .padding(vertical = 4.dp),
                            )
                        }
                        to.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f)) }
                        if (value != null) {
                            Text(
                                "1 ${from.symbol} = ${NumberFormatter.format(Converter.convert(1.0, from, to), sig, sci)} ${to.symbol}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
            item(key = "filter") {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter ${cat.units.size} units") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            for ((group, units) in grouped) {
                item(key = "h-${group.name}") { SectionTitle(group.title, Modifier.padding(top = 6.dp)) }
                items(units, key = { it.id }) { u ->
                    val converted = value?.let { Converter.convert(it, from, u) }
                    val isTo = u.id == to.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isTo) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                            .combinedClickable(
                                onClick = { vm.setTo(u.id) },
                                onLongClick = { converted?.let { copyToClipboard(context, NumberFormatter.plain(it, sig), u.symbol) } },
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(u.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(u.symbol, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            converted?.let { NumberFormatter.format(it, sig, sci) } ?: "—",
                            style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                            maxLines = 1,
                        )
                        if (u.group == UnitGroup.CUSTOM) {
                            IconButton(onClick = { vm.deleteUnit(u.id) }) { Icon(Icons.Rounded.Delete, "Delete ${u.name}", Modifier.size(18.dp)) }
                        }
                    }
                }
            }
            item(key = "add") {
                TextButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add your own unit")
                }
            }
        }
    }

    picking?.let { isFrom ->
        UnitPickerSheet(cat, selected = if (isFrom) from.id else to.id, onDismiss = { picking = null }) { id ->
            if (isFrom) vm.setFrom(id) else vm.setTo(id)
            picking = null
        }
    }
    if (showAdd) {
        AddUnitDialog(cat, onDismiss = { showAdd = false }) { name, symbol, amount, rel ->
            vm.addUnit(cat.id, name, symbol, amount, rel)
            showAdd = false
        }
    }
    if (showPrefs) {
        PrecisionDialog(sig, sci, onDismiss = { showPrefs = false }, onSig = { scope.launch { vm.setSignificant(it) } }, onSci = { vm.setScientific(it) })
    }
}

@Composable
private fun UnitButton(u: UnitDef, onClick: () -> Unit) {
    Row(
        Modifier
            .bouncyClickable(shape = CircleShape, pressedScale = 0.97f, onClick = onClick)
            .background(Color.White.copy(alpha = 0.2f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(u.name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text(u.symbol, style = MonoStyle, color = Color.White.copy(alpha = 0.85f))
    }
}

@Composable
private fun UnitPickerSheet(cat: UnitCategory, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Search units") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            val grouped = cat.units.filter { it.matches(q) }.groupBy { it.group }.toSortedMap(compareBy { it.ordinal })
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                for ((g, list) in grouped) {
                    item(key = "g-${g.name}") { SectionTitle(g.title, Modifier.padding(top = 8.dp)) }
                    items(list, key = { it.id }) { u ->
                        val sel = u.id == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onPick(u.id) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(u.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(u.symbol, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddUnitDialog(cat: UnitCategory, onDismiss: () -> Unit, onAdd: (String, String, Double, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("1") }
    var rel by remember { mutableStateOf(cat.baseUnit) }
    var picking by remember { mutableStateOf(false) }
    val amt = Expression.evaluate(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New unit in ${cat.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. My stride)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(symbol, { symbol = it }, label = { Text("Symbol (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("1 ${symbol.ifBlank { name.ifBlank { "unit" } }} equals", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(amount, { amount = it }, singleLine = true, modifier = Modifier.weight(1f), isError = amt == null || amt == 0.0)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { picking = true }) { Text(rel.symbol, maxLines = 1) }
                }
                Text(rel.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && amt != null && amt != 0.0, onClick = { onAdd(name, symbol, amt!!, rel.id) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) UnitPickerSheet(cat, rel.id, onDismiss = { picking = false }) { id ->
        cat.unit(id)?.let { rel = it }
        picking = false
    }
}

@Composable
private fun NewCategoryDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create your own conversion family, then add units defined in terms of the base unit.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("Category (e.g. Coffee)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(base, { base = it }, label = { Text("Base unit (e.g. Shot)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(symbol, { symbol = it }, label = { Text("Symbol (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && base.isNotBlank(), onClick = { onCreate(name, base, symbol) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PrecisionDialog(sig: Int, sci: Boolean, onDismiss: () -> Unit, onSig: (Int) -> Unit, onSci: (Boolean) -> Unit) {
    var digits by remember { mutableFloatStateOf(sig.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Precision & format") },
        text = {
            Column {
                Text("${digits.toInt()} significant digits", style = MaterialTheme.typography.titleSmall)
                Slider(digits, { digits = it; onSig(it.toInt()) }, valueRange = 3f..15f, steps = 11)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Always scientific notation", Modifier.weight(1f))
                    Switch(sci, onSci)
                }
                AnimatedVisibility(true, enter = expandVertically(), exit = shrinkVertically()) {
                    Text("Example: ${NumberFormatter.format(Math.PI * 1234.5678, digits.toInt(), sci)}", style = MonoStyle, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
