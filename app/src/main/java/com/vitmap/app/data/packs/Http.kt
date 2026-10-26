package com.vitmap.app.data.packs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Small HTTP helpers built on [HttpURLConnection], with resumable downloads and byte ranges. */
object Http {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Vitmap/2.0"

    fun connect(url: String, range: LongRange? = null, method: String = "GET", headers: Map<String, String> = emptyMap()): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            val c = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
                if (range != null) setRequestProperty("Range", "bytes=${range.first}-${if (range.last == Long.MAX_VALUE) "" else range.last}")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                c.disconnect()
                current = URL(current, loc)
            } else {
                return c
            }
        }
        throw IOException("Too many redirects for $url")
    }

    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val c = connect(url, headers = headers)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun post(url: String, body: String, contentType: String = "application/json", headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val ok = c.responseCode in 200..299
            val stream = if (ok) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) throw IOException("HTTP ${c.responseCode}: ${text.take(300)}")
            text
        } finally {
            c.disconnect()
        }
    }

    /** Reads [range] (inclusive) of [url]. The server must honour byte ranges. */
    suspend fun range(url: String, range: LongRange): ByteArray = withContext(Dispatchers.IO) {
        val c = connect(url, range)
        try {
            if (c.responseCode != 206) throw IOException("Server ignored the byte range (HTTP ${c.responseCode})")
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Total size of [url] in bytes, or -1. */
    suspend fun contentLength(url: String): Long = withContext(Dispatchers.IO) {
        val c = connect(url, 0L..0L)
        try {
            when (c.responseCode) {
                206 -> c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1
                200 -> c.contentLengthLong
                else -> throw IOException("HTTP ${c.responseCode} for $url")
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads the first working mirror in [urls] to [dest]. A partial `.part` file left by an
     * interrupted download is resumed when the server supports ranges. [range] limits the download
     * to part of the remote file (used to fetch one entry out of a remote zip).
     */
    suspend fun download(
        urls: List<String>,
        dest: File,
        sha256: String? = null,
        range: LongRange? = null,
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (url in urls) {
            try {
                downloadOne(url, dest, range, progress)
                if (sha256 != null) {
                    val actual = sha256(dest)
                    if (!actual.equals(sha256, ignoreCase = true)) {
                        dest.delete()
                        throw IOException("Checksum mismatch for ${dest.name}")
                    }
                }
                return@withContext
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                last = e
            }
        }
        throw last ?: IOException("No download location")
    }

    private suspend fun downloadOne(url: String, dest: File, range: LongRange?, progress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        val base = range?.first ?: 0L
        val wanted = range?.let { it.last - it.first + 1 } ?: -1L
        var have = if (part.exists()) part.length() else 0L
        if (wanted in 1..have) have = 0L.also { part.delete() }
        val requested = if (have > 0 || range != null) (base + have)..(range?.last ?: Long.MAX_VALUE) else null
        val c = connect(url, requested)
        try {
            val code = c.responseCode
            val append = code == 206 && have > 0
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            if (range != null && code != 206) throw IOException("Server ignored the byte range")
            if (!append) have = 0L
            val total = when {
                wanted > 0 -> wanted
                code == 206 -> c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1
                else -> c.contentLengthLong
            }
            c.inputStream.use { input -> copy(input, FileOutputStream(part, append), have, total, progress) }
            if (wanted > 0 && part.length() != wanted) throw IOException("Download ended early")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("Couldn't move ${dest.name} into place")
        } finally {
            c.disconnect()
        }
    }

    private suspend fun copy(input: InputStream, out: FileOutputStream, start: Long, total: Long, progress: (Long, Long) -> Unit) {
        out.use {
            val buf = ByteArray(128 * 1024)
            var done = start
            var lastReport = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                it.write(buf, 0, n)
                done += n
                val now = System.nanoTime()
                if (now - lastReport > 100_000_000L) {
                    lastReport = now
                    progress(done, total)
                }
            }
            progress(done, total)
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
