package com.geanalves.transmiplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvTest {
    @Test fun lerListaDeCanais() {
        val txt = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://x.com/a.png" group-title="Notícias",Canal Um
            https://cdn.exemplo.com/um/index.m3u8
            #EXTINF:-1,Rádio Dois
            #EXTGRP:Rádios
            https://cdn.exemplo.com/dois.mp3
            #EXTINF:-1,Relativo
            tres/live.m3u8
        """.trimIndent()
        val l = Iptv.parse(txt, "https://lista.exemplo.com/pasta/lista.m3u")
        assertEquals(3, l.size)
        assertEquals("Canal Um", l[0].name)
        assertEquals("Notícias", l[0].group)
        assertEquals("https://x.com/a.png", l[0].logo)
        assertEquals("Rádios", l[1].group)
        assertEquals("https://lista.exemplo.com/pasta/tres/live.m3u8", l[2].url)
        assertEquals("m3u8", Iptv.toItem(l[0]).mime)
    }

    @Test fun hlsComumNaoEhLista() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n"
        assertTrue(Iptv.parse(media, "https://a.com/v.m3u8").isEmpty())
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://a.com/720.m3u8\n"
        assertTrue(Iptv.parse(master).isEmpty())
        assertTrue(Iptv.maybeList("https://a.com/lista.m3u?x=1"))
        assertTrue(!Iptv.maybeList("https://a.com/video.mp4"))
    }
}
