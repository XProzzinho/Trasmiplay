package com.geanalves.transmiplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * TVs sem o Simulplay: Chromecast (e TVs com Chromecast embutido) e Smart TVs com DLNA
 * (Samsung, LG, Sony, Philips, TCL...). Elas seguem o grupo delas o melhor possível:
 * a sincronia não é tão perfeita quanto nas TVs com Simulplay.
 */
object Cast {
    val devices = CopyOnWriteArrayList<Device>()
    private lateinit var app: Context
    @Volatile private var started = false
    private fun prefs() = app.getSharedPreferences("transmiplay", Context.MODE_PRIVATE)

    abstract class Device(val id: String, @Volatile var name: String, val kind: String) {
        @Volatile var status = ""
        val enabled: Boolean get() = Cast.prefs().getBoolean("ext_on_$id", false)
        val group: String get() = Cast.prefs().getString("ext_group_$id", null)?.takeIf { it in Hub.groups } ?: Hub.groups[0]
        fun setEnabled(on: Boolean) {
            Cast.prefs().edit().putBoolean("ext_on_$id", on).apply()
            if (!on) exec.execute { try { stop() } catch (_: Exception) {}; loaded = -1 }
            Hub.notifyUi()
        }
        fun setGroup(g: String) { Cast.prefs().edit().putString("ext_group_$id", g).apply(); Hub.notifyUi() }
        val label: String get() = if (kind == "chromecast") "Chromecast" else "Smart TV (DLNA)"

        // quem segue o quê
        @Volatile var loaded = -1
        var lastPlaying: Boolean? = null
        var lastCheck = 0L
        val exec = Executors.newSingleThreadExecutor()

        abstract fun load(url: String, mime: String, title: String, startMs: Long, play: Boolean)
        abstract fun play()
        abstract fun pause()
        abstract fun seek(ms: Long)
        abstract fun stop()
        abstract fun positionMs(): Long?
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        thread(name = "ssdp", isDaemon = true) {
            while (started) { try { searchDlna() } catch (_: Exception) {}; Thread.sleep(30_000) }
        }
        try { discoverCast() } catch (_: Exception) {}
        thread(name = "cast-follow", isDaemon = true) {
            while (started) {
                for (d in devices) if (d.enabled) d.exec.execute { try { follow(d) } catch (e: Exception) { d.status = "sem resposta" } }
                Thread.sleep(1000)
            }
        }
    }

    fun refresh() { thread(isDaemon = true) { try { searchDlna() } catch (_: Exception) {} } }

    // ------------------------------------------------------------------ seguir o grupo

    private fun follow(d: Device) {
        val s = Hub.sessionFor(d.group)
        val cur = s.current
        if (cur == null || (Hub.live && Hub.liveGroup == d.group)) {
            if (d.loaded != -1) { d.loaded = -1; d.stop() }
            d.status = if (Hub.live && Hub.liveGroup == d.group) "não recebe o som/tela ao vivo" else "pronta"
            return
        }
        if (cur.kind == "youtube") {
            if (d.loaded != -1) { d.loaded = -1; d.stop() }
            d.status = "YouTube só toca nas TVs com Simulplay"
            return
        }
        val want = s.playing && Hub.now() >= s.baseAt
        if (d.loaded != s.sessionId) {
            val url = urlFor(cur, s) ?: run { d.status = "sem Wi-Fi"; return }
            d.status = "abrindo…"
            d.load(url, mimeFor(cur), cur.title, s.position(), want)
            d.loaded = s.sessionId
            d.lastPlaying = want
            d.lastCheck = SystemClock.elapsedRealtime() + 4000
            d.status = "tocando"
            return
        }
        if (d.lastPlaying != want) {
            if (want) { d.seek(s.position()); d.play() } else d.pause()
            d.lastPlaying = want
            d.lastCheck = SystemClock.elapsedRealtime() + 3000
        }
        val now = SystemClock.elapsedRealtime()
        if (now > d.lastCheck) {
            d.lastCheck = now + 3000
            val p = d.positionMs() ?: return
            val target = s.position()
            if (abs(p - target) > 2500) d.seek(target + 800)
            d.status = if (want) "tocando" else "pausada"
        }
    }

