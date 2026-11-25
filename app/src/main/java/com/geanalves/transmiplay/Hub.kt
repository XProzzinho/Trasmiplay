package com.geanalves.transmiplay

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * O "cérebro" do Transmiplay: guarda o relógio mestre, a lista de TVs
 * conectadas e o que está tocando. As TVs seguem esse relógio.
 */
object Hub {
    data class Item(
        val kind: String,          // "media" (arquivo/link) ou "youtube"
        val url: String,           // link, id do YouTube ou caminho local
        val title: String,
        val uri: Uri? = null,      // música do celular
        @Volatile var durMs: Long = 0,
        val headers: Map<String, String> = emptyMap(), // Referer, cookies... que o site exige
        val mime: String? = null,  // "m3u8", "mpd", "mp4"...
        val proxy: Boolean = false,// passa o vídeo pelo celular
        @Volatile var sub: Sub? = null,
        val thumb: String? = null,
    )

    data class Sub(val url: String, val mime: String, val local: Boolean)

    lateinit var app: Context
        private set
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    @Volatile private var started = false

    val tvs = CopyOnWriteArrayList<TvConn>()
    val tracks = CopyOnWriteArrayList<Item>()
    private val audioSinks = CopyOnWriteArrayList<AudioSink>()

    @Volatile var current: Item? = null; private set
    @Volatile var currentIndex = -1; private set
    @Volatile var sessionId = 0; private set
    @Volatile var playing = false; private set
    @Volatile var basePos = 0L; private set
    @Volatile var baseAt = 0L; private set
    /** atraso do modo caixinha (ms): 300, ou o atraso da caixa Bluetooth do celular */
    @Volatile var audioDelay = Proto.AUDIO_DELAY_MS
    @Volatile private var endedHandled = false
    @Volatile var caixinha = false; private set
    @Volatile var lastError: String? = null
    /** legenda escolhida antes de transmitir */
    @Volatile var pendingSub: Sub? = null
    /** arquivo de legenda do celular servido em /sub */
    @Volatile var subUri: Uri? = null

    /** 0 automática, 1 alta (Full HD), 2 economia */
    val quality: Int get() = prefs().getInt("quality", 0)
    val autoSync: Boolean get() = prefs().getBoolean("auto_sync", true)

    fun setQuality(q: Int) { prefs().edit().putInt("quality", q).apply(); sendAll(cfgMsg()) }
    fun setAutoSync(on: Boolean) { prefs().edit().putBoolean("auto_sync", on).apply(); sendAll(cfgMsg()) }
    private fun cfgMsg() = JSONObject().put("t", "cfg").put("quality", quality).put("sync", autoSync)

    fun skip(deltaMs: Long) = seek((position() + deltaMs).coerceAtLeast(0))
    val activeTvs: Int get() = tvs.count { it.enabled }

    var listener: (() -> Unit)? = null
    fun notifyUi() = main.post { listener?.invoke() }

    private var controlServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null
    private var http: HttpServer? = null
    private var discovery: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun now(): Long = SystemClock.elapsedRealtime()

    fun position(): Long {
        val p = if (playing) basePos + (now() - baseAt).coerceAtLeast(0) else basePos
        val d = current?.durMs ?: 0
        return if (d > 0) p.coerceAtMost(d) else p
    }

