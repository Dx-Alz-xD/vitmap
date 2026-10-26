package com.vitmap.core

import com.vitmap.core.convert.Cue
import com.vitmap.core.convert.DataFormat
import com.vitmap.core.convert.DataFormats
import com.vitmap.core.convert.SubtitleFormat
import com.vitmap.core.convert.Subtitles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConvertTest {
    private val srt = "1\n00:00:01,500 --> 00:00:03,250\nHello <i>world</i>\n\n2\n00:01:02,000 --> 00:01:04,000\nSecond line\nwraps\n"

    @Test
    fun subtitlesRoundTripThroughEveryFormat() {
        val cues = Subtitles.parse(srt, SubtitleFormat.SRT)
        assertEquals(listOf(Cue(1500, 3250, "Hello world"), Cue(62000, 64000, "Second line\nwraps")), cues)
        for (f in listOf(SubtitleFormat.SRT, SubtitleFormat.VTT, SubtitleFormat.ASS, SubtitleFormat.SSA, SubtitleFormat.SBV)) {
            val back = Subtitles.parse(Subtitles.write(cues, f), f)
            assertEquals(cues.map { it.text }, back.map { it.text }, "text via $f")
            assertTrue(cues.zip(back).all { (a, b) -> kotlin.math.abs(a.start - b.start) <= 10 && kotlin.math.abs(a.end - b.end) <= 10 }, "times via $f: $back")
        }
        val lrc = Subtitles.parse(Subtitles.write(cues, SubtitleFormat.LRC), SubtitleFormat.LRC)
        assertEquals(1500, lrc.first().start)
    }

    @Test
    fun vttWithSettingsAndRetime() {
        val vtt = "WEBVTT\n\nintro\n00:00.250 --> 00:02.000 align:start\nHi\n"
        val cues = Subtitles.parse(vtt, SubtitleFormat.VTT)
        assertEquals(listOf(Cue(250, 2000, "Hi")), cues)
        assertEquals(Cue(1250, 3000, "Hi"), Subtitles.retime(cues, 1000).first())
    }

    @Test
    fun csvJsonYamlXmlRoundTrips() {
        val csv = "name,age,city\r\n\"Doe, Jane\",31,\"Paris \"\"FR\"\"\"\r\nBob,25,Oslo\r\n"
        val json = DataFormats.read(csv, DataFormat.CSV) as JsonArray
        assertEquals("Doe, Jane", (json[0] as JsonObject)["name"]!!.jsonPrimitive.content)
        assertEquals("31", (json[0] as JsonObject)["age"]!!.jsonPrimitive.content)
        assertEquals(json, DataFormats.read(DataFormats.write(json, DataFormat.CSV), DataFormat.CSV))
        assertEquals(json, DataFormats.read(DataFormats.write(json, DataFormat.YAML), DataFormat.YAML))
        assertEquals(json, DataFormats.read(DataFormats.write(json, DataFormat.JSON), DataFormat.JSON))
        val tsv = DataFormats.write(json, DataFormat.TSV)
        assertTrue(tsv.startsWith("name\tage\tcity"))
    }

    @Test
    fun xmlToJsonAndBack() {
        val xml = "<library><book id=\"1\"><title>Dune</title></book><book id=\"2\"><title>Emma</title></book></library>"
        val e = DataFormats.read(xml, DataFormat.XML) as JsonObject
        val books = (e["library"] as JsonObject)["book"] as JsonArray
        assertEquals(2, books.size)
        assertEquals("Emma", ((books[1] as JsonObject)["title"])!!.jsonPrimitive.content)
        val again = DataFormats.read(DataFormats.write(e, DataFormat.XML), DataFormat.XML)
        assertEquals(e, again)
    }

    @Test
    fun tomlAndPropertiesOutput() {
        val e = Json.parseToJsonElement("""{"title":"App","owner":{"name":"Ann","admin":true},"ports":[80,443],"servers":[{"host":"a"},{"host":"b"}]}""")
        val toml = DataFormats.write(e, DataFormat.TOML)
        assertTrue("title = \"App\"" in toml && "[owner]" in toml && "admin = true" in toml && "ports = [80, 443]" in toml && "[[servers]]" in toml, toml)
        val props = DataFormats.write(e, DataFormat.PROPERTIES)
        assertTrue("owner.name=Ann" in props, props)
        val back = DataFormats.read("a.b=1\na.c=x\n", DataFormat.PROPERTIES) as JsonObject
        assertEquals("1", (back["a"] as JsonObject)["b"]!!.jsonPrimitive.content)
    }
}
