package com.vitmap.app.feature.clock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.theme.Accents
import com.vitmap.core.geo.Sun
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private fun zone(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())

private fun offsetLabel(target: ZonedDateTime, local: ZonedDateTime): String {
    val diff = target.offset.totalSeconds - local.offset.totalSeconds
    if (diff == 0) return "Same time"
    val h = abs(diff) / 3600
    val m = (abs(diff) % 3600) / 60
    val sign = if (diff > 0) "+" else "−"
    return sign + (if (m == 0) "${h}h" else "${h}h ${m}m")
}

private fun dayLabel(target: ZonedDateTime, local: ZonedDateTime): String {
    val d = ChronoUnit.DAYS.between(local.toLocalDate(), target.toLocalDate())
    return when (d) {
        0L -> "Today"
        1L -> "Tomorrow"
        -1L -> "Yesterday"
        else -> target.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

@Composable
fun ClockScreen(onBack: () -> Unit) {
    val vm: ClockViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val camera = remember { MapCamera() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val localZone = ZoneId.systemDefault()

    ToolScaffold(
        title = "World Clock",
        subtitle = "${localZone.id.replace('_', ' ')} · ${Instant.ofEpochMilli(s.now).atZone(localZone).format(DateTimeFormatter.ofPattern(if (s.prefs.use24h) "HH:mm:ss" else "h:mm:ss a"))}",
        accent = Accents.Clock,
        onBack = onBack,
        actions = {
            TextButton(onClick = { vm.setPrefs { it.copy(use24h = !it.use24h) } }) { Text(if (s.prefs.use24h) "24h" else "12h") }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add city") }, containerColor = Accents.Clock.start, contentColor = Color.White)
        },
    ) { padding ->
        val world = s.world
        if (world == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ToolScaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val mapHeight = (maxHeight * 0.42f).coerceAtMost(maxWidth * 0.62f)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(mapHeight).padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp))) {
                    WorldMap(
                        world = world,
                        clocks = s.clocks,
                        selectedId = s.selectedId,
                        selectedCountry = s.selectedCountry,
                        now = s.now + if (tab == 1) s.plannerOffsetMin * 60_000L else 0L,
                        use24h = s.prefs.use24h,
                        camera = camera,
                        onCountryTap = { vm.selectCountry(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (camera.zoom.value > 1.05f) {
                        FilledTonalIconButton(onClick = { vm.selectCountry(null); scope.launch { camera.animateTo(0f, 0f, 1f) } }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                            Icon(Icons.Rounded.ZoomOutMap, "Whole world")
                        }
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    listOf("Clocks", "Planner").forEachIndexed { i, l ->
                        SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                    }
                }
                AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "clockTab", modifier = Modifier.weight(1f)) { t ->
                    if (t == 0) ClocksList(vm, s, world) else Planner(vm, s)
                }
            }
        }
    }
    if (adding) s.world?.let { w -> AddCitySheet(w, s, onDismiss = { adding = false }) { entry -> vm.add(entry); adding = false } }
}

@Composable
private fun ClocksList(vm: ClockViewModel, s: ClockState, world: World) {
    val local = Instant.ofEpochMilli(s.now).atZone(ZoneId.systemDefault())
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val code = s.selectedCountry
        if (code != null && s.selectedId == null) {
            item(key = "country-$code") { CountryPanel(vm, s, world, code) }
        }
        items(s.clocks, key = { it.id }) { c -> ClockCard(vm, s, c, local, Modifier.animateItem()) }
    }
}

@Composable
private fun CountryPanel(vm: ClockViewModel, s: ClockState, world: World, code: String) {
    val info = world.countries[code]
    val zones = world.zonesByCountry[code].orEmpty()
    val now = Instant.ofEpochMilli(s.now)
    val fmt = DateTimeFormatter.ofPattern(if (s.prefs.use24h) "HH:mm" else "h:mm a")
    Surface(shape = RoundedCornerShape(22.dp), color = Accents.Clock.start.copy(alpha = 0.1f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(flagEmoji(code), fontSize = 28.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(info?.name ?: world.shapeByCode[code]?.name ?: code, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(info?.capital?.takeIf { it.isNotEmpty() }?.let { "Capital: $it" }, info?.subregion?.takeIf { it.isNotEmpty() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.selectCountry(null) }) { Icon(Icons.Rounded.Close, "Close") }
            }
            if (zones.isEmpty()) Text("No time zone data for this territory", style = MaterialTheme.typography.bodySmall)
            for (z in zones.take(12)) {
                val t = now.atZone(zone(z.id))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bouncyClickable {
                    val city = world.cities.filter { it.zone == z.id && it.iso2 == code }.maxByOrNull { it.population }
                    vm.entryForZone(world, z.id, city)?.let(vm::add)
                }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(z.id.substringAfter('/').replace('_', ' ').replace('/', ' · '), style = MaterialTheme.typography.bodyMedium)
                        Text(listOf("UTC${t.offset.id.replace("Z", "+00:00")}", z.comment).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(t.format(fmt), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Add, "Add", tint = Accents.Clock.start)
                }
            }
            if (zones.size > 12) Text("+${zones.size - 12} more zones", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ClockCard(vm: ClockViewModel, s: ClockState, c: ClockEntry, local: ZonedDateTime, modifier: Modifier) {
    val selected = s.selectedId == c.id
    val t = Instant.ofEpochMilli(s.now).atZone(zone(c.zone))
    var menu by remember { mutableStateOf(false) }
    val alt = Sun.altitude(c.lat, c.lon, s.now)
    Surface(
        modifier.fillMaxWidth().animateContentSize().bouncyClickable(pressedScale = 0.98f) { vm.select(c.id) },
        shape = RoundedCornerShape(22.dp),
        color = if (selected) Accents.Clock.start.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnalogClock(t, Modifier.size(54.dp), night = alt < -6, showSeconds = s.prefs.showSeconds)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(flagEmoji(c.country), fontSize = 15.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(c.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${dayLabel(t, local)} · ${offsetLabel(t, local)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        t.format(DateTimeFormatter.ofPattern(if (s.prefs.use24h) "HH:mm" else "h:mm")),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when {
                                alt > 0 -> Icons.Rounded.WbSunny
                                alt > -6 -> Icons.Rounded.WbTwilight
                                else -> Icons.Rounded.DarkMode
                            },
                            null,
                            Modifier.size(14.dp),
                            tint = if (alt > 0) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (s.prefs.use24h) ":%02d".format(t.second) else t.format(DateTimeFormatter.ofPattern("a")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.DeleteOutline, "Options", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) }, onClick = { menu = false; vm.move(c.id, -1) })
                        DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) }, onClick = { menu = false; vm.move(c.id, 1) })
                        DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; vm.remove(c.id) })
                    }
                }
            }
            AnimatedVisibility(selected, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                ClockDetails(c, t, s)
            }
        }
    }
}

