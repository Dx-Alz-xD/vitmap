package com.vitmap.core

import com.vitmap.core.ytdl.YtAudio
import com.vitmap.core.ytdl.YtContainer
import com.vitmap.core.ytdl.YtMode
import com.vitmap.core.ytdl.YtPlaylist
import com.vitmap.core.ytdl.YtRequest
import com.vitmap.core.ytdl.YtSponsor
import com.vitmap.core.ytdl.YtSubs
import com.vitmap.core.ytdl.YtVideo
import com.vitmap.core.ytdl.Ytdl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtdlTest {
    private val videoJson = """
        WARNING: something on stderr that leaked
        {"_type":"video","id":"abc123","title":"Big 100% Test / Demo","uploader":"Chan","channel":"Chan","duration":600,"upload_date":"20240131",
         "webpage_url":"https://www.youtube.com/watch?v=abc123","extractor_key":"Youtube","view_count":12345,
         "thumbnail":"https://i.ytimg.com/vi/abc123/maxresdefault.webp",
         "formats":[
          {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none","format_note":"storyboard"},
          {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"tbr":129.5,"filesize":9700000},
          {"format_id":"251","ext":"webm","vcodec":"none","acodec":"opus","abr":140,"tbr":140,"filesize_approx":10500000,"language":"en"},
          {"format_id":"18","ext":"mp4","vcodec":"avc1.42001E","acodec":"mp4a.40.2","width":640,"height":360,"fps":30,"tbr":500},
          {"format_id":"137","ext":"mp4","vcodec":"avc1.640028","acodec":"none","width":1920,"height":1080,"fps":30,"tbr":4000,"filesize":300000000},
          {"format_id":"248","ext":"webm","vcodec":"vp9","acodec":"none","width":1920,"height":1080,"fps":30,"tbr":2600,"filesize":195000000},
          {"format_id":"136","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","width":1280,"height":720,"fps":30,"vbr":1500,"tbr":1500}
         ],
         "subtitles":{"en":[{"ext":"vtt"}],"live_chat":[{"ext":"json"}],"de":[{"ext":"vtt"}]},
         "automatic_captions":{"fr":[{"ext":"vtt"}]},
         "chapters":[{"start_time":0,"end_time":60,"title":"Intro"}]}
    """.trimIndent()

    @Test
    fun parsesVideoAndFiltersStoryboards() {
        val v = Ytdl.parse(videoJson) as YtVideo
        assertEquals("abc123", v.id)
        assertEquals(6, v.formats.size)
        assertEquals(listOf(1080, 720, 360), v.qualities)
        assertEquals(listOf("de", "en"), v.subtitles)
        assertEquals(listOf("fr"), v.autoCaptions)
        assertEquals(1, v.chapters.size)
        assertEquals("H.264", v.formats.first { it.id == "137" }.codecLabel)
        // Best 1080p is the smaller VP9 stream plus the best audio (Opus).
        assertEquals(195_000_000L + 10_500_000L, v.estimate(1080))
        // 720p has no size, so it's estimated from the bitrate: 1500 kbit/s × 600 s.
        assertEquals(112_500_000L + 10_500_000L, v.estimate(720))
        // 360p is a muxed stream, so no separate audio is added.
        assertEquals(500L * 1000 / 8 * 600, v.estimate(360))
    }

    @Test
    fun parsesFlatPlaylists() {
        val p = Ytdl.parse("""{"_type":"playlist","id":"PL1","title":"Mix","uploader":"U","entries":[{"id":"a","title":"One","url":"https://y/a","duration":61},{"id":"b","title":"Two","url":"https://y/b"}]}""") as YtPlaylist
        assertEquals(2, p.entries.size)
        assertEquals(2, p.entries[1].index)
        assertEquals("Mix", p.title)
    }

    @Test
    fun templatesEscapeAndMapTokens() {
        assertEquals("%(title)s - %(channel,uploader|Unknown)s", Ytdl.outputTemplate("{title} - {channel}"))
        assertEquals("100%% done-ok %(id)s", Ytdl.outputTemplate("100% done/ok {id}"))
        assertEquals("%(title)s", Ytdl.outputTemplate("  "))
        val v = Ytdl.parse(videoJson) as YtVideo
        assertEquals("2024-01-31 Big 100% Test - Demo [1080p]", Ytdl.previewName("{date} {title} [{quality}]", v, null, 1080, false))
        assertEquals("Big 100% Test - Demo [720p]", Ytdl.previewName("{title} [{quality}]", v, null, 900, false))
    }

    @Test
    fun buildsVideoArgs() {
        val a = Ytdl.args(YtRequest("https://youtu.be/abc123 ", quality = 720, container = YtContainer.MKV, subs = YtSubs.EMBED, subLangs = listOf("en", "de")), "/out")
        assertTrue(a.containsAll(listOf("-f", "bv*+ba/b", "--merge-output-format", "mkv", "--remux-video", "mkv", "--embed-subs", "--no-playlist")))
        assertEquals("hdr:SDR,res:720,fps,+codec:avc:m4a", a[a.indexOf("-S") + 1])
        assertEquals("en,de", a[a.indexOf("--sub-langs") + 1])
        assertEquals(listOf("--", "https://youtu.be/abc123"), a.takeLast(2))
        assertTrue("--embed-thumbnail" in a)
    }

    @Test
    fun buildsAudioArgs() {
        val a = Ytdl.args(YtRequest("u", mode = YtMode.AUDIO, audio = YtAudio.WAV, sponsor = YtSponsor.REMOVE, sectionStart = 65.5, sectionEnd = 0.0, subs = YtSubs.EMBED), "/out")
        assertTrue(a.containsAll(listOf("-x", "--audio-format", "wav", "--sponsorblock-remove", "--force-keyframes-at-cuts", "--convert-subs")))
        assertFalse("--audio-quality" in a, "lossless formats take no bitrate")
        assertFalse("--embed-thumbnail" in a, "WAV can't hold cover art")
        assertFalse("--embed-subs" in a)
        assertEquals("*0:01:05.500-inf", a[a.indexOf("--download-sections") + 1])
        val mp3 = Ytdl.args(YtRequest("u", mode = YtMode.AUDIO, audio = YtAudio.MP3, audioKbps = 192), "/o")
        assertEquals("192K", mp3[mp3.indexOf("--audio-quality") + 1])
    }

    @Test
    fun splitsExtraArgsLikeAShell() {
        assertEquals(listOf("--cookies", "/sdcard/my cookies.txt", "-o", "a b", "x\"y"), Ytdl.splitArgs("""--cookies "/sdcard/my cookies.txt" -o 'a b' x\"y"""))
        assertEquals(listOf("--flag", ""), Ytdl.splitArgs("--flag \"\""))
    }

    @Test
    fun parsesProgressLines() {
        val p = Ytdl.parseProgress("[vp] 42.0%|4200|10000|NA|1048576.0|12|NA|avc1.640028")!!
        assertEquals(0.42f, p.fraction)
        assertEquals(12, p.etaSec)
        assertFalse(p.audioStream)
        val est = Ytdl.parseProgress("[vp]  10.5%|500|NA|4000.0|NA|NA|3|none")!!
        assertEquals(0.125f, est.fraction)
        assertEquals(3, est.playlistIndex)
        assertTrue(est.audioStream)
        assertEquals("1.0 MB/s", Ytdl.formatSpeed(1048576.0))
        assertEquals("Merging video and audio", Ytdl.stageFor("[Merger] Merging formats into \"x.mp4\""))
        assertEquals("Item 3 of 10", Ytdl.stageFor("[download] Downloading item 3 of 10"))
    }

    @Test
    fun findsLinksInSharedText() {
        assertEquals("https://youtu.be/abc", Ytdl.extractUrl("Watch this! https://youtu.be/abc."))
        assertTrue(Ytdl.hasPlaylist("https://www.youtube.com/watch?v=a&list=PLx_1"))
        assertFalse(Ytdl.hasPlaylist("https://www.youtube.com/watch?v=a"))
    }
}
