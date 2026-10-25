package com.vitmap.app.feature.browser

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitmap.app.ui.components.copyToClipboard
import com.vitmap.app.ui.components.shareText
import com.vitmap.app.ui.theme.MonoStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

private val inspectorJson = Json { ignoreUnknownKeys = true }

/** evaluateJavascript hands back a JSON-encoded value; unwrap string results. */
fun decodeJsResult(raw: String?): String? {
    if (raw == null || raw == "null" || raw == "undefined") return null
    return runCatching { (inspectorJson.parseToJsonElement(raw) as? JsonPrimitive)?.content }.getOrNull() ?: raw
}

fun WebView.runInspector(script: String, onResult: (String?) -> Unit = {}) {
    evaluateJavascript(InspectorJs.install) { evaluateJavascript(script) { r -> onResult(decodeJsResult(r)) } }
}

data class DomNode(val index: Int, val tag: String, val id: String, val cls: String, val childCount: Int, val children: List<DomNode>, val text: String?)

private fun parseNode(o: JsonObject): DomNode = DomNode(
    index = o["i"]?.jsonPrimitive?.int ?: 0,
    tag = o["t"]?.jsonPrimitive?.content ?: "",
    id = o["id"]?.jsonPrimitive?.content ?: "",
    cls = o["c"]?.jsonPrimitive?.content ?: "",
    childCount = o["n"]?.jsonPrimitive?.int ?: 0,
    children = (o["k"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseNode) } ?: emptyList(),
    text = o["x"]?.jsonPrimitive?.content,
)

@Composable
fun InspectorPanel(tab: BrowserTab, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val wv = tab.webView
    var section by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    val sections = listOf("Elements", "Styles", "Console", "Network", "Source", "Storage")

    LaunchedEffect(picking, wv) { wv?.runInspector(InspectorJs.callRaw("pick", picking.toString())) }
    LaunchedEffect(Unit) { if (tab.inspected == null) picking = true }

    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 12.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconToggleButton(checked = picking, onCheckedChange = { picking = it }) { Icon(Icons.Rounded.TouchApp, "Pick element") }
                Text(
                    tab.inspected?.selector ?: if (picking) "Tap an element on the page" else "Inspector",
                    style = MonoStyle.copy(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconButton(onClick = { wv?.runInspector(InspectorJs.call("parent")) }, enabled = tab.inspected != null) { Icon(Icons.Rounded.ArrowUpward, "Parent element") }
                IconButton(onClick = { wv?.runInspector(InspectorJs.call("remove")) }, enabled = tab.inspected != null) { Icon(Icons.Rounded.Delete, "Delete element") }
                IconButton(onClick = {
                    wv?.runInspector(InspectorJs.callRaw("pick", "false"))
                    wv?.runInspector(InspectorJs.call("hide"))
                    onClose()
                }) { Icon(Icons.Rounded.Close, "Close inspector") }
            }
            PrimaryTabRow(selectedTabIndex = section, containerColor = Color.Transparent) {
                sections.forEachIndexed { i, s -> Tab(selected = section == i, onClick = { section = i }, text = { Text(s, maxLines = 1, style = MaterialTheme.typography.labelMedium) }) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    0 -> ElementsSection(tab)
                    1 -> StylesSection(tab)
                    2 -> ConsoleSection(tab)
                    3 -> NetworkSection(tab)
                    4 -> SourceSection(tab)
                    else -> StorageSection(tab)
                }
            }
        }
    }
}