    // ---------------------------------------------------------------- início

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        PhoneSpeaker.init(app)
        val ht = HandlerThread("hub").apply { start() }
        worker = Handler(ht.looper)
        loadSavedTracks()

        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("transmiplay").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}

        thread(name = "control", isDaemon = true) { runControlServer() }
        thread(name = "audio", isDaemon = true) { runAudioServer() }
        thread(name = "discovery", isDaemon = true) { runDiscovery() }
        http = HttpServer(Proto.HTTP_PORT).also { it.start() }
        worker.post(object : Runnable {
            override fun run() {
                tick()
                worker.postDelayed(this, 500)
            }
        })
    }

    fun shutdown() {
        started = false
        try { controlServer?.close() } catch (_: Exception) {}
        try { audioServer?.close() } catch (_: Exception) {}
        try { discovery?.close() } catch (_: Exception) {}
        http?.stop()
        tvs.forEach { it.close() }
        audioSinks.forEach { it.close() }
        try { multicastLock?.release() } catch (_: Exception) {}
    }

    private var tickCount = 0
    private fun tick() {
        val cur = current
        if (cur != null && playing && cur.durMs > 0 && position() >= cur.durMs && now() > baseAt) {
            if (basePos + (now() - baseAt) > cur.durMs + 400) onEnded(sessionId)
        }
        tickCount++
        if (tickCount % 2 == 0 && cur != null) sendAll(stateMsg())
    }

    // ---------------------------------------------------------------- descoberta

    private fun runDiscovery() {
        try {
            val sock = DatagramSocket().apply { broadcast = true }
            discovery = sock
            while (started) {
                val msg = "TRANSMIPLAY|${deviceName()}|${Proto.CONTROL_PORT}".toByteArray()
                for (addr in broadcastAddresses()) {
                    try { sock.send(DatagramPacket(msg, msg.size, addr, Proto.DISCOVERY_PORT)) } catch (_: Exception) {}
                }
                Thread.sleep(1000)
            }
        } catch (_: Exception) {}
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try { list += InetAddress.getByName("255.255.255.255") } catch (_: Exception) {}
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { if (it !in list) list += it }
            }
        } catch (_: Exception) {}
        return list
    }

    fun deviceName(): String {
        return try {
            android.provider.Settings.Global.getString(app.contentResolver, "device_name")
        } catch (_: Exception) { null } ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    // ---------------------------------------------------------------- comandos com as TVs

    private fun runControlServer() {
        try {
            val ss = ServerSocket(Proto.CONTROL_PORT).apply { reuseAddress = true }
            controlServer = ss
            while (started) {
                val s = ss.accept()
                s.tcpNoDelay = true
                val tv = TvConn(s)
                tvs += tv
                thread(name = "tv-read", isDaemon = true) { tv.readLoop() }
            }
        } catch (e: Exception) {
            if (started) { lastError = "Porta ocupada: ${e.message}"; notifyUi() }
        }
    }

    /** qualquer coisa que toca junto (TV ou o próprio celular) */
    interface Syncable {
        val name: String
        val adj: Int
        fun changeAdj(delta: Int)
        fun beep(at: Long, freq: Double)
    }

    class TvConn(private val socket: Socket) : Syncable {
        @Volatile override var name: String = socket.inetAddress.hostAddress ?: "TV"
        @Volatile override var adj: Int = 0

        override fun beep(at: Long, freq: Double) =
            send(JSONObject().put("t", "beep").put("at", at).put("freq", freq).put("ms", 120))
        @Volatile var enabled: Boolean = true
        /** caixa de som Bluetooth dessa TV (o que a TV contou por último) */
        @Volatile var btState: JSONObject? = null
        @Volatile var btMsg: String = ""

        fun bt(op: String, addr: String = "") = send(JSONObject().put("t", "bt").put("op", op).put("addr", addr))
        private val out: OutputStream = socket.getOutputStream()

        fun send(j: JSONObject) {
            val bytes = (j.toString() + "\n").toByteArray()
            try { synchronized(out) { out.write(bytes); out.flush() } } catch (_: Exception) { close() }
        }

        fun close() {
            try { socket.close() } catch (_: Exception) {}
            if (tvs.remove(this)) notifyUi()
        }

        fun readLoop() {
            try {
                val r = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (true) {
                    val line = r.readLine() ?: break
                    val j = try { JSONObject(line) } catch (_: Exception) { continue }
                    when (j.optString("t")) {
                        "ping" -> send(JSONObject().put("t", "pong").put("c", j.optLong("c")).put("s", now()))
                        "hello" -> {
                            name = j.optString("name", name)
                            adj = prefs().getInt("adj_$name", 0)
                            enabled = prefs().getBoolean("on_$name", true)
                            send(JSONObject().put("t", "adj").put("ms", adj))
                            send(cfgMsg())
                            if (enabled) {
                                current?.let { send(loadMsg(it)); send(stateMsg()) }
                                if (caixinha) send(audioMsg(true))
                            }
                            notifyUi()
                        }
                        "info" -> onInfo(j.optInt("id"), j.optLong("dur"))
                        "ended" -> onEnded(j.optInt("id"))
                        "error" -> onTvError(this, j)
                        "bt" -> {
                            j.optJSONObject("state")?.let { btState = it }
                            if (j.has("msg")) btMsg = j.optString("msg")
                            notifyUi()
                        }
                    }
                }
            } catch (_: Exception) {}
            close()
        }

        fun setOn(on: Boolean) {
            enabled = on
            prefs().edit().putBoolean("on_$name", on).apply()
            if (on) {
                current?.let { send(loadMsg(it)); send(stateMsg()) }
                if (caixinha) send(audioMsg(true))
            } else {
                send(JSONObject().put("t", "stop"))
                send(audioMsg(false))
            }
            notifyUi()
        }

        override fun changeAdj(delta: Int) {
            adj = (adj + delta).coerceIn(-1000, 1000)
            prefs().edit().putInt("adj_$name", adj).apply()
            send(JSONObject().put("t", "adj").put("ms", adj))
            notifyUi()
        }
    }

    private fun prefs() = app.getSharedPreferences("transmiplay", Context.MODE_PRIVATE)

    private fun sendAll(j: JSONObject) = tvs.forEach { if (it.enabled) it.send(j) }

    private fun loadMsg(it: Item): JSONObject {
        val j = JSONObject().put("t", "load").put("id", sessionId).put("kind", it.kind).put("title", it.title)
        when {
            it.uri != null -> j.put("local", true).put("url", "/m/$currentIndex?s=$sessionId")
            it.proxy -> j.put("local", true).put("url", "/p?u=" + java.net.URLEncoder.encode(it.url, "UTF-8"))
            else -> {
                j.put("local", false).put("url", it.url)
                if (it.headers.isNotEmpty()) j.put("headers", JSONObject(it.headers))
            }
        }
        it.mime?.let { m -> j.put("mime", m) }
        it.sub?.let { sb ->
            j.put("sub", if (sb.local) "/sub?s=$sessionId-${sb.url.hashCode()}" else sb.url)
                .put("subLocal", sb.local).put("subMime", sb.mime)
        }
        return j
    }

    private fun stateMsg() = JSONObject().put("t", "state").put("id", sessionId)
        .put("playing", playing).put("pos", basePos).put("at", baseAt)

    private fun audioMsg(on: Boolean) = JSONObject().put("t", "audio").put("on", on).put("delay", audioDelay)

    // ---------------------------------------------------------------- tocar

    private fun runOnWorker(r: () -> Unit) = worker.post(r)

    fun playLink(raw: String) = runOnWorker {
        val url = raw.trim()
        val yt = youtubeId(url)
        val item = if (yt != null) Item("youtube", yt, "YouTube")
        else Item("media", url, Uri.parse(url).lastPathSegment?.take(60) ?: url)
        load(item, -1)
    }

    /** vídeo achado no navegador */
    fun playItem(item: Item) = runOnWorker {
        if (item.kind == "media" && item.sub == null) item.sub = pendingSub
        load(item, -1)
    }

    /** troca a legenda do que está tocando (ou guarda pra próxima vez) */
    fun setSubtitle(sub: Sub?) = runOnWorker {
        pendingSub = sub
        val cur = current ?: return@runOnWorker
        if (cur.kind != "media") return@runOnWorker
        cur.sub = sub
        sendAll(loadMsg(cur))
        sendAll(stateMsg())
        notifyUi()
    }

    fun playTrack(index: Int) = runOnWorker {
        val t = tracks.getOrNull(index) ?: return@runOnWorker
        load(t, index)
        if (t.durMs == 0L && t.uri != null) thread(isDaemon = true) {
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(app, t.uri)
                t.durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                mmr.release()
                notifyUi()
            } catch (_: Exception) {}
        }
    }

    private fun load(item: Item, index: Int) {
        if (caixinha) CaptureService.stop(app)
        sessionId++
        val sid = sessionId
        current = item
        currentIndex = index
        playing = false
        basePos = 0
        baseAt = now()
        endedHandled = false
        sendAll(loadMsg(item))
        sendAll(stateMsg())
        notifyUi()
        // dá um tempinho pras TVs carregarem e começa todo mundo junto
        worker.postDelayed({ if (sessionId == sid) doPlay() }, 1200)
    }

    fun togglePlay() = runOnWorker { if (playing) doPause() else doPlay() }

    private fun doPlay() {
        val cur = current ?: return
        var p = position()
        if (cur.durMs > 0 && p >= cur.durMs - 500) p = 0
        basePos = p
        baseAt = now() + Proto.START_LEAD_MS
        playing = true
        endedHandled = false
        sendAll(stateMsg())
        notifyUi()
    }

    private fun doPause() {
        basePos = position()
        playing = false
        baseAt = now()
        sendAll(stateMsg())
        notifyUi()
    }

    fun seek(ms: Long) = runOnWorker {
        if (current == null) return@runOnWorker
        basePos = ms.coerceAtLeast(0)
        baseAt = if (playing) now() + Proto.START_LEAD_MS else now()
        endedHandled = false
        sendAll(stateMsg())
        notifyUi()
    }

    fun next() = runOnWorker {
        if (currentIndex >= 0 && currentIndex + 1 < tracks.size) playTrack(currentIndex + 1)
    }

    fun prev() = runOnWorker {
        if (position() > 3000 || currentIndex <= 0) seek(0) else playTrack(currentIndex - 1)
    }

    fun stopAll() = runOnWorker {
        current = null
        currentIndex = -1
        playing = false
        sessionId++
        sendAll(JSONObject().put("t", "stop"))
        notifyUi()
    }

    /** a TV não conseguiu abrir: tenta sozinho passando o vídeo pelo celular */
    private fun onTvError(tv: TvConn, j: JSONObject) = runOnWorker {
        val cur = current
        val id = j.optInt("id", -1)
        val http = j.optInt("http")
        if (id != -1 && id != sessionId) return@runOnWorker // erro de um vídeo antigo
        if (cur != null && cur.kind == "media" && cur.uri == null && !cur.proxy) {
            lastError = "A TV não abriu direto. Tentando passar o vídeo pelo celular…"
            notifyUi()
            val pos = position()
            load(cur.copy(proxy = true), currentIndex)
            if (pos > 3000) worker.postDelayed({ seek(pos) }, 200)
            return@runOnWorker
        }
        val why = when {
            http == 403 || http == 401 -> "o site não deixou outro aparelho abrir o vídeo (erro $http)"
            http == 404 || http == 410 -> "o link do vídeo expirou (erro $http). Dê play de novo no site e mande outra vez"
            http >= 500 -> "o servidor do vídeo está com problema (erro $http)"
            j.optString("name").contains("PARSING") || j.optString("name").contains("DECODING") ->
                "o formato desse vídeo não é suportado pela TV"
            j.optString("name").contains("DRM") -> "o vídeo tem proteção (DRM)"
            else -> j.optString("msg")
        }
        lastError = "${tv.name}: $why"
        notifyUi()
    }

    private fun onInfo(id: Int, dur: Long) {
        val cur = current ?: return
        if (id == sessionId && dur > 0 && cur.durMs == 0L) { cur.durMs = dur; notifyUi() }
    }

    private fun onEnded(id: Int) = runOnWorker {
        if (id != sessionId || endedHandled || !playing) return@runOnWorker
        endedHandled = true
        if (currentIndex >= 0 && currentIndex + 1 < tracks.size) playTrack(currentIndex + 1)
        else {
            basePos = current?.durMs ?: position()
            playing = false
            sendAll(stateMsg())
            notifyUi()
        }
    }

    // ---------------------------------------------------------------- minhas músicas

    fun addTracks(uris: List<Uri>) = runOnWorker {
        val firstNew = tracks.size
        for (u in uris) {
            if (tracks.any { it.uri == u }) continue
            tracks += Item("media", u.toString(), displayName(u), u)
        }
        saveTracks()
        notifyUi()
        if (current == null && tracks.size > firstNew) playTrack(firstNew)
    }

    fun removeTrack(index: Int) = runOnWorker {
        if (index !in tracks.indices) return@runOnWorker
        if (index == currentIndex) stopAll()
        tracks.removeAt(index)
        if (currentIndex > index) currentIndex--
        saveTracks()
        notifyUi()
    }

    private fun displayName(u: Uri): String {
        try {
            app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0).substringBeforeLast('.')
            }
        } catch (_: Exception) {}
        return u.lastPathSegment ?: "Música"
    }

    private fun saveTracks() {
        prefs().edit().putString("tracks", tracks.mapNotNull { it.uri?.toString() }.joinToString("\n")).apply()
    }

    private fun loadSavedTracks() {
        val saved = prefs().getString("tracks", "") ?: ""
        val perms = app.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        for (s in saved.split("\n")) {
            if (s.isBlank() || s !in perms) continue
            val u = Uri.parse(s)
            tracks += Item("media", s, displayName(u), u)
        }
    }

    // ---------------------------------------------------------------- caixinha (som ao vivo)

    fun setCaixinha(on: Boolean) = runOnWorker {
        if (on == caixinha) return@runOnWorker
        caixinha = on
        if (on && current != null) {
            current = null; currentIndex = -1; playing = false; sessionId++
            sendAll(JSONObject().put("t", "stop"))
        }
        sendAll(audioMsg(on))
        if (!on) audioSinks.forEach { it.close() }
        notifyUi()
    }

    fun pushAudio(packet: ByteArray) {
        for (s in audioSinks) s.offer(packet)
    }

    private fun runAudioServer() {
        try {
            val ss = ServerSocket(Proto.AUDIO_PORT).apply { reuseAddress = true }
            audioServer = ss
            while (started) {
                val s = ss.accept()
                s.tcpNoDelay = true
                s.sendBufferSize = 64 * 1024
                audioSinks += AudioSink(s)
            }
        } catch (_: Exception) {}
    }

    class AudioSink(private val socket: Socket) {
        private val q = ArrayBlockingQueue<ByteArray>(60)
        init {
            thread(name = "audio-out", isDaemon = true) {
                try {
                    val out = socket.getOutputStream()
                    while (true) out.write(q.take())
                } catch (_: Exception) {}
                close()
            }
        }
        fun offer(p: ByteArray) { if (!q.offer(p)) { q.poll(); q.offer(p) } }
        fun close() {
            try { socket.close() } catch (_: Exception) {}
            audioSinks.remove(this)
        }
    }

    // ---------------------------------------------------------------- utilidades

    fun youtubeId(url: String): String? {
        val patterns = listOf(
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})"""),
        )
        if (!url.contains("youtu")) return null
        for (p in patterns) p.find(url)?.let { return it.groupValues[1] }
        return null
    }
}
