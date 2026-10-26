package com.vitmap.core.zip

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** One entry of a zip archive's central directory. */
data class ZipItem(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val crc: Long,
    val localHeaderOffset: Long,
    val externalAttributes: Long,
) {
    val isDirectory: Boolean get() = name.endsWith('/')

    /** Unix file mode stored by zip tools that record permissions (upper 16 bits). */
    val unixMode: Int get() = ((externalAttributes ushr 16) and 0xFFFF).toInt()

    val isSymlink: Boolean get() = (unixMode and 0xF000) == 0xA000

    val isExecutable: Boolean get() = (unixMode and 0x49) != 0
}

/**
 * Reads a zip archive's central directory through random access, so a single entry can be pulled
 * out of a large remote archive with two or three byte-range requests. Zip64 archives are
 * supported for sizes and offsets.
 */
class ZipIndex private constructor(val items: List<ZipItem>) {
    private val byName = items.associateBy { it.name }

    operator fun get(name: String): ZipItem? = byName[name]

    companion object {
        /** [read] returns [len] bytes starting at [offset]; [size] is the archive length. Blocking. */
        fun read(size: Long, read: (offset: Long, len: Int) -> ByteArray): ZipIndex {
            val tailLen = minOf(size, 65_557L).toInt()
            val tailStart = size - tailLen
            val tail = read(tailStart, tailLen)
            var eocd = -1
            for (i in tail.size - 22 downTo 0) {
                if (tail.u32(i) == 0x06054b50L) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) throw IOException("Not a zip archive")
            var count = tail.u16(eocd + 10).toLong()
            var cdSize = tail.u32(eocd + 12)
            var cdOffset = tail.u32(eocd + 16)
            // Zip64 end-of-central-directory locator sits just before the EOCD record.
            val loc = eocd - 20
            if (loc >= 0 && tail.u32(loc) == 0x07064b50L) {
                val z64Offset = tail.u64(loc + 8)
                val rec = read(z64Offset, 56)
                if (rec.u32(0) == 0x06064b50L) {
                    count = rec.u64(32)
                    cdSize = rec.u64(40)
                    cdOffset = rec.u64(48)
                }
            }
            val cd = if (cdOffset >= tailStart) {
                val from = (cdOffset - tailStart).toInt()
                tail.copyOfRange(from, from + cdSize.toInt())
            } else {
                read(cdOffset, cdSize.toInt())
            }
            val items = ArrayList<ZipItem>(count.toInt())
            var p = 0
            while (p + 46 <= cd.size && cd.u32(p) == 0x02014b50L) {
                val method = cd.u16(p + 10)
                val crc = cd.u32(p + 16)
                var compressed = cd.u32(p + 20)
                var uncompressed = cd.u32(p + 24)
                val nameLen = cd.u16(p + 28)
                val extraLen = cd.u16(p + 30)
                val commentLen = cd.u16(p + 32)
                val external = cd.u32(p + 38)
                var localOffset = cd.u32(p + 42)
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                // Zip64 extra field carries the real values for fields saturated at 0xFFFFFFFF.
                var e = p + 46 + nameLen
                val extraEnd = e + extraLen
                while (e + 4 <= extraEnd) {
                    val id = cd.u16(e)
                    val len = cd.u16(e + 2)
                    if (id == 0x0001) {
                        var q = e + 4
                        if (uncompressed == 0xFFFFFFFFL) { uncompressed = cd.u64(q); q += 8 }
                        if (compressed == 0xFFFFFFFFL) { compressed = cd.u64(q); q += 8 }
                        if (localOffset == 0xFFFFFFFFL) { localOffset = cd.u64(q) }
                    }
                    e += 4 + len
                }
                items += ZipItem(name, method, compressed, uncompressed, crc, localOffset, external)
                p = extraEnd + commentLen
            }
            return ZipIndex(items)
        }

        fun read(file: File): ZipIndex = RandomAccessFile(file, "r").use { raf ->
            read(raf.length()) { off, len ->
                val b = ByteArray(len)
                raf.seek(off)
                raf.readFully(b)
                b
            }
        }

        /** Offset of [item]'s compressed data, given the 30-byte local header read at its offset. */
        fun dataOffset(item: ZipItem, localHeader: ByteArray): Long {
            if (localHeader.u32(0) != 0x04034b50L) throw IOException("Bad local header for ${item.name}")
            return item.localHeaderOffset + 30 + localHeader.u16(26) + localHeader.u16(28)
        }

        /** Wraps the raw (compressed) bytes of an entry into a stream of its contents. */
        fun decode(item: ZipItem, raw: InputStream): InputStream = when (item.method) {
            0 -> raw
            8 -> InflaterInputStream(raw, Inflater(true), 64 * 1024)
            else -> throw IOException("Unsupported compression method ${item.method} for ${item.name}")
        }
    }
}

/**
 * Extracts a local zip into [target], recreating symbolic links (Termux-built runtimes ship their
 * shared libraries as symlink chains) and executable bits. Entries escaping [target] are rejected.
 */
object ZipExtractor {
    fun extract(
        archive: File,
        target: File,
        stripPrefix: String = "",
        symlink: (linkTarget: String, link: File) -> Unit,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val index = ZipIndex.read(archive)
        val root = target.canonicalFile
        val total = index.items.sumOf { it.size }
        var done = 0L
        RandomAccessFile(archive, "r").use { raf ->
            for (item in index.items) {
                if (!item.name.startsWith(stripPrefix)) continue
                val rel = item.name.removePrefix(stripPrefix)
                if (rel.isEmpty()) continue
                val dest = File(root, rel)
                if (!dest.canonicalPath.startsWith(root.path + File.separator) && dest.canonicalPath != root.path) {
                    throw IOException("Entry escapes the target folder: ${item.name}")
                }
                if (item.isDirectory) {
                    dest.mkdirs()
                    continue
                }
                dest.parentFile?.mkdirs()
                val header = ByteArray(30)
                raf.seek(item.localHeaderOffset)
                raf.readFully(header)
                val start = ZipIndex.dataOffset(item, header)
                val raw = RafSliceStream(raf, start, item.compressedSize)
                ZipIndex.decode(item, raw).use { input ->
                    if (item.isSymlink) {
                        val linkTarget = input.readBytes().toString(Charsets.UTF_8)
                        if (dest.exists() || isBrokenLink(dest)) dest.delete()
                        symlink(linkTarget, dest)
                    } else {
                        dest.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
                        if (item.isExecutable) dest.setExecutable(true, false)
                    }
                }
                done += item.size
                onProgress(done, total)
            }
        }
    }

    private fun isBrokenLink(f: File): Boolean = !f.exists() && f.parentFile?.list()?.contains(f.name) == true
}

private class RafSliceStream(private val raf: RandomAccessFile, start: Long, private val length: Long) : InputStream() {
    private var pos = start
    private val end = start + length

    override fun read(): Int {
        if (pos >= end) return -1
        raf.seek(pos++)
        return raf.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= end) return -1
        val n = minOf(len.toLong(), end - pos).toInt()
        raf.seek(pos)
        val r = raf.read(b, off, n)
        if (r > 0) pos += r
        return r
    }
}

private fun ByteArray.u16(i: Int): Int = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
private fun ByteArray.u32(i: Int): Long = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
private fun ByteArray.u64(i: Int): Long = u32(i) or (u32(i + 4) shl 32)
