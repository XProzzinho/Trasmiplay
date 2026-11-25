package com.geanalves.transmiplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.net.URLDecoder

class LogicTest {
    @Test fun m3u8Rewrite() {
        val master = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p/index.m3u8?token=abc
            #EXT-X-MEDIA:TYPE=SUBTITLES,URI="https://cdn.x.com/subs/pt.m3u8"
            https://other.cdn.com/720p.m3u8
            /abs/seg1.ts
        """.trimIndent()
        val out = HttpServer(0).rewriteM3u8(master, URL("https://site.com/videos/movie/master.m3u8?x=1")).lines()
        fun dec(l: String) = URLDecoder.decode(l.substringAfter("/p?u=").substringBefore('"'), "UTF-8")
        assertEquals("#EXTM3U", out[0])
        assertEquals("https://site.com/videos/movie/key.bin", dec(out[1].substringAfter("URI=\"")))
        assertTrue(out[2].startsWith("#EXT-X-STREAM-INF"))
        assertEquals("https://site.com/videos/movie/360p/index.m3u8?token=abc", dec(out[3]))
        assertEquals("https://cdn.x.com/subs/pt.m3u8", dec(out[4].substringAfter("URI=\"")))
        assertEquals("https://other.cdn.com/720p.m3u8", dec(out[5]))
        assertEquals("https://site.com/abs/seg1.ts", dec(out[6]))
    }

    @Test fun youtubeIds() {
        assertEquals("Oa3QsDdYc20", Hub.youtubeId("https://m.youtube.com/watch?v=Oa3QsDdYc20&list=RD"))
        assertEquals("TAcKp8BI2Ns", Hub.youtubeId("https://youtu.be/TAcKp8BI2Ns?si=x"))
        assertEquals("TAcKp8BI2Ns", Hub.youtubeId("https://www.youtube.com/shorts/TAcKp8BI2Ns"))
        assertNull(Hub.youtubeId("https://site.com/watch?v=Oa3QsDdYc20"))
    }
}
