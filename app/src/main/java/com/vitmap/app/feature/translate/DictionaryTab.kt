package com.vitmap.app.feature.translate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.mlkit.nl.translate.TranslateLanguage
import com.vitmap.app.data.packs.PackCatalog
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.PackGate
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.ui.components.SectionTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DictionaryTab(vm: TranslatorViewModel, s: TranslatorState) {
    PackGate(PackCatalog.dictionary, accent = Accents.Translator, icon = Icons.AutoMirrored.Rounded.MenuBook) {
        DictionaryContent(vm, s)
    }
}

@Composable
private fun DictionaryContent(vm: TranslatorViewModel, s: TranslatorState) {
    val scope = rememberCoroutineScope()
    val initial = s.input.trim().takeIf { it.isNotEmpty() && !it.contains(' ') } ?: ""
    var query by rememberSaveable { mutableStateOf(initial) }
    var entry by remember { mutableStateOf<Entry?>(null) }
    var loading by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf<String?>(null) }
    var via by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var translateDefs by rememberSaveable { mutableStateOf(false) }
    var translatedDefs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun lookup(word: String) {
        query = word
        suggestions = emptyList()
        scope.launch {
            loading = true
            notFound = null
            via = null
            var e = runCatching { vm.dictionary.lookup(word) }.getOrNull()
            // Words in other languages are looked up through their English translation.
            val src = s.effectiveSource ?: vm.detect(word)
            if (e == null && src != null && src != TranslateLanguage.ENGLISH) {
                val english = runCatching { vm.translate(word, src, TranslateLanguage.ENGLISH) }.getOrNull()
                if (english != null && !english.equals(word, true)) {
                    e = runCatching { vm.dictionary.lookup(english) }.getOrNull()
                    if (e != null) via = english
                }
            }
            entry = e
            loading = false
            if (e == null) notFound = word
        }
    }

    LaunchedEffect(Unit) {
        if (initial.isNotEmpty()) lookup(initial)
    }
    LaunchedEffect(query) {
        delay(220)
        if (entry?.word != query) suggestions = runCatching { vm.dictionary.suggest(query) }.getOrDefault(emptyList())
    }
    LaunchedEffect(entry, translateDefs, s.target) {
        val e = entry ?: return@LaunchedEffect
        translatedDefs = emptyMap()
        if (!translateDefs || s.target == TranslateLanguage.ENGLISH) return@LaunchedEffect
        val map = HashMap<String, String>()
        for (sense in e.senses.take(12)) {
            val t = runCatching { vm.translate(sense.definition, TranslateLanguage.ENGLISH, s.target) }.getOrNull() ?: break
            map[sense.definition] = t
            translatedDefs = map.toMap()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Look up a word") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; entry = null; notFound = null }) { Icon(Icons.Rounded.Close, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) lookup(query.trim()) }),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        AnimatedVisibility(suggestions.isNotEmpty() && entry?.word != query, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (w in suggestions) SuggestionChip(onClick = { lookup(w) }, label = { Text(w) })
            }
        }
        if (s.target != TranslateLanguage.ENGLISH) {
            FilterChip(
                selected = translateDefs,
                onClick = { translateDefs = !translateDefs },
                label = { Text("Show definitions in ${vm.langName(s.target)}") },
                leadingIcon = { Icon(Icons.Rounded.Translate, null, Modifier.size(18.dp)) },
            )
        }
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            entry != null -> EntryView(entry!!, via, translatedDefs, vm, speaking = s.speaking == "dict", onWord = { lookup(it) })
            notFound != null -> EmptyState(Icons.AutoMirrored.Rounded.MenuBook, "No entry for \"$notFound\"", "The offline dictionary covers about 150,000 English words and phrases. Other languages are looked up via English.")
            else -> EmptyState(Icons.AutoMirrored.Rounded.MenuBook, "Offline dictionary", "Definitions, examples, synonyms and antonyms from WordNet — no internet needed.")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EntryView(entry: Entry, via: String?, translatedDefs: Map<String, String>, vm: TranslatorViewModel, speaking: Boolean, onWord: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.lemma, style = MaterialTheme.typography.displaySmall)
                if (via != null) Text("“${entry.word}” in English", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                else if (!entry.lemma.equals(entry.word.trim(), true)) Text("from “${entry.word}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.speak(entry.lemma, TranslateLanguage.ENGLISH, "dict") }) { SpeakIcon(speaking) }
        }
        var index = 0
        for ((pos, senses) in entry.byPos) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pos, style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.primary)
                    senses.forEachIndexed { i, sense ->
                        val appear = remember(sense) { Animatable(0f) }
                        val delayMs = (index++ * 40L).coerceAtMost(600L)
                        LaunchedEffect(sense) {
                            delay(delayMs)
                            appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 300f))
                        }
                        Column(Modifier.graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * 24f }) {
                            Row {
                                Text("${i + 1}.", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(26.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(sense.definition, style = MaterialTheme.typography.bodyLarge)
                                    AnimatedContent(translatedDefs[sense.definition], transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut() }, label = "td") { t ->
                                        if (t != null) Text(t, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                                    }
                                    for (ex in sense.examples.take(2)) {
                                        Text("“$ex”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (sense.synonyms.isNotEmpty()) WordChips("Synonyms", sense.synonyms, onWord)
                                    if (sense.antonyms.isNotEmpty()) WordChips("Opposite", sense.antonyms, onWord)
                                    if (sense.typeOf.isNotEmpty()) WordChips("Type of", sense.typeOf, onWord)
                                    if (sense.similar.isNotEmpty()) WordChips("Similar", sense.similar, onWord)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (entry.allSynonyms.isNotEmpty()) {
            SectionTitle("All synonyms")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (w in entry.allSynonyms.take(40)) AssistChip(onClick = { onWord(w) }, label = { Text(w) })
            }
        }
        if (entry.allAntonyms.isNotEmpty()) {
            SectionTitle("Antonyms")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (w in entry.allAntonyms) AssistChip(onClick = { onWord(w) }, label = { Text(w) })
            }
        }
    }
}

@Composable
private fun WordChips(label: String, words: List<String>, onWord: (String) -> Unit) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(66.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (w in words.take(10)) {
                Text(
                    w,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .graphicsLayer { clip = true; shape = CircleShape }
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { onWord(w) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