    private fun urlFor(it: Hub.Item, s: Hub.Session): String? {
        val ip = Hub.localIp() ?: return null
        val base = "http://$ip:${Proto.HTTP_PORT}"
        return when {
            it.uri != null -> "$base/m/${s.currentIndex}?s=${s.sessionId}"
            it.url.startsWith("file:") -> "$base/f/" + java.net.URLEncoder.encode(it.url.substringAfterLast('/'), "UTF-8")
            it.proxy || it.headers.isNotEmpty() -> "$base/p?u=" + java.net.URLEncoder.encode(it.url, "UTF-8")
            else -> it.url
        }
    }

    private fun mimeFor(it: Hub.Item): String {
        val u = it.url.substringBefore('?').lowercase()
        return when {
            it.mime == "m3u8" || u.endsWith(".m3u8") -> "application/x-mpegURL"
            it.mime == "mpd" || u.endsWith(".mpd") -> "application/dash+xml"
            u.endsWith(".mp3") -> "audio/mpeg"
            u.endsWith(".m4a") || u.endsWith(".aac") -> "audio/mp4"
            u.endsWith(".ogg") || u.endsWith(".opus") -> "audio/ogg"
            u.endsWith(".flac") -> "audio/flac"
            u.endsWith(".wav") -> "audio/wav"
            u.endsWith(".webm") -> "video/webm"
            u.endsWith(".mkv") -> "video/x-matroska"
            it.uri != null -> try { Hub.app.contentResolver.getType(it.uri) } catch (_: Exception) { null } ?: "audio/mpeg"
            else -> "video/mp4"
        }
    }

    private fun add(d: Device) {
        val old = devices.firstOrNull { it.id == d.id }
        if (old != null) { old.name = d.name; return }
        devices += d
        Hub.notifyUi()
    }

    // ================================================================== DLNA / UPnP

