package com.vitmap.app.feature.fileconvert

import com.vitmap.app.feature.compress.Progress
import kotlinx.coroutines.Job
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** Repacks archives between ZIP, 7z and the TAR family, or packs/compresses a single file. */
object ArchiveConvert {
    private const val FILE_MODE = 420 // 0644
    private const val DIR_MODE = 493 // 0755
    private val SINGLE = setOf("gz", "xz", "bz2")

    private class Item(val name: String, val dir: Boolean, val size: Long, val time: Long, val mode: Int, val open: () -> InputStream)

    private class Meter(val p: Progress, val job: Job?, val label: String) {
        var total = 0L
        var done = 0L
        var raw: CountingStream? = null
        var rawTotal = 0L
        fun tick(n: Int) {
            if (job?.isActive == false) throw CancellationException()
            done += n
            val f = raw?.let { if (rawTotal > 0) it.count.toFloat() / rawTotal else null } ?: if (total > 0) done.toFloat() / total else null
            p.update(f?.coerceIn(0f, 1f), label)
        }
    }

    suspend fun convert(input: File, name: String, inFmt: Fmt?, out: Fmt, target: File, p: Progress) {
        val meter = Meter(p, coroutineContext[Job], "Packing ${out.label}")
        try {
            when {
                out.id in SINGLE -> compressSingle(input, out.id, target, meter)
                inFmt?.family == Family.ARCHIVE -> sink(out.id, target).use { s ->
                    var count = 0
                    read(input, inFmt, name, meter) { item -> s.put(item, meter); count++ }
                    if (count == 0) throw IOException("This archive is empty")
                }
                else -> sink(out.id, target).use { s ->
                    meter.total = input.length()
                    s.put(Item(name, false, input.length(), input.lastModified(), FILE_MODE) { input.inputStream() }, meter)
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    // ---------------------------------------------------------------- reading

    private fun read(input: File, fmt: Fmt, name: String, meter: Meter, onItem: (Item) -> Unit) {
        when (fmt.id) {
            "zip" -> ZipFile.builder().setFile(input).get().use { z ->
                val entries = z.entries.toList()
                meter.total = entries.sumOf { it.size.coerceAtLeast(0) }
                for (e in entries) {
                    if (e.isUnixSymlink) continue
                    onItem(Item(e.name, e.isDirectory, e.size, e.time, e.unixMode and 0xFFF) { z.getInputStream(e) })
                }
            }
            "7z" -> SevenZFile.builder().setFile(input).get().use { sz ->
                meter.total = sz.entries.sumOf { if (it.hasStream()) it.size else 0L }
                while (true) {
                    val e = sz.nextEntry ?: break
                    val time = runCatching { if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L }.getOrDefault(0L)
                    onItem(Item(e.name, e.isDirectory, if (e.hasStream()) e.size else 0L, time, 0) { sz.getInputStream(e) })
                }
            }
            "tar", "tgz", "txz", "tbz" -> {
                val raw = CountingStream(input.inputStream().buffered(1 shl 16))
                meter.raw = raw
                meter.rawTotal = input.length()
                val inner = when (fmt.id) {
                    "tgz" -> GzipCompressorInputStream(raw, true)
                    "txz" -> XZCompressorInputStream(raw, true)
                    "tbz" -> BZip2CompressorInputStream(raw, true)
                    else -> raw
                }
                TarArchiveInputStream(inner).use { t ->
                    while (true) {
                        val e = t.nextEntry ?: break
                        if (e.isSymbolicLink || e.isLink || !(e.isFile || e.isDirectory)) continue
                        onItem(Item(e.name, e.isDirectory, e.size, e.modTime.time, e.mode and 0xFFF) { Unclosable(t) })
                    }
                }
            }
            "gz", "xz", "bz2" -> {
                val raw = CountingStream(input.inputStream().buffered(1 shl 16))
                meter.raw = raw
                meter.rawTotal = input.length()
                val stream = when (fmt.id) {
                    "gz" -> GzipCompressorInputStream(raw, true)
                    "xz" -> XZCompressorInputStream(raw, true)
                    else -> BZip2CompressorInputStream(raw, true)
                }
                val inner = name.substringBeforeLast('.').ifEmpty { "file" }
                stream.use { onItem(Item(inner, false, -1, input.lastModified(), FILE_MODE) { Unclosable(it) }) }
            }
            else -> throw IOException("Can't read ${fmt.label} archives")
        }
    }

    // ---------------------------------------------------------------- writing

    private interface Sink : Closeable {
        fun put(item: Item, meter: Meter)
    }

    private fun copy(from: InputStream, to: OutputStream, meter: Meter) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = from.read(buf)
            if (n < 0) break
            to.write(buf, 0, n)
            meter.tick(n)
        }
    }

    private fun sink(id: String, target: File): Sink = when (id) {
        "zip" -> object : Sink {
            val z = ZipArchiveOutputStream(target).apply { setUseZip64(Zip64Mode.AsNeeded) }
            override fun put(item: Item, meter: Meter) {
                val name = if (item.dir) item.name.trimEnd('/') + "/" else item.name
                val e = ZipArchiveEntry(name)
                if (item.time > 0) e.time = item.time
                if (item.mode != 0) e.unixMode = (if (item.dir) 0x4000 else 0x8000) or item.mode
                z.putArchiveEntry(e)
                if (!item.dir) item.open().use { copy(it, z, meter) }
                z.closeArchiveEntry()
            }
            override fun close() = z.close()
        }
        "7z" -> object : Sink {
            val sz = SevenZOutputFile(target)
            override fun put(item: Item, meter: Meter) {
                val e = SevenZArchiveEntry()
                e.name = item.name.trimEnd('/')
                e.isDirectory = item.dir
                e.setHasStream(!item.dir)
                if (item.time > 0) e.lastModifiedDate = Date(item.time)
                sz.putArchiveEntry(e)
                if (!item.dir) item.open().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sz.write(buf, 0, n)
                        meter.tick(n)
                    }
                }
                sz.closeArchiveEntry()
            }
            override fun close() = sz.close()
        }
        "tar", "tgz", "txz", "tbz" -> object : Sink {
            val raw = target.outputStream().buffered(1 shl 16)
            val t = TarArchiveOutputStream(
                when (id) {
                    "tgz" -> GZIPOutputStream(raw, 1 shl 16)
                    "txz" -> XZCompressorOutputStream(raw, 6)
                    "tbz" -> BZip2CompressorOutputStream(raw)
                    else -> raw
                },
            ).apply {
                setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                setAddPaxHeadersForNonAsciiNames(true)
            }
            override fun put(item: Item, meter: Meter) {
                val name = if (item.dir) item.name.trimEnd('/') + "/" else item.name
                val e = TarArchiveEntry(name)
                if (item.time > 0) e.modTime = Date(item.time)
                e.mode = (if (item.dir) 0x4000 else 0x8000) or (if (item.mode != 0) item.mode else if (item.dir) DIR_MODE else FILE_MODE)
                if (item.dir) {
                    t.putArchiveEntry(e)
                    t.closeArchiveEntry()
                    return
                }
                // TAR headers carry the size up front, so spool streams of unknown length first.
                var spool: File? = null
                try {
                    val size = if (item.size >= 0) item.size else {
                        spool = File.createTempFile("spool", ".bin", target.parentFile)
                        item.open().use { input -> spool.outputStream().use { input.copyTo(it, 1 shl 16) } }
                        spool.length()
                    }
                    e.size = size
                    t.putArchiveEntry(e)
                    (spool?.inputStream() ?: item.open()).use { copy(it, t, meter) }
                    t.closeArchiveEntry()
                } finally {
                    spool?.delete()
                }
            }
            override fun close() = t.close()
        }
        else -> throw IOException("Can't write $id archives")
    }

    private fun compressSingle(input: File, id: String, target: File, meter: Meter) {
        meter.total = input.length()
        val raw = target.outputStream().buffered(1 shl 16)
        val out: OutputStream = when (id) {
            "gz" -> GZIPOutputStream(raw, 1 shl 16)
            "xz" -> XZCompressorOutputStream(raw, 6)
            else -> BZip2CompressorOutputStream(raw)
        }
        out.use { o -> input.inputStream().use { copy(it, o, meter) } }
    }

    private class CountingStream(s: InputStream) : FilterInputStream(s) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    /** Lets an entry's reader be closed without closing the archive stream underneath. */
    private class Unclosable(s: InputStream) : FilterInputStream(s) {
        override fun close() {}
    }
}