@Composable
private fun ElementsSection(tab: BrowserTab) {
    val wv = tab.webView
    var root by remember { mutableStateOf<DomNode?>(null) }
    var expanded by remember { mutableStateOf(setOf<Int>()) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) } // (kind, initial)
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, tab.url) {
        wv?.runInspector(InspectorJs.callRaw("tree", "5")) { r ->
            root = r?.let { runCatching { parseNode(inspectorJson.parseToJsonElement(it) as JsonObject) }.getOrNull() }
            expanded = setOf(0, 1)
        }
    }
    val flat = remember(root, expanded) {
        val out = ArrayList<Pair<DomNode, Int>>()
        fun walk(n: DomNode, depth: Int) {
            out += n to depth
            if (n.index in expanded) n.children.forEach { walk(it, depth + 1) }
        }
        root?.let { walk(it, 0) }
        out
    }
    Column(Modifier.fillMaxSize()) {
        tab.inspected?.let { el ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(8.dp).animateContentSize()) {
                Column(Modifier.padding(10.dp)) {
                    Text(buildTagLabel(el.tag, el.id, el.classes), style = MonoStyle.copy(fontSize = 12.sp))
                    Text(el.box, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { editing = "text" to el.text }) { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)); Text(" Text") }
                        TextButton(onClick = { editing = "html" to el.html }) { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)); Text(" HTML") }
                        TextButton(onClick = { editing = "attr" to "" }) { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)); Text(" Attribute") }
                        val context = LocalContext.current
                        TextButton(onClick = { copyToClipboard(context, el.html, "HTML") }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Text(" Copy") }
                    }
                    if (el.attrs.isNotEmpty()) {
                        for ((k, v) in el.attrs.entries.take(12)) {
                            Text(
                                buildAnnotatedString {
                                    pushStyle(SpanStyle(color = Color(0xFFE5A04B))); append(k); pop()
                                    append("=\"")
                                    pushStyle(SpanStyle(color = Color(0xFF6CC28F))); append(v.take(120)); pop()
                                    append("\"")
                                },
                                style = MonoStyle.copy(fontSize = 11.sp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { editing = "attr:$k" to v },
                            )
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("DOM", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, "Refresh tree") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(flat, key = { it.first.index }) { (node, depth) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            wv?.runInspector(InspectorJs.callRaw("selectNode", node.index.toString()))
                            if (node.childCount > 0) {
                                if (node.index in expanded) expanded = expanded - node.index
                                else {
                                    expanded = expanded + node.index
                                    if (node.children.isEmpty()) {
                                        wv?.runInspector(InspectorJs.callRaw("subtree", "${node.index}, 3")) { r ->
                                            val sub = r?.let { runCatching { parseNode(inspectorJson.parseToJsonElement(it) as JsonObject) }.getOrNull() } ?: return@runInspector
                                            root = root?.let { replaceNode(it, sub) }
                                        }
                                    }
                                }
                            }
                        }
                        .padding(start = (8 + depth * 14).dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (node.childCount > 0) {
                        Icon(
                            if (node.index in expanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            null,
                            Modifier.size(16.dp),
                        )
                    } else Spacer(Modifier.width(16.dp))
                    Text(buildTagLabel(node.tag, node.id, node.cls), style = MonoStyle.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    node.text?.takeIf { it.isNotBlank() }?.let {
                        Text("  $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    editing?.let { (kind, initial) ->
        var value by remember(kind) { mutableStateOf(initial) }
        var name by remember(kind) { mutableStateOf(kind.removePrefix("attr:").takeIf { kind.startsWith("attr:") } ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(when { kind == "text" -> "Edit text"; kind == "html" -> "Edit HTML"; else -> "Edit attribute" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kind.startsWith("attr")) OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, textStyle = MonoStyle)
                    OutlinedTextField(value, { value = it }, label = { Text("Value") }, textStyle = MonoStyle.copy(fontSize = 12.sp), modifier = Modifier.heightIn(min = 120.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val script = when {
                        kind == "text" -> InspectorJs.call("setText", value)
                        kind == "html" -> InspectorJs.call("setHtml", value)
                        else -> InspectorJs.call("setAttr", name, value)
                    }
                    wv?.runInspector(script)
                    editing = null
                }) { Text("Apply") }
            },
            dismissButton = {
                if (kind.startsWith("attr:")) TextButton(onClick = {
                    wv?.runInspector(InspectorJs.callRaw("setAttr", "${InspectorJs.quote(name)}, null"))
                    editing = null
                }) { Text("Remove") } else TextButton(onClick = { editing = null }) { Text("Cancel") }
            },
        )
    }
}

private fun replaceNode(n: DomNode, sub: DomNode): DomNode =
    if (n.index == sub.index) sub.copy(index = n.index) else n.copy(children = n.children.map { replaceNode(it, sub) })

private fun buildTagLabel(tag: String, id: String, cls: String): AnnotatedString = buildAnnotatedString {
    pushStyle(SpanStyle(color = Color(0xFFC792EA))); append("<$tag"); pop()
    if (id.isNotBlank()) { pushStyle(SpanStyle(color = Color(0xFFFFCB6B))); append(" #$id"); pop() }
    if (cls.isNotBlank()) { pushStyle(SpanStyle(color = Color(0xFF82AAFF))); append(" ." + cls.trim().replace(Regex("\\s+"), ".")); pop() }
    pushStyle(SpanStyle(color = Color(0xFFC792EA))); append(">"); pop()
}

@Composable
private fun StylesSection(tab: BrowserTab) {
    val el = tab.inspected
    if (el == null) {
        Text("Pick an element to see its styles.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var inline by remember(el.selector, el.inline) { mutableStateOf(el.inline) }
    LazyColumn(contentPadding = PaddingValues(8.dp)) {
        item {
            Text("element.style", style = MonoStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = inline,
                onValueChange = { inline = it },
                placeholder = { Text("color: tomato; padding: 8px") },
                textStyle = MonoStyle.copy(fontSize = 12.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { tab.webView?.runInspector(InspectorJs.call("setStyle", inline)) }),
                trailingIcon = { IconButton(onClick = { tab.webView?.runInspector(InspectorJs.call("setStyle", inline)) }) { Icon(Icons.Rounded.PlayArrow, "Apply") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            Text("Computed", style = MaterialTheme.typography.labelLarge)
        }
        items(el.styles.entries.toList(), key = { it.key }) { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(k, style = MonoStyle.copy(fontSize = 11.sp), color = Color(0xFFE5A04B), modifier = Modifier.width(140.dp))
                Text(v, style = MonoStyle.copy(fontSize = 11.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConsoleSection(tab: BrowserTab) {
    var code by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(tab.console.size) { if (tab.console.isNotEmpty()) listState.animateScrollToItem(tab.console.size - 1) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(8.dp)) {
            if (tab.console.isEmpty()) item { Text("Console output from the page appears here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            itemsIndexed(tab.console) { _, line ->
                val color = when (line.level) {
                    "error" -> Color(0xFFFF6B6B)
                    "warning" -> Color(0xFFFFC145)
                    "result" -> Color(0xFF6CC28F)
                    "input" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(line.message, style = MonoStyle.copy(fontSize = 12.sp), color = color)
                    if (line.source.isNotBlank()) Text(line.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                placeholder = { Text("document.title") },
                textStyle = MonoStyle.copy(fontSize = 12.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { run(tab, code); code = "" }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { run(tab, code); code = "" }) { Icon(Icons.Rounded.PlayArrow, "Run") }
            IconButton(onClick = { tab.console.clear() }) { Icon(Icons.Rounded.Delete, "Clear console") }
        }
    }
}

private fun run(tab: BrowserTab, code: String) {
    if (code.isBlank()) return
    tab.console += ConsoleLine("input", "› $code", "")
    tab.webView?.evaluateJavascript(code) { r -> tab.console += ConsoleLine("result", "← ${r ?: "undefined"}", "") }
}

@Composable
private fun NetworkSection(tab: BrowserTab) {
    var filter by remember { mutableStateOf<String?>(null) }
    val types = listOf("document", "script", "stylesheet", "image", "font", "fetch", "media", "other")
    val list = tab.requests.filter { filter == null || it.type == filter || (filter == "blocked" && it.blocked) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${tab.requests.size}") })
            FilterChip(selected = filter == "blocked", onClick = { filter = "blocked" }, label = { Text("Blocked ${tab.blockedCount}") })
            for (t in types) {
                val n = tab.requests.count { it.type == t }
                if (n > 0) FilterChip(selected = filter == t, onClick = { filter = t }, label = { Text("$t $n") })
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            items(list.asReversed()) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.method, style = MonoStyle.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(40.dp))
                    Text(r.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(70.dp))
                    Text(
                        r.url,
                        style = MonoStyle.copy(fontSize = 11.sp),
                        color = if (r.blocked) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val tagRegex = Regex("</?[a-zA-Z][\\w:-]*|/?>")
private val attrRegex = Regex("\\s([a-zA-Z_:][\\w:.-]*)=")
private val stringRegex = Regex("\"[^\"]*\"|'[^']*'")

private fun highlight(line: String): AnnotatedString = buildAnnotatedString {
    append(line)
    for (m in stringRegex.findAll(line)) addStyle(SpanStyle(color = Color(0xFF6CC28F)), m.range.first, m.range.last + 1)
    for (m in attrRegex.findAll(line)) addStyle(SpanStyle(color = Color(0xFFE5A04B)), m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1)
    for (m in tagRegex.findAll(line)) addStyle(SpanStyle(color = Color(0xFFC792EA)), m.range.first, m.range.last + 1)
}

@Composable
private fun SourceSection(tab: BrowserTab) {
    val context = LocalContext.current
    var source by remember { mutableStateOf<String?>(null) }
    var wrap by remember { mutableStateOf(false) }
    LaunchedEffect(tab.url) { tab.webView?.evaluateJavascript(InspectorJs.SOURCE) { r -> source = decodeJsResult(r) } }
    val lines = remember(source) { source?.take(200_000)?.lines() ?: emptyList() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${lines.size} lines", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            FilterChip(selected = wrap, onClick = { wrap = !wrap }, label = { Text("Wrap") })
            IconButton(onClick = { source?.let { copyToClipboard(context, it, "source") } }) { Icon(Icons.Rounded.ContentCopy, "Copy source") }
            IconButton(onClick = { source?.let { shareText(context, it, "Page source") } }) { Icon(Icons.Rounded.Share, "Share source") }
        }
        val hScroll = rememberScrollState()
        LazyColumn(Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll)), contentPadding = PaddingValues(8.dp)) {
            itemsIndexed(lines) { i, line ->
                Row {
                    Text("${i + 1}", style = MonoStyle.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(38.dp))
                    Text(highlight(line), style = MonoStyle.copy(fontSize = 11.sp), softWrap = wrap)
                }
            }
        }
    }
}

@Composable
private fun StorageSection(tab: BrowserTab) {
    var local by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var info by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val cookies = remember(tab.url) {
        CookieManager.getInstance().getCookie(tab.url)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
    LaunchedEffect(tab.url) {
        tab.webView?.evaluateJavascript(InspectorJs.STORAGE) { r ->
            local = decodeJsResult(r)?.let { s -> runCatching { (inspectorJson.parseToJsonElement(s) as JsonObject).mapValues { (it.value as? JsonPrimitive)?.content ?: "" } }.getOrNull() } ?: emptyMap()
        }
        tab.webView?.evaluateJavascript(InspectorJs.PAGE_INFO) { r ->
            info = decodeJsResult(r)?.let { s -> runCatching { (inspectorJson.parseToJsonElement(s) as JsonObject).mapValues { (it.value as? JsonPrimitive)?.content ?: "" } }.getOrNull() } ?: emptyMap()
        }
    }
    LazyColumn(contentPadding = PaddingValues(8.dp)) {
        item { Text("Page", style = MaterialTheme.typography.labelLarge) }
        items(info.entries.toList()) { (k, v) -> KeyValue(k, v) }
        item { Text("Cookies (${cookies.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp)) }
        items(cookies) { c -> KeyValue(c.substringBefore('='), c.substringAfter('=', "")) }
        item { Text("localStorage (${local.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp)) }
        items(local.entries.toList()) { (k, v) -> KeyValue(k, v) }
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).padding(vertical = 3.dp)) {
        Text(k, style = MonoStyle.copy(fontSize = 11.sp), color = Color(0xFFE5A04B), modifier = Modifier.width(130.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(v, style = MonoStyle.copy(fontSize = 11.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