    private fun searchDlna() {
        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
            "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n").toByteArray()
        val sock = DatagramSocket()
        sock.soTimeout = 800
        val locations = mutableSetOf<String>()
        try {
            val addr = InetAddress.getByName("239.255.255.250")
            repeat(2) { sock.send(DatagramPacket(msg, msg.size, addr, 1900)) }
            val end = SystemClock.elapsedRealtime() + 3500
            val buf = ByteArray(4096)
            while (SystemClock.elapsedRealtime() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    String(p.data, 0, p.length).lineSequence()
                        .firstOrNull { it.startsWith("location:", true) }
                        ?.substringAfter(':')?.trim()?.let { locations += it }
                } catch (_: java.net.SocketTimeoutException) {}
            }
        } finally { sock.close() }
        for (loc in locations) try { describe(loc)?.let { add(it) } } catch (_: Exception) {}
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 4000
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name>([\\s\\S]*?)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()

    /** lê a descrição do aparelho e acha onde mandar os comandos */
    fun describe(location: String, xml: String = httpGet(location)): Dlna? {
        val name = tag(xml, "friendlyName")?.let { unescape(it) } ?: return null
        val udn = tag(xml, "UDN") ?: location
        val services = Regex("<(?:\\w+:)?service>([\\s\\S]*?)</(?:\\w+:)?service>").findAll(xml).map { it.groupValues[1] }
        val av = services.firstOrNull { tag(it, "serviceType")?.contains("AVTransport") == true } ?: return null
        val control = tag(av, "controlURL") ?: return null
        val base = tag(xml, "URLBase")?.takeIf { it.startsWith("http") } ?: location
        return Dlna(udn, name, URL(URL(base), control).toString())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    fun msToTime(ms: Long): String { val t = (ms / 1000).coerceAtLeast(0); return "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60) }
    fun timeToMs(t: String): Long? {
        val p = t.substringBefore('.').split(":").mapNotNull { it.toLongOrNull() }
        if (p.size != 3) return null
        return (p[0] * 3600 + p[1] * 60 + p[2]) * 1000
    }

    class Dlna(udn: String, name: String, private val control: String) : Device(udn, name, "dlna") {
        private val st = "urn:schemas-upnp-org:service:AVTransport:1"

        private fun soap(action: String, args: String): String {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$st\">" +
                "<InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
            val c = URL(control).openConnection() as HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 5000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$st#$action\"")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val res = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw IllegalStateException("DLNA $action: $code")
            return res
        }

        override fun load(url: String, mime: String, title: String, startMs: Long, play: Boolean) {
            val cls = if (mime.startsWith("audio")) "object.item.audioItem.musicTrack" else "object.item.videoItem"
            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>${esc(title)}</dc:title><upnp:class>$cls</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"
            try { soap("Stop", "") } catch (_: Exception) {}
            soap("SetAVTransportURI", "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
            soap("Play", "<Speed>1</Speed>")
            if (startMs > 5000) { Thread.sleep(1500); try { seek(startMs + 1500) } catch (_: Exception) {} }
            if (!play) try { pause() } catch (_: Exception) {}
        }
        override fun play() { soap("Play", "<Speed>1</Speed>") }
        override fun pause() { soap("Pause", "") }
        override fun seek(ms: Long) { soap("Seek", "<Unit>REL_TIME</Unit><Target>${msToTime(ms)}</Target>") }
        override fun stop() { soap("Stop", "") }
        override fun positionMs(): Long? = tag(soap("GetPositionInfo", ""), "RelTime")?.let { timeToMs(it) }

        private fun tag(xml: String, n: String) = Regex("<$n>([^<]*)</$n>").find(xml)?.groupValues?.get(1)
    }

    // ================================================================== Chromecast

    private fun discoverCast() {
        if (Build.VERSION.SDK_INT < 21) return
        val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        val resolveQueue = Executors.newSingleThreadExecutor()
        nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onStartDiscoveryFailed(t: String?, e: Int) {}
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onServiceLost(info: NsdServiceInfo?) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                // o Android só resolve um por vez
                resolveQueue.execute {
                    val lock = Object()
                    var done = false
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo?, e: Int) { synchronized(lock) { done = true; lock.notifyAll() } }
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            try {
                                val attrs = i.attributes
                                val fn = attrs["fn"]?.let { String(it) } ?: i.serviceName
                                val id = attrs["id"]?.let { String(it) } ?: i.serviceName
                                @Suppress("DEPRECATION")
                                val host = i.host?.hostAddress
                                if (host != null) add(Chromecast("cast:$id", fn, host, i.port))
                            } catch (_: Exception) {}
                            synchronized(lock) { done = true; lock.notifyAll() }
                        }
                    })
                    synchronized(lock) { if (!done) lock.wait(5000) }
                }
            }
        })
    }

    /** mensagem do Chromecast (protobuf CastMessage, montado à mão) */
    object CastProto {
        private fun varint(o: ByteArrayOutputStream, v: Long) {
            var x = v
            while (true) {
                if (x and 0x7F.inv().toLong() == 0L) { o.write(x.toInt()); return }
                o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
            }
        }
        private fun str(o: ByteArrayOutputStream, field: Int, s: String) {
            val b = s.toByteArray()
            o.write(field shl 3 or 2); varint(o, b.size.toLong()); o.write(b)
        }
        fun encode(src: String, dst: String, ns: String, payload: String): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(0x08); varint(o, 0)
            str(o, 2, src); str(o, 3, dst); str(o, 4, ns)
            o.write(0x28); varint(o, 0)
            str(o, 6, payload)
            return o.toByteArray()
        }
        /** devolve (namespace, texto) */
        fun decode(b: ByteArray): Pair<String, String> {
            var i = 0
            var ns = ""; var payload = ""
            fun readVar(): Long { var r = 0L; var sh = 0; while (true) { val c = b[i++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl sh); if (c < 0x80) return r; sh += 7 } }
            while (i < b.size) {
                val key = readVar().toInt()
                val f = key ushr 3
                when (key and 7) {
                    0 -> readVar()
                    2 -> {
                        val n = readVar().toInt()
                        val s = String(b, i, n); i += n
                        if (f == 4) ns = s
                        if (f == 6) payload = s
                    }
                    else -> return ns to payload
                }
            }
            return ns to payload
        }
    }

    class Chromecast(id: String, name: String, private val host: String, private val port: Int) : Device(id, name, "chromecast") {
        private val nsConn = "urn:x-cast:com.google.cast.tp.connection"
        private val nsBeat = "urn:x-cast:com.google.cast.tp.heartbeat"
        private val nsRecv = "urn:x-cast:com.google.cast.receiver"
        private val nsMedia = "urn:x-cast:com.google.cast.media"
        private var sock: SSLSocket? = null
        private var out: DataOutputStream? = null
        private var req = 1
        @Volatile private var transport: String? = null
        @Volatile private var mediaSession = 0
        @Volatile private var posMs = 0L
        @Volatile private var posAt = 0L
        @Volatile private var isPlaying = false

        @Synchronized private fun send(dst: String, ns: String, j: JSONObject) {
            val o = out ?: return
            val b = CastProto.encode("sender-tp", dst, ns, j.toString())
            o.writeInt(b.size); o.write(b); o.flush()
        }

        private fun connect() {
            if (sock?.isClosed == false && out != null) return
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), null)
            val s = ctx.socketFactory.createSocket() as SSLSocket
            s.connect(InetSocketAddress(host, port), 4000)
            s.startHandshake()
            sock = s
            out = DataOutputStream(s.outputStream)
            transport = null; mediaSession = 0
            send("receiver-0", nsConn, JSONObject().put("type", "CONNECT"))
            val inp = DataInputStream(s.inputStream)
            thread(name = "cast-read", isDaemon = true) {
                try {
                    while (true) {
                        val n = inp.readInt()
                        if (n <= 0 || n > 1_000_000) break
                        val b = ByteArray(n); inp.readFully(b)
                        val (ns, p) = CastProto.decode(b)
                        onMessage(ns, p)
                    }
                } catch (_: Exception) {}
                try { s.close() } catch (_: Exception) {}
                out = null
            }
            thread(name = "cast-beat", isDaemon = true) {
                try { while (!s.isClosed) { send("receiver-0", nsBeat, JSONObject().put("type", "PING")); Thread.sleep(5000) } } catch (_: Exception) {}
            }
        }

        private fun onMessage(ns: String, p: String) {
            val j = try { JSONObject(p) } catch (_: Exception) { return }
            when (j.optString("type")) {
                "PING" -> try { send("receiver-0", nsBeat, JSONObject().put("type", "PONG")) } catch (_: Exception) {}
                "RECEIVER_STATUS" -> {
                    val apps = j.optJSONObject("status")?.optJSONArray("applications")
                    val a = apps?.optJSONObject(0)
                    if (a != null && a.optString("appId") == APP) transport = a.optString("transportId")
                }
                "MEDIA_STATUS" -> {
                    val st = j.optJSONArray("status")?.optJSONObject(0) ?: return
                    if (st.has("mediaSessionId")) mediaSession = st.optInt("mediaSessionId")
                    if (st.has("currentTime")) { posMs = (st.optDouble("currentTime") * 1000).toLong(); posAt = SystemClock.elapsedRealtime() }
                    isPlaying = st.optString("playerState") == "PLAYING"
                }
                "CLOSE" -> { transport = null; mediaSession = 0 }
            }
        }

        private fun ensureApp(): String {
            connect()
            transport?.let { return it }
            send("receiver-0", nsRecv, JSONObject().put("type", "LAUNCH").put("appId", APP).put("requestId", req++))
            val end = SystemClock.elapsedRealtime() + 8000
            while (transport == null && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            val t = transport ?: throw IllegalStateException("Chromecast não abriu o player")
            send(t, nsConn, JSONObject().put("type", "CONNECT"))
            return t
        }

        private fun media(type: String, extra: JSONObject.() -> Unit = {}) {
            val t = ensureApp()
            if (mediaSession == 0) return
            send(t, nsMedia, JSONObject().put("type", type).put("requestId", req++).put("mediaSessionId", mediaSession).apply(extra))
        }

        override fun load(url: String, mime: String, title: String, startMs: Long, play: Boolean) {
            val t = ensureApp()
            mediaSession = 0
            val m = JSONObject().put("contentId", url).put("contentUrl", url).put("streamType", "BUFFERED").put("contentType", mime)
                .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
            send(t, nsMedia, JSONObject().put("type", "LOAD").put("requestId", req++).put("media", m)
                .put("autoplay", play).put("currentTime", startMs / 1000.0))
            val end = SystemClock.elapsedRealtime() + 6000
            while (mediaSession == 0 && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        }
        override fun play() = media("PLAY")
        override fun pause() = media("PAUSE")
        override fun seek(ms: Long) = media("SEEK") { put("currentTime", ms / 1000.0) }
        override fun stop() { if (mediaSession != 0) media("STOP"); mediaSession = 0 }
        override fun positionMs(): Long? {
            media("GET_STATUS")
            Thread.sleep(300)
            if (posAt == 0L) return null
            return posMs + if (isPlaying) SystemClock.elapsedRealtime() - posAt else 0
        }

        companion object { const val APP = "CC1AD845" } // player padrão do Chromecast
    }
}