@Composable
private fun ClockDetails(c: ClockEntry, t: ZonedDateTime, s: ClockState) {
    val z = zone(c.zone)
    val rules = z.rules
    val now = Instant.ofEpochMilli(s.now)
    val dst = rules.isDaylightSavings(now)
    val next = rules.nextTransition(now)
    val fmt = DateTimeFormatter.ofPattern(if (s.prefs.use24h) "HH:mm" else "h:mm a")
    val day = Sun.day(c.lat, c.lon, t.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + 12 * 3_600_000L)
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            AnalogClock(t, Modifier.size(120.dp), night = Sun.altitude(c.lat, c.lon, s.now) < -6, showSeconds = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")), style = MaterialTheme.typography.titleSmall)
                Detail("Time zone", "${c.zone.replace('_', ' ')} (${z.getDisplayName(TextStyle.SHORT, Locale.getDefault())})")
                Detail("UTC offset", "UTC${t.offset.id.replace("Z", "+00:00")}${if (dst) " · daylight saving" else ""}")
                when (day) {
                    is Sun.Day.Normal -> {
                        Detail("Sunrise · sunset", "${Instant.ofEpochMilli(day.sunriseUtcMillis).atZone(z).format(fmt)} · ${Instant.ofEpochMilli(day.sunsetUtcMillis).atZone(z).format(fmt)}")
                        val len = Duration.ofMillis(day.sunsetUtcMillis - day.sunriseUtcMillis)
                        Detail("Daylight", "${len.toHours()}h ${len.toMinutes() % 60}m")
                    }
                    Sun.Day.PolarDay -> Detail("Sun", "Up all day (midnight sun)")
                    Sun.Day.PolarNight -> Detail("Sun", "Below the horizon all day (polar night)")
                }
                if (next != null) {
                    val change = (next.offsetAfter.totalSeconds - next.offsetBefore.totalSeconds) / 60
                    Detail("Next change", "${next.instant.atZone(z).format(DateTimeFormatter.ofPattern("d MMM yyyy"))}: clocks go ${if (change > 0) "forward" else "back"} ${abs(change)} min")
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun AnalogClock(t: ZonedDateTime, modifier: Modifier, night: Boolean, showSeconds: Boolean) {
    val face = if (night) Color(0xFF1E2540) else Color.White
    val ink = if (night) Color(0xFFE2E8F0) else Color(0xFF1F2937)
    val accent = Accents.Clock.start
    Canvas(modifier.clip(CircleShape).background(face).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        for (i in 0 until 12) {
            val a = i * PI / 6
            val inner = if (i % 3 == 0) 0.76f else 0.84f
            drawLine(ink.copy(alpha = if (i % 3 == 0) 0.9f else 0.4f), c + Offset((sin(a) * r * inner).toFloat(), (-cos(a) * r * inner).toFloat()), c + Offset((sin(a) * r * 0.92f).toFloat(), (-cos(a) * r * 0.92f).toFloat()), r * 0.035f, cap = StrokeCap.Round)
        }
        val sec = t.second + t.nano / 1e9
        val min = t.minute + sec / 60
        val hr = (t.hour % 12) + min / 60
        fun hand(angle: Double, len: Float, width: Float, color: Color) {
            drawLine(color, c, c + Offset((sin(angle) * r * len).toFloat(), (-cos(angle) * r * len).toFloat()), width, cap = StrokeCap.Round)
        }
        hand(hr / 12 * 2 * PI, 0.5f, r * 0.09f, ink)
        hand(min / 60 * 2 * PI, 0.72f, r * 0.06f, ink)
        if (showSeconds) hand(t.second / 60.0 * 2 * PI, 0.8f, r * 0.025f, accent)
        drawCircle(accent, r * 0.07f, c)
    }
}

@Composable
private fun Planner(vm: ClockViewModel, s: ClockState) {
    val base = Instant.ofEpochMilli(s.now).truncatedTo(ChronoUnit.MINUTES).plusSeconds(s.plannerOffsetMin * 60L)
    val local = base.atZone(ZoneId.systemDefault())
    val fmt = DateTimeFormatter.ofPattern(if (s.prefs.use24h) "HH:mm" else "h:mm a")
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(local.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " · " + local.format(fmt) + " here", style = MaterialTheme.typography.titleMedium)
                            Text(if (s.plannerOffsetMin == 0) "Now" else offsetText(s.plannerOffsetMin), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (s.plannerOffsetMin != 0) TextButton(onClick = { vm.setPlannerOffset(0) }) { Text("Now") }
                    }
                    Slider(
                        s.plannerOffsetMin.toFloat(),
                        { vm.setPlannerOffset(((it / 15).toInt()) * 15) },
                        valueRange = -24 * 60f..48 * 60f,
                    )
                    Text("Drag to see what time it is everywhere at that moment. Green hours are typical working hours.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(s.clocks, key = { it.id }) { c ->
            val t = base.atZone(zone(c.zone))
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(flagEmoji(c.country), fontSize = 15.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(c.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${dayLabel(t, local)} · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(t.format(fmt), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    HourStrip(t)
                }
            }
        }
    }
}

private fun offsetText(min: Int): String {
    val h = abs(min) / 60
    val m = abs(min) % 60
    val txt = if (m == 0) "${h}h" else "${h}h ${m}m"
    return if (min > 0) "In $txt" else "$txt ago"
}

/** 24 hour cells around the chosen moment, coloured by time of day, with the moment marked. */
@Composable
private fun HourStrip(t: ZonedDateTime) {
    val work = Color(0xFF22C55E)
    val evening = Color(0xFF60A5FA)
    val nightC = Color(0xFF334155)
    Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(8.dp))) {
        val w = size.width / 24
        val startHour = t.minusHours(12).truncatedTo(ChronoUnit.HOURS)
        for (i in 0 until 24) {
            val h = startHour.plusHours(i.toLong()).hour
            val color = when (h) {
                in 9..16 -> work
                in 7..8, in 17..20 -> evening
                else -> nightC
            }
            drawRect(color.copy(alpha = if (h in 9..16) 0.85f else 0.55f), Offset(i * w + 1, 0f), androidx.compose.ui.geometry.Size(w - 2, size.height))
        }
        val frac = (t.minute + t.second / 60f) / 60f
        val x = 12 * w + frac * w
        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
    }
}

@Composable
private fun AddCitySheet(world: World, s: ClockState, onDismiss: () -> Unit, onAdd: (ClockEntry) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val cities = remember(q) {
        if (q.length < 2) world.cities.take(30)
        else world.cities.asSequence().filter { it.search.contains(q) }.take(60).toList()
    }
    val zones = remember(q) { if (q.length < 2) emptyList() else world.zones.filter { it.id.lowercase().replace('_', ' ').contains(q) }.take(20) }
    val now = Instant.ofEpochMilli(s.now)
    val fmt = DateTimeFormatter.ofPattern(if (s.prefs.use24h) "HH:mm" else "h:mm a")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search ${world.cities.size} cities, countries or time zones") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
            LazyColumn(Modifier.fillMaxWidth().height(520.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(cities, key = { "c-${it.name}-${it.lat}-${it.lon}" }) { c ->
                    val t = now.atZone(zone(c.zone))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).bouncyClickable(pressedScale = 0.98f) {
                        onAdd(ClockEntry(zone = c.zone, label = c.name, country = c.iso2, lat = c.lat, lon = c.lon))
                    }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(flagEmoji(c.iso2), fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Text(listOf(c.province, c.country).filter { it.isNotEmpty() && it != c.name }.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(t.format(fmt), style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (zones.isNotEmpty()) {
                    item { Text("Time zones", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(8.dp)) }
                    items(zones, key = { "z-${it.id}" }) { z ->
                        val t = now.atZone(zone(z.id))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).bouncyClickable(pressedScale = 0.98f) {
                            onAdd(ClockEntry(zone = z.id, label = z.id.substringAfterLast('/').replace('_', ' '), country = z.countries.firstOrNull() ?: "", lat = z.lat, lon = z.lon))
                        }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Public, null, tint = Accents.Clock.start)
                            Spacer(Modifier.width(12.dp))
                            Text(z.id.replace('_', ' '), Modifier.weight(1f))
                            Text(t.format(fmt), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}
