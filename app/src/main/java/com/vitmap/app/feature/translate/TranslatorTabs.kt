package com.vitmap.app.feature.translate

import com.vitmap.app.ui.components.PackDialog
import com.vitmap.app.data.packs.Pack
import androidx.compose.material.icons.rounded.DocumentScanner
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.ui.components.PackGate
import com.vitmap.app.ml.OcrScript
import com.vitmap.app.ml.OcrMissingException
import com.vitmap.app.ml.Ocr
import android.Manifest
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.mlkit.nl.translate.TranslateLanguage
import com.vitmap.app.ui.components.CameraHandle
import com.vitmap.app.ui.components.CameraView
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.PermissionGate
import com.vitmap.app.ui.components.copyToClipboard
import com.vitmap.app.ui.components.loadBitmap
import com.vitmap.app.ui.components.readable
import com.vitmap.app.ui.components.shareText
import kotlinx.coroutines.launch

private data class OverlayBlock(val rect: Rect, val original: String, val translated: String, val lines: Int)

@Composable
fun PhotoTranslateTab(vm: TranslatorViewModel, s: TranslatorState, onUseText: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var blocks by remember { mutableStateOf<List<OverlayBlock>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf(Ocr.scriptFor(s.effectiveSource)) }
    val handle = remember { CameraHandle() }

    fun process(b: Bitmap) {
        bitmap = b
        blocks = emptyList()
        scope.launch {
            status = "Reading text…"
            val text = try {
                vm.recognize(b, script)
            } catch (e: OcrMissingException) {
                status = "Download the ${e.script.label} text recognition model first"
                return@launch
            } catch (e: Exception) {
                null
            }
            if (text == null || text.textBlocks.isEmpty()) {
                status = "No text found — try getting closer or another script"
                return@launch
            }
            val src = s.effectiveSource ?: vm.detect(text.text) ?: TranslateLanguage.ENGLISH
            status = "Translating to ${vm.langName(s.target)}…"
            val out = ArrayList<OverlayBlock>()
            for (block in text.textBlocks) {
                val rect = block.boundingBox ?: continue
                val original = block.lines.joinToString(" ") { it.text }
                val translated = try {
                    vm.translate(original, src, s.target)
                } catch (e: ModelMissingException) {
                    status = "Download ${e.languages.joinToString { vm.langName(it) }} to translate"
                    original
                } catch (e: Exception) {
                    original
                }
                out += OverlayBlock(rect, original, translated, block.lines.size)
                blocks = out.toList()
            }
            if (status?.startsWith("Translating") == true) status = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { loadBitmap(context, uri, 2400)?.readable()?.let { process(it) } }
    }

    val bmp = bitmap
    if (bmp == null) {
        val scriptChips: @Composable (Modifier) -> Unit = { m ->
            Row(m.padding(10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (sc in OcrScript.entries) {
                    FilterChip(selected = script == sc, onClick = { script = sc }, label = { Text(sc.native) })
                }
            }
        }
        PackGate(Ocr.pack(script), accent = Accents.Translator, icon = Icons.Rounded.DocumentScanner, header = { scriptChips(Modifier) }) {
        PermissionGate(Manifest.permission.CAMERA, Icons.Rounded.PhotoCamera, "Camera access", "Point the camera at signs, menus or documents to translate them. Text is read on your device.") {
            Box(Modifier.fillMaxSize()) {
                CameraView(Modifier.fillMaxSize(), handle = handle)
                scriptChips(Modifier.align(Alignment.TopCenter))
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.AddPhotoAlternate, "Pick photo")
                    }
                    LargeFloatingActionButton(onClick = { handle.snapshot()?.let { process(it.readable()) } }, shape = CircleShape) {
                        Icon(Icons.Rounded.Camera, "Capture", Modifier.size(36.dp))
                    }
                    Spacer(Modifier.size(56.dp))
                }
            }
        }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(20.dp))) {
            val scale = constraints.maxWidth.toFloat() / bmp.width
            val density = LocalDensity.current
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (!showOriginal) {
                for (b in blocks) {
                    val appear = remember(b) { Animatable(0f) }
                    LaunchedEffect(b) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 300f)) }
                    val w = with(density) { (b.rect.width() * scale).toDp() }
                    val h = with(density) { (b.rect.height() * scale).toDp() }
                    val perLine = b.rect.height() * scale / b.lines.coerceAtLeast(1)
                    val fontSize = with(density) { (perLine * 0.62f).coerceIn(9f * density.density, 40f * density.density).toSp() }
                    Box(
                        Modifier
                            .offset { IntOffset((b.rect.left * scale).toInt(), (b.rect.top * scale).toInt()) }
                            .size(w, h)
                            .graphicsLayer { alpha = appear.value; scaleX = 0.9f + 0.1f * appear.value; scaleY = scaleX }
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.92f))
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(b.translated, color = Color(0xFF111111), fontSize = fontSize, lineHeight = fontSize * 1.05f, maxLines = b.lines.coerceAtLeast(1) + 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        status?.let { st ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (st.endsWith("…")) LinearProgressIndicator(Modifier.width(60.dp))
                Spacer(Modifier.width(10.dp))
                Text(st, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { showOriginal = !showOriginal }) {
                Icon(if (showOriginal) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null)
                Spacer(Modifier.width(6.dp))
                Text(if (showOriginal) "Show translation" else "Show original")
            }
            FilledTonalButton(onClick = { bitmap = null }) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("New photo")
            }
        }
        if (blocks.isNotEmpty()) {
            val originalText = blocks.joinToString("\n") { it.original }
            val translatedText = blocks.joinToString("\n") { it.translated }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(14.dp)) {
                    Text("Recognised text", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(originalText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Text("Translation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(translatedText, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        IconButton(onClick = { copyToClipboard(context, translatedText, "translation") }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                        IconButton(onClick = { shareText(context, translatedText) }) { Icon(Icons.Rounded.Share, "Share") }
                        IconButton(onClick = { vm.speak(translatedText, s.target, "photo") }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Listen") }
                        TextButton(onClick = { onUseText(originalText) }) {
                            Icon(Icons.Rounded.Edit, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Edit as text")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun VoiceTab(vm: TranslatorViewModel, s: TranslatorState) {
    var conversation by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    PermissionGate(Manifest.permission.RECORD_AUDIO, Icons.Rounded.Mic, "Microphone access", "Speak and hear the translation. Recognition runs on your device when an offline speech pack is installed.") {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!conversation, { conversation = false; vm.stopListening() }, SegmentedButtonDefaults.itemShape(0, 2), icon = { Icon(Icons.Rounded.Mic, null) }) { Text("Dictate") }
                SegmentedButton(conversation, { conversation = true; vm.stopListening() }, SegmentedButtonDefaults.itemShape(1, 2), icon = { Icon(Icons.Rounded.Forum, null) }) { Text("Conversation") }
            }
            s.voice.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
            if (!conversation) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))
                    AnimatedContent(
                        targetState = if (s.voice.listening) s.voice.partial.ifBlank { "Listening…" } else s.input.ifBlank { "Tap the mic and speak in ${vm.langName(s.effectiveSource ?: TranslateLanguage.ENGLISH)}" },
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                        label = "partial",
                    ) { t -> Text(t, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 12.dp)) }
                    Spacer(Modifier.height(12.dp))
                    PulsingMic(s.voice.listening, s.voice.level, onClick = { if (s.voice.listening) vm.stopListening() else vm.startListening(0, conversation = false) })
                    AnimatedVisibility(s.output.isNotBlank() && !s.voice.listening, enter = slideInVertically { it / 2 } + fadeIn()) {
                        OutputCard(
                            text = s.output, loading = s.translating, speaking = s.speaking == "output",
                            onSpeak = { vm.speak(s.output, s.target, "output") },
                            onCopy = { copyToClipboard(context, s.output, "translation") },
                            onShare = { shareText(context, s.output) },
                        )
                    }
                }
            } else {
                ConversationView(vm, s, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ConversationView(vm: TranslatorViewModel, s: TranslatorState, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(s.conversation.size) { if (s.conversation.isNotEmpty()) listState.animateScrollToItem(s.conversation.size - 1) }
    val srcName = vm.langName(s.effectiveSource ?: TranslateLanguage.ENGLISH)
    val tgtName = vm.langName(s.target)
    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.conversation.isEmpty()) item {
                EmptyState(Icons.Rounded.Forum, "Talk face to face", "Each person taps their language. What they say is translated and read aloud for the other.")
            }
            itemsIndexed(s.conversation) { i, b ->
                val mine = b.side == 0
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f)) }
                Row(Modifier.fillMaxWidth().graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * 40f }, horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (mine) 20.dp else 4.dp, bottomEnd = if (mine) 4.dp else 20.dp),
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(b.translated, style = MaterialTheme.typography.titleMedium)
                            Text(b.original, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { vm.speak(b.translated, if (mine) s.target else s.effectiveSource, "bubble-$i") }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Listen", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        if (s.voice.listening && s.voice.partial.isNotBlank()) Text(s.voice.partial, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            for ((side, name) in listOf(1 to tgtName, 0 to srcName)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val active = s.voice.listening && s.voice.side == side
                    PulsingMic(active, if (active) s.voice.level else 0f, onClick = { if (active) vm.stopListening() else vm.startListening(side, conversation = true) }, size = 64)
                    Text(name, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (s.conversation.isNotEmpty()) TextButton(onClick = vm::clearConversation, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Clear conversation") }
    }
}

@Composable
fun DocsTab(vm: TranslatorViewModel, s: TranslatorState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<LoadedDocument?>(null) }
    var translated by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var showOriginal by remember { mutableStateOf(false) }
    var needPack by remember { mutableStateOf<Pack?>(null) }
    needPack?.let { PackDialog(it, onDismiss = { needPack = null }, accent = Accents.Translator) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(translated.joinToString("\n\n").toByteArray()) }
            }
            status = "Saved"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            translated = emptyList()
            doc = null
            status = "Opening…"
            val d = try {
                DocumentText.load(context, uri, ocr = vm::ocrText, onProgress = { status = it })
            } catch (e: OcrMissingException) {
                needPack = Ocr.pack(e.script)
                null
            } catch (e: Exception) {
                null
            }
            doc = d
            status = when {
                needPack != null && d == null -> "This PDF needs text recognition. Download it, then choose the file again."
                d == null -> "Couldn't read that file"
                d.paragraphs.isEmpty() -> "No text found in the document"
                else -> d.note
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val d = doc
        if (d == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.Description, "Translate a document", "Plain text, Markdown, subtitles (SRT/VTT), HTML, Word (.docx), OpenDocument (.odt) and PDF — scanned PDFs are read with on-device OCR.") {
                    Button(onClick = {
                        picker.launch(arrayOf("text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.oasis.opendocument.text", "application/x-subrip", "application/octet-stream"))
                    }) { Text("Choose document") }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally)) }
            return@Column
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${d.paragraphs.size} paragraphs · ${d.paragraphs.sumOf { it.length }} characters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { doc = null; translated = emptyList(); status = null }) { Text("Change") }
            }
        }
        if (translated.isEmpty() && status?.endsWith("…") != true) {
            Button(onClick = {
                scope.launch {
                    val sample = d.paragraphs.take(5).joinToString(" ")
                    val src = s.effectiveSource ?: vm.detect(sample)
                    if (src == null) {
                        status = "Couldn't detect the language — choose it above"
                        return@launch
                    }
                    val out = ArrayList<String>()
                    for ((i, p) in d.paragraphs.withIndex()) {
                        status = "Translating ${i + 1} of ${d.paragraphs.size}…"
                        progress = (i + 1f) / d.paragraphs.size
                        val t = try {
                            vm.translate(p, src, s.target)
                        } catch (e: ModelMissingException) {
                            status = "Download ${e.languages.joinToString { vm.langName(it) }} first"
                            return@launch
                        }
                        out += t
                        translated = out.toList()
                    }
                    status = "Done"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Translate to ${vm.langName(s.target)}") }
        }
        status?.let { st ->
            Column {
                if (st.startsWith("Translating") || st.startsWith("Reading")) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(st, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (translated.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !showOriginal, onClick = { showOriginal = false }, label = { Text("Translation") })
                FilterChip(selected = showOriginal, onClick = { showOriginal = true }, label = { Text("Side by side") })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { copyToClipboard(context, translated.joinToString("\n\n"), "translation") }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                IconButton(onClick = { shareText(context, translated.joinToString("\n\n")) }) { Icon(Icons.Rounded.Share, "Share") }
                IconButton(onClick = { saver.launch(d.name.substringBeforeLast('.') + " (${s.target}).txt") }) { Icon(Icons.Rounded.Save, "Save") }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(d.paragraphs) { i, p ->
                val t = translated.getOrNull(i)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        if (t == null || showOriginal) Text(p, style = MaterialTheme.typography.bodyMedium, color = if (t != null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                        if (t != null) {
                            if (showOriginal) Spacer(Modifier.height(6.dp))
                            Text(t, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
