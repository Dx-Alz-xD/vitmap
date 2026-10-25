package com.vitmap.app.feature.translate

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

data class Sense(
    val pos: String,
    val definition: String,
    val examples: List<String>,
    val synonyms: List<String>,
    val antonyms: List<String>,
    val typeOf: List<String>,
    val similar: List<String>,
)

data class Entry(val word: String, val lemma: String, val senses: List<Sense>) {
    val byPos: Map<String, List<Sense>> get() = senses.groupBy { it.pos }
    val allSynonyms: List<String> get() = senses.flatMap { it.synonyms + it.similar }.filter { !it.equals(lemma, true) }.distinct()
    val allAntonyms: List<String> get() = senses.flatMap { it.antonyms }.distinct()
}

/** Offline English dictionary backed by a bundled WordNet 3.1 database. */
class Dictionary(private val context: Context) {
    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null

    val isInstalled: Boolean get() = dbFile().exists()

    private fun dbFile() = File(context.filesDir, "wordnet-v1.db")

    private suspend fun open(): SQLiteDatabase = mutex.withLock {
        db?.let { return it }
        withContext(Dispatchers.IO) {
            val f = dbFile()
            if (!f.exists()) {
                val tmp = File(f.parentFile, f.name + ".tmp")
                GZIPInputStream(context.assets.open("wordnet.db.gz")).use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                tmp.renameTo(f)
            }
            SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        }
    }

    /** Prepares the database (first run unpacks ~19 MB). */
    suspend fun warmUp() {
        open()
    }

    suspend fun lookup(word: String): Entry? = withContext(Dispatchers.IO) {
        val db = open()
        val clean = word.trim().lowercase().replace('_', ' ')
        if (clean.isEmpty()) return@withContext null
        for (lemma in candidates(clean)) {
            val ids = db.rawQuery("SELECT synsets FROM words WHERE lemma = ?", arrayOf(lemma)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: continue
            val senses = ArrayList<Sense>()
            for (id in ids.split(',')) {
                db.rawQuery("SELECT pos, definition, examples, words, antonyms, hypernyms, similar FROM synsets WHERE id = ?", arrayOf(id)).use { c ->
                    if (c.moveToFirst()) {
                        fun list(i: Int) = c.getString(i).split('|').filter { it.isNotBlank() }
                        senses += Sense(
                            pos = posName(c.getString(0)),
                            definition = c.getString(1),
                            examples = list(2),
                            synonyms = list(3).filter { !it.equals(lemma, true) },
                            antonyms = list(4),
                            typeOf = list(5),
                            similar = list(6),
                        )
                    }
                }
            }
            if (senses.isNotEmpty()) return@withContext Entry(word, lemma, senses)
        }
        null
    }

    suspend fun suggest(prefix: String, limit: Int = 8): List<String> = withContext(Dispatchers.IO) {
        val p = prefix.trim().lowercase()
        if (p.length < 2) return@withContext emptyList()
        val db = open()
        db.rawQuery("SELECT lemma FROM words WHERE lemma >= ? AND lemma < ? ORDER BY lemma LIMIT ?", arrayOf(p, p + "￿", limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    private fun posName(p: String) = when (p) {
        "n" -> "noun"
        "v" -> "verb"
        "a", "s" -> "adjective"
        "r" -> "adverb"
        else -> p
    }

    /** WordNet's "morphy" detachment rules, so "running", "mice" and "better" still resolve. */
    private fun candidates(w: String): List<String> {
        val out = LinkedHashSet<String>()
        out += w
        IRREGULAR[w]?.let { out += it }
        val rules = listOf(
            "ies" to "y", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man",
            "s" to "", "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to "",
            "er" to "", "est" to "", "er" to "e", "est" to "e", "ier" to "y", "iest" to "y",
        )
        for ((suffix, repl) in rules) {
            if (w.length > suffix.length + 1 && w.endsWith(suffix)) out += w.dropLast(suffix.length) + repl
        }
        // Doubled consonants: "running" -> "run", "stopped" -> "stop"
        for (suffix in listOf("ing", "ed", "er", "est")) {
            if (w.endsWith(suffix)) {
                val stem = w.dropLast(suffix.length)
                if (stem.length >= 3 && stem.last() == stem[stem.length - 2]) out += stem.dropLast(1)
            }
        }
        return out.toList()
    }

    companion object {
        private val IRREGULAR = mapOf(
            "went" to "go", "gone" to "go", "was" to "be", "were" to "be", "been" to "be", "is" to "be", "am" to "be", "are" to "be",
            "had" to "have", "has" to "have", "did" to "do", "done" to "do", "said" to "say", "made" to "make", "took" to "take",
            "taken" to "take", "came" to "come", "saw" to "see", "seen" to "see", "knew" to "know", "known" to "know", "got" to "get",
            "gave" to "give", "given" to "give", "found" to "find", "thought" to "think", "told" to "tell", "became" to "become",
            "left" to "leave", "felt" to "feel", "brought" to "bring", "began" to "begin", "begun" to "begin", "kept" to "keep",
            "held" to "hold", "wrote" to "write", "written" to "write", "stood" to "stand", "heard" to "hear", "let" to "let",
            "meant" to "mean", "met" to "meet", "ran" to "run", "paid" to "pay", "sat" to "sit", "spoke" to "speak", "spoken" to "speak",
            "lay" to "lie", "led" to "lead", "read" to "read", "grew" to "grow", "grown" to "grow", "lost" to "lose", "fell" to "fall",
            "fallen" to "fall", "sent" to "send", "built" to "build", "understood" to "understand", "drew" to "draw", "drawn" to "draw",
            "broke" to "break", "broken" to "break", "spent" to "spend", "cut" to "cut", "rose" to "rise", "risen" to "rise",
            "drove" to "drive", "driven" to "drive", "bought" to "buy", "wore" to "wear", "worn" to "wear", "chose" to "choose",
            "chosen" to "choose", "ate" to "eat", "eaten" to "eat", "flew" to "fly", "flown" to "fly", "swam" to "swim", "sang" to "sing",
            "sung" to "sing", "taught" to "teach", "caught" to "catch", "fought" to "fight", "sold" to "sell", "slept" to "sleep",
            "better" to "good", "best" to "good", "worse" to "bad", "worst" to "bad", "more" to "much", "most" to "much",
            "less" to "little", "least" to "little", "children" to "child", "mice" to "mouse", "geese" to "goose", "feet" to "foot",
            "teeth" to "tooth", "men" to "man", "women" to "woman", "people" to "person", "oxen" to "ox", "data" to "datum",
            "criteria" to "criterion", "phenomena" to "phenomenon", "analyses" to "analysis", "wolves" to "wolf", "knives" to "knife",
            "lives" to "life", "wives" to "wife", "leaves" to "leaf", "halves" to "half", "selves" to "self", "shelves" to "shelf",
        )
    }
}
