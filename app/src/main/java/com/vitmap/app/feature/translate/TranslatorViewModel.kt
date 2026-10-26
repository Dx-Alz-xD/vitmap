package com.vitmap.app.feature.translate

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.Text
import com.vitmap.app.ml.Ocr
import com.vitmap.app.ml.OcrScript
import com.vitmap.app.vitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import java.util.Locale

const val AUTO = "auto"

data class Lang(val code: String, val name: String, val native: String)

@Serializable
data class HistoryItem(val source: String, val target: String, val text: String, val translation: String, val time: Long, val favorite: Boolean = false)

@Serializable
data class TranslatorPrefs(
    val source: String = AUTO,
    val target: String = "",
    val recent: List<String> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val speechRate: Float = 1f,
)

data class Bubble(val side: Int, val original: String, val translated: String)

data class VoiceState(
    val listening: Boolean = false,
    val side: Int = 0,
    val partial: String = "",
    val level: Float = 0f,
    val error: String? = null,
)

data class TranslatorState(
    val source: String = AUTO,
    val target: String = "es",
    val detected: String? = null,
    val input: String = "",
    val output: String = "",
    val translating: Boolean = false,
    /** Language codes whose models are missing for the current pair. */
    val missing: List<String> = emptyList(),
    val error: String? = null,
    val downloaded: Set<String> = setOf(TranslateLanguage.ENGLISH),
    val downloading: Set<String> = emptySet(),
    val prefs: TranslatorPrefs = TranslatorPrefs(),
    val speaking: String? = null,
    val voice: VoiceState = VoiceState(),
    val conversation: List<Bubble> = emptyList(),
) {
    val effectiveSource: String? get() = if (source == AUTO) detected else source
}

class ModelMissingException(val languages: List<String>) : Exception("Language pack missing")

class TranslatorViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.vitmap.store("translator", TranslatorPrefs.serializer()) { TranslatorPrefs() }
    private val _state = MutableStateFlow(TranslatorState())
    val state: StateFlow<TranslatorState> = _state.asStateFlow()

    val dictionary = Dictionary(app)

    val languages: List<Lang> = TranslateLanguage.getAllLanguages().map { code ->
        val loc = Locale.forLanguageTag(code)
        Lang(code, loc.getDisplayName(Locale.getDefault()).replaceFirstChar { it.uppercase() }, loc.getDisplayName(loc).replaceFirstChar { it.uppercase() })
    }.sortedBy { it.name }

    fun langName(code: String?): String = when (code) {
        null -> "Unknown"
        AUTO -> "Detect language"
        else -> languages.firstOrNull { it.code == code }?.name ?: Locale.forLanguageTag(code).displayName
    }

    private val modelManager = RemoteModelManager.getInstance()
    private val translators = HashMap<String, Translator>()
    private val identifier = LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.35f).build())

    private var translateJob: Job? = null
    private var historyJob: Job? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    init {
        viewModelScope.launch {
            val p = store.load()
            val deviceLang = TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
            val defaultTarget = if (deviceLang != null && deviceLang != TranslateLanguage.ENGLISH) deviceLang else TranslateLanguage.SPANISH
            _state.update { it.copy(prefs = p, source = p.source, target = p.target.ifEmpty { defaultTarget }) }
            refreshModels()
        }
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = _state.update { it.copy(speaking = utteranceId) }
                override fun onDone(utteranceId: String?) = _state.update { if (it.speaking == utteranceId) it.copy(speaking = null) else it }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = _state.update { it.copy(speaking = null) }
            })
        }
    }

    private fun savePrefs(t: (TranslatorPrefs) -> TranslatorPrefs) {
        val next = t(_state.value.prefs)
        _state.update { it.copy(prefs = next) }
        viewModelScope.launch { store.set(next) }
    }

    // ------------------------------------------------------------------ models

    fun refreshModels() {
        viewModelScope.launch {
            runCatching {
                val models = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
                _state.update { it.copy(downloaded = models.map { m -> m.language }.toSet() + TranslateLanguage.ENGLISH) }
            }
        }
    }

    fun isDownloaded(code: String) = code in _state.value.downloaded

    fun download(code: String, wifiOnly: Boolean = false) {
        if (code in _state.value.downloading) return
        _state.update { it.copy(downloading = it.downloading + code, error = null) }
        viewModelScope.launch {
            val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
            val result = runCatching { modelManager.download(TranslateRemoteModel.Builder(code).build(), conditions).await() }
            _state.update {
                it.copy(
                    downloading = it.downloading - code,
                    error = result.exceptionOrNull()?.let { e -> "Couldn't download ${langName(code)}: ${e.localizedMessage ?: "check your connection"}" },
                )
            }
            refreshModels()
            if (result.isSuccess) retranslate()
        }
    }

    fun deleteModel(code: String) {
        if (code == TranslateLanguage.ENGLISH) return
        viewModelScope.launch {
            runCatching { modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await() }
            translators.keys.filter { it.contains(code) }.forEach { k -> translators.remove(k)?.close() }
            refreshModels()
        }
    }

    // ------------------------------------------------------------------ translation

    /** Translates [text]; throws [ModelMissingException] if a language pack isn't on the device. */
    suspend fun translate(text: String, source: String, target: String): String {
        if (text.isBlank() || source == target) return text
        val need = listOf(source, target).filter { it !in _state.value.downloaded }
        if (need.isNotEmpty()) {
            refreshModels()
            throw ModelMissingException(need)
        }
        val key = "$source>$target"
        val t = translators.getOrPut(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        }
        // Translate line by line so formatting (line breaks, lists) survives.
        return text.split('\n').map { line -> if (line.isBlank()) line else t.translate(line).await() }.joinToString("\n")
    }

    suspend fun detect(text: String): String? {
        if (text.isBlank()) return null
        val code = runCatching { identifier.identifyLanguage(text).await() }.getOrNull() ?: return null
        if (code == "und") return null
        return TranslateLanguage.fromLanguageTag(code)
    }

    fun setInput(text: String) {
        _state.update { it.copy(input = text) }
        scheduleTranslate()
    }

    private fun scheduleTranslate(immediate: Boolean = false) {
        translateJob?.cancel()
        historyJob?.cancel()
        val s = _state.value
        if (s.input.isBlank()) {
            _state.update { it.copy(output = "", translating = false, missing = emptyList(), detected = null) }
            return
        }
        translateJob = viewModelScope.launch {
            if (!immediate) delay(420)
            _state.update { it.copy(translating = true, error = null) }
            val text = _state.value.input
            val src = if (_state.value.source == AUTO) detect(text).also { d -> _state.update { it.copy(detected = d) } } else _state.value.source
            if (src == null) {
                _state.update { it.copy(translating = false, error = "Couldn't detect the language — pick it manually", output = "") }
                return@launch
            }
            try {
                val out = translate(text, src, _state.value.target)
                _state.update { it.copy(output = out, translating = false, missing = emptyList()) }
                historyJob = viewModelScope.launch {
                    delay(1800)
                    addHistory(HistoryItem(src, _state.value.target, text, out, System.currentTimeMillis()))
                }
            } catch (e: ModelMissingException) {
                _state.update { it.copy(missing = e.languages, translating = false, output = "") }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(translating = false, error = e.localizedMessage ?: "Translation failed") }
            }
        }
    }

    fun retranslate() = scheduleTranslate(immediate = true)

    private fun addHistory(item: HistoryItem) = savePrefs { p ->
        val rest = p.history.filter { !(it.text == item.text && it.target == item.target) }
        p.copy(history = (listOf(item) + rest).take(200))
    }

    fun toggleFavorite(item: HistoryItem) = savePrefs { p ->
        p.copy(history = p.history.map { if (it.time == item.time) it.copy(favorite = !it.favorite) else it })
    }

    fun deleteHistory(item: HistoryItem) = savePrefs { p -> p.copy(history = p.history.filter { it.time != item.time }) }

    fun clearHistory() = savePrefs { p -> p.copy(history = p.history.filter { it.favorite }) }

    fun setSource(code: String) {
        _state.update { it.copy(source = code, detected = null) }
        savePrefs { it.copy(source = code, recent = (listOf(code) + it.recent.filter { r -> r != code }).filter { r -> r != AUTO }.take(6)) }
        retranslate()
    }

    fun setTarget(code: String) {
        _state.update { it.copy(target = code) }
        savePrefs { it.copy(target = code, recent = (listOf(code) + it.recent.filter { r -> r != code }).take(6)) }
        retranslate()
    }

    fun swap() {
        val s = _state.value
        val src = s.effectiveSource ?: return
        _state.update { it.copy(source = s.target, target = src, input = s.output.ifBlank { s.input }, output = "", detected = null) }
        savePrefs { it.copy(source = s.target, target = src) }
        retranslate()
    }

    fun loadHistory(item: HistoryItem) {
        _state.update { it.copy(source = item.source, target = item.target, input = item.text, output = item.translation, detected = null) }
    }

    // ------------------------------------------------------------------ speech output

    fun speak(text: String, code: String?, id: String) {
        val engine = tts ?: return
        if (!ttsReady || text.isBlank()) {
            _state.update { it.copy(error = "Text-to-speech isn't ready on this device") }
            return
        }
        if (_state.value.speaking == id) {
            engine.stop()
            _state.update { it.copy(speaking = null) }
            return
        }
        val locale = Locale.forLanguageTag(code ?: "en")
        val res = engine.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            _state.update { it.copy(error = "No ${langName(code)} voice installed. Add one in system text-to-speech settings.") }
            return
        }
        engine.setSpeechRate(_state.value.prefs.speechRate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun setSpeechRate(rate: Float) = savePrefs { it.copy(speechRate = rate) }

    fun installVoicesIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun clearError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------------ speech input

    /**
     * Listens in the language of [side] (0 = source, 1 = target). In conversation mode the result is
     * translated to the other side and spoken aloud.
     */
    fun startListening(side: Int, conversation: Boolean) {
        val app = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            _state.update { it.copy(voice = it.voice.copy(error = "Speech recognition isn't available on this device")) }
            return
        }
        stopListening()
        val s = _state.value
        val lang = if (side == 0) (s.effectiveSource ?: TranslateLanguage.ENGLISH) else s.target
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        } else {
            SpeechRecognizer.createSpeechRecognizer(app)
        }
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = _state.update { it.copy(voice = VoiceState(listening = true, side = side)) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = _state.update { it.copy(voice = it.voice.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = _state.update { it.copy(voice = it.voice.copy(level = 0f)) }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — try again"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Offline speech isn't installed for ${langName(lang)} — download it in system voice settings"
                    else -> "Speech recognition error ($error)"
                }
                _state.update { it.copy(voice = VoiceState(error = msg, side = side)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.update { it.copy(voice = VoiceState(side = side)) }
                if (text.isBlank()) return
                if (conversation) handleConversation(side, text) else setInput(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.update { it.copy(voice = it.voice.copy(partial = text)) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag(lang).toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        _state.update { it.copy(voice = VoiceState(listening = true, side = side)) }
        rec.startListening(intent)
    }

    fun stopListening() {
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
        _state.update { it.copy(voice = it.voice.copy(listening = false, level = 0f)) }
    }

    private fun handleConversation(side: Int, text: String) {
        viewModelScope.launch {
            val s = _state.value
            val from = if (side == 0) (s.effectiveSource ?: TranslateLanguage.ENGLISH) else s.target
            val to = if (side == 0) s.target else (s.effectiveSource ?: TranslateLanguage.ENGLISH)
            try {
                val out = translate(text, from, to)
                _state.update { it.copy(conversation = it.conversation + Bubble(side, text, out)) }
                speak(out, to, "conv-${System.nanoTime()}")
            } catch (e: ModelMissingException) {
                _state.update { it.copy(missing = e.languages) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.localizedMessage) }
            }
        }
    }

    fun clearConversation() = _state.update { it.copy(conversation = emptyList()) }

    // ------------------------------------------------------------------ OCR

    /** Throws [com.vitmap.app.ml.OcrMissingException] when the script's model isn't downloaded. */
    suspend fun recognize(bitmap: Bitmap, script: OcrScript = Ocr.scriptFor(_state.value.effectiveSource)): Text =
        Ocr.recognize(getApplication<Application>(), bitmap, script)

    suspend fun ocrText(bitmap: Bitmap): String = recognize(bitmap).text

    // ------------------------------------------------------------------ cleanup

    override fun onCleared() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
        translators.values.forEach { it.close() }
        dictionary.close()
        identifier.close()
        super.onCleared()
    }
}
