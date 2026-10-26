package com.vitmap.core

import com.vitmap.core.zip.ZipExtractor
import com.vitmap.core.zip.ZipIndex
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZipTest {
    private fun fixture(): File {
        val f = File.createTempFile("fixture", ".zip")
        javaClass.getResourceAsStream("/symlinks.zip")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test
    fun readsCentralDirectoryAndModes() {
        val index = ZipIndex.read(fixture())
        assertEquals(4, index.items.size)
        val lib = index["usr/lib/libfoo.so.1.2"]!!
        assertEquals(3000, lib.size)
        assertEquals(8, lib.method)
        assertTrue(lib.isExecutable)
        assertTrue(index["usr/lib/libfoo.so"]!!.isSymlink)
        assertTrue(index["usr/"]!!.isDirectory)
    }

    @Test
    fun extractsFilesAndLinks() {
        val dir = Files.createTempDirectory("unz").toFile()
        val links = HashMap<String, String>()
        ZipExtractor.extract(fixture(), dir, symlink = { target, link -> links[link.relativeTo(dir).path] = target })
        assertEquals("ELF".repeat(1000), File(dir, "usr/lib/libfoo.so.1.2").readText())
        assertEquals("CERT", File(dir, "usr/etc/cert.pem").readText())
        assertEquals(mapOf("usr/lib/libfoo.so" to "libfoo.so.1.2"), links)
    }

    @Test
    fun stripsPrefixAndReadsRemoteStyle() {
        val f = fixture()
        val bytes = f.readBytes()
        var calls = 0
        val index = ZipIndex.read(bytes.size.toLong()) { off, len -> calls++; bytes.copyOfRange(off.toInt(), off.toInt() + len) }
        assertEquals(1, calls) // small archive: the tail read already contains the central directory
        val item = index["usr/etc/cert.pem"]!!
        val header = bytes.copyOfRange(item.localHeaderOffset.toInt(), item.localHeaderOffset.toInt() + 30)
        val start = ZipIndex.dataOffset(item, header).toInt()
        val data = ZipIndex.decode(item, bytes.inputStream(start, item.compressedSize.toInt())).readBytes()
        assertEquals("CERT", String(data))
        val dir = Files.createTempDirectory("strip").toFile()
        ZipExtractor.extract(f, dir, stripPrefix = "usr/", symlink = { _, _ -> })
        assertTrue(File(dir, "lib/libfoo.so.1.2").exists())
    }
}
