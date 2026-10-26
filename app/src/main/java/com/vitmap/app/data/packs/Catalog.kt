package com.vitmap.app.data.packs

import android.content.Context
import com.vitmap.app.media.MediaEngine
import com.vitmap.app.ml.Ocr
import java.io.File

/** Every optional download the app knows about, for the Downloads screen. Features add theirs here. */
object PackCatalog {
    /** Large data files hosted in this repository, pinned to the commit that added them. */
    private const val REPO_COMMIT = "d10d28a98072da7204cfd96ef96d53ef576bc7b2"

    fun repoFile(path: String): List<String> = listOf(
        "https://raw.githubusercontent.com/Dx-Alz-xD/vitmap/$REPO_COMMIT/$path",
        "https://cdn.jsdelivr.net/gh/Dx-Alz-xD/vitmap@$REPO_COMMIT/$path",
    )

    private val dictionaryInstaller = FilesInstaller("wordnet", "1") {
        listOf(
            RemoteFile(
                urls = repoFile("packs/wordnet-v1.db.gz"),
                path = "wordnet-v1.db",
                sha256 = "5707d1e20983139340820f74a0424b0a19b8b3f05b624d069084311bbf888941",
                gunzip = true,
            ),
        )
    }

    val dictionary = Pack(
        id = "wordnet",
        title = "English dictionary",
        description = "WordNet 3.1: definitions, examples, synonyms and antonyms for about 150,000 words and phrases. Works offline once downloaded.",
        approxBytes = 7_900_308,
        group = PackGroup.LANGUAGE,
        installer = dictionaryInstaller,
    )

    fun dictionaryFile(context: Context): File = File(dictionaryInstaller.dir(context), "wordnet-v1.db")

    private val extra = ArrayList<() -> List<Pack>>()

    /** Lets a feature list its packs without this file knowing about it. */
    fun register(provider: () -> List<Pack>) = synchronized(extra) { extra += provider }

    val all: List<Pack>
        get() = buildList {
            add(dictionary)
            addAll(Ocr.packs)
            add(MediaEngine.ffmpeg)
            add(MediaEngine.python)
            add(MediaEngine.ytdlp)
            synchronized(extra) { extra.forEach { addAll(it()) } }
        }.distinctBy { it.id }
}
