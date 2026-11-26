package com.geanalves.transmiplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CastTest {
    @Test fun lerDescricaoDlna() {
        val xml = """<?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <friendlyName>[TV] Samsung 7 Series &amp; Cia</friendlyName><UDN>uuid:abc-123</UDN>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>upnp/control/AVTransport1</controlURL></service>
            </serviceList></device></root>"""
        val d = Cast.describe("http://192.168.0.20:9197/dmr", xml)
        assertNotNull(d)
        assertEquals("[TV] Samsung 7 Series & Cia", d!!.name)
        assertEquals("uuid:abc-123", d.id)
        val f = Cast.Dlna::class.java.getDeclaredField("control").apply { isAccessible = true }
        assertEquals("http://192.168.0.20:9197/upnp/control/AVTransport1", f.get(d))
        // sem AVTransport (ex.: só servidor de mídia) não serve
        assertNull(Cast.describe("http://x/", "<root><device><friendlyName>NAS</friendlyName></device></root>"))
    }

    @Test fun tempos() {
        assertEquals("1:02:03", Cast.msToTime(3_723_000))
        assertEquals(3_723_000L, Cast.timeToMs("01:02:03.500"))
        assertNull(Cast.timeToMs("NOT_IMPLEMENTED"))
    }

    @Test fun mensagemChromecastIdaEVolta() {
        val b = Cast.CastProto.encode("sender-0", "receiver-0", "urn:x-cast:com.google.cast.tp.connection", "{\"type\":\"CONNECT\"}")
        val (ns, p) = Cast.CastProto.decode(b)
        assertEquals("urn:x-cast:com.google.cast.tp.connection", ns)
        assertEquals("{\"type\":\"CONNECT\"}", p)
        // texto grande (tamanho com mais de um byte no varint)
        val big = "x".repeat(5000)
        assertEquals(big, Cast.CastProto.decode(Cast.CastProto.encode("a", "b", "c", big)).second)
    }
}
