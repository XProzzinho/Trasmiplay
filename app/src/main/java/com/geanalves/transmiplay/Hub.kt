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
        @Volatile var title: String,
        val uri: Uri? = null,      // música do celular
        @Volatile var durMs: Long = 0,
        val headers: Map<String, String> = emptyMap(), // Referer, cookies... que o site exige
        val mime: String? = null,  // "m3u8", "mpd", "mp4"...
        val proxy: Boolean = false,// passa o vídeo pelo celular
        @Volatile var sub: Sub? = null,
        val thumb: String? = null,
        val by: String? = null,    // quem pôs na fila (modo festa)
    )

    data class Sub(val url: String, val mime: String, val local: Boolean)

    lateinit var app: Context
        private set
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    @Volatile private var started = false

    val tvs = CopyOnWriteArrayList<TvConn>()
    val tracks = CopyOnWriteArrayList<Item>()
    // ---------------------------------------------------------------- grupos de TVs
    /** o que um grupo de TVs está tocando (cada grupo toca uma coisa) */
    class Session(val group: String) {
        @Volatile var current: Item? = null
        @Volatile var currentIndex = -1
        @Volatile var sessionId = 0
        @Volatile var playing = false
        @Volatile var basePos = 0L
        @Volatile var baseAt = 0L
        @Volatile var endedHandled = false
        val queue = CopyOnWriteArrayList<Item>()
        fun position(): Long {
            val p = if (playing) basePos + (SystemClock.elapsedRealtime() - baseAt).coerceAtLeast(0) else basePos
            val d = current?.durMs ?: 0
            return if (d > 0) p.coerceAtMost(d) else p
        }
    }

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    @Volatile private var idSeq = 0
    /** grupo que a tela está controlando agora */
    @Volatile var active: Session = Session("Principal"); private set
    fun sessionFor(g: String): Session = sessions.getOrPut(g) { Session(g) }
    fun allSessions(): List<Session> = sessions.values.toList()

    val groups: List<String> get() = try {
        val a = org.json.JSONArray(prefs().getString("groups", "[\"Principal\"]"))
        (0 until a.length()).map { a.getString(it) }.ifEmpty { listOf("Principal") }
    } catch (_: Exception) { listOf("Principal") }
    private fun saveGroups(l: List<String>) = prefs().edit().putString("groups", org.json.JSONArray(l).toString()).apply()

    fun selectGroup(g: String) { active = sessionFor(g); prefs().edit().putString("active_group", g).apply(); notifyUi() }
    fun addGroup(name: String): Boolean {
        val n = name.trim().take(30)
        if (n.isBlank() || n in groups) return false
        saveGroups(groups + n); selectGroup(n); return true
    }
    fun removeGroup(g: String) = runOnWorker {
        val l = groups
        if (l.size <= 1 || g !in l) return@runOnWorker
        val rest = l - g
        saveGroups(rest)
        val s = sessionFor(g)
        s.current = null; s.playing = false; s.sessionId = ++idSeq; s.queue.clear()
        tvs.filter { it.group == g }.forEach { moveTvNow(it, rest[0]) }
        Cast.devices.filter { it.group == g }.forEach { it.setGroup(rest[0]) }
        sessions.remove(g)
        if (active.group == g) active = sessionFor(rest[0])
        notifyUi()
    }
    fun groupTvs(): List<TvConn> = tvs.filter { it.group == active.group }
    fun moveTv(tv: TvConn, g: String) = runOnWorker { moveTvNow(tv, g); notifyUi() }
    private fun moveTvNow(tv: TvConn, g: String) {
        if (tv.group == g) return
        tv.send(JSONObject().put("t", "stop")); tv.send(audioMsg(false)); tv.send(JSONObject().put("t", "mirror").put("on", false))
        tv.group = g
        prefs().edit().putString("group_${tv.name}", g).apply()
        if (tv.enabled) inSession(sessionFor(g)) { tv.sendCurrent() }
    }

    /** roda f com outro grupo como "o atual" (no worker) */
    private inline fun inSession(s: Session, f: () -> Unit) {
        val prev = active
        active = s
        try { f() } finally { active = prev }
    }

    /** comando vindo de uma TV vale pro grupo dela */
    private fun forGroup(g: String, f: () -> Unit) {
        var prev: Session? = null
        runOnWorker { prev = active; active = sessionFor(g) }
        f()
        runOnWorker { prev?.let { active = it } }
    }

    /** fila "a seguir" do grupo atual */
    val queue: CopyOnWriteArrayList<Item> get() = active.queue
    /** lista de canais (IPTV) que acabou de abrir: a tela mostra pra escolher */
    var onPlaylist: ((String, List<Iptv.Channel>) -> Unit)? = null
    private val audioSinks = CopyOnWriteArrayList<AudioSink>()

    var current: Item? get() = active.current; private set(v) { active.current = v }
    var currentIndex: Int get() = active.currentIndex; private set(v) { active.currentIndex = v }
    var sessionId: Int get() = active.sessionId; private set(v) { active.sessionId = v }
    var playing: Boolean get() = active.playing; private set(v) { active.playing = v }
    var basePos: Long get() = active.basePos; private set(v) { active.basePos = v }
    var baseAt: Long get() = active.baseAt; private set(v) { active.baseAt = v }
    /** atraso do modo caixinha (ms): 300, ou o atraso da caixa Bluetooth do celular */
    @Volatile var audioDelay = Proto.AUDIO_DELAY_MS
    private var endedHandled: Boolean get() = active.endedHandled; set(v) { active.endedHandled = v }
    @Volatile var caixinha = false; private set
    /** espelhando a tela */
    @Volatile var mirroring = false; private set
    /** algo ao vivo (som ou tela do celular) */
    val live: Boolean get() = caixinha || mirroring
    /** grupo que recebe o som/tela ao vivo */
    @Volatile var liveGroup = "Principal"; private set
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
    val bootStart: Boolean get() = prefs().getBoolean("boot_start", true)
    fun setBootStart(on: Boolean) { prefs().edit().putBoolean("boot_start", on).apply(); sendAll(cfgMsg()) }
    private fun cfgMsg() = JSONObject().put("t", "cfg").put("quality", quality).put("sync", autoSync).put("boot", bootStart)

    fun skip(deltaMs: Long) = runOnWorker { seekNow((position() + deltaMs).coerceAtLeast(0)) }
    val activeTvs: Int get() = tvs.count { it.enabled && it.group == active.group } +
        Cast.devices.count { it.enabled && it.group == active.group }

    var listener: (() -> Unit)? = null
    /** outros que querem saber das mudanças (notificação, tela de bloqueio) */
    val watchers = CopyOnWriteArrayList<() -> Unit>()
    fun notifyUi() = main.post { listener?.invoke(); watchers.forEach { it() } }

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
        prefs().getString("active_group", null)?.takeIf { it in groups }?.let { active = sessionFor(it) }
            ?: run { active = sessionFor(groups[0]) }
        val ht = HandlerThread("hub").apply { start() }
        worker = Handler(ht.looper)
        loadSavedTracks()

        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("transmiplay").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}

        thread(name = "control", isDaemon = true) { runControlServer() }
        thread(name = "audio", isDaemon = true) { runAudioServer() }
        thread(name = "video", isDaemon = true) { runVideoServer() }
        Cast.start(app)
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
        tickCount++
        for (s in allSessions()) inSession(s) {
            val cur = current
            if (cur != null && playing && cur.durMs > 0 && position() >= cur.durMs && now() > baseAt) {
                if (basePos + (now() - baseAt) > cur.durMs + 400) onEnded(sessionId)
            }
            if (tickCount % 2 == 0 && cur != null) sendAll(stateMsg())
            if (tickCount % 20 == 0 && cur != null && playing) savePos()
        }
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
        @Volatile var group: String = "Principal"
        /** manda pra essa TV o que o grupo dela está tocando */
        fun sendCurrent() {
            current?.let { send(loadMsg(it)); send(stateMsg()) }
            if (caixinha && liveGroup == group) send(audioMsg(true))
            if (mirroring && liveGroup == group) send(mirrorMsg(true))
        }
        /** caixa de som Bluetooth dessa TV (o que a TV contou por último) */
        @Volatile var btState: JSONObject? = null
        @Volatile var btMsg: String = ""
        /** volume dessa TV (0 a 100) */
        @Volatile var vol: Int = 100
        @Volatile var muted: Boolean = false
        fun setVol(v: Int, mute: Boolean = muted) {
            vol = v.coerceIn(0, 100); muted = mute
            prefs().edit().putInt("vol_$name", vol).putBoolean("mute_$name", muted).apply()
            send(volMsg())
            notifyUi()
        }
        private fun volMsg() = JSONObject().put("t", "vol").put("v", vol).put("mute", muted)

        /** troca o nome da TV (aparece no celular e na própria TV) */
        fun rename(newName: String) {
            val n = newName.trim().take(40)
            if (n.isBlank() || n == name) return
            val p = prefs(); val e = p.edit()
            for (k in listOf("adj_", "on_", "vol_", "mute_", "group_")) {
                val old = p.all["$k$name"] ?: continue
                when (old) { is Int -> e.putInt("$k$n", old); is Boolean -> e.putBoolean("$k$n", old); is String -> e.putString("$k$n", old) }
            }
            e.apply()
            name = n
            send(JSONObject().put("t", "name").put("name", n))
            notifyUi()
        }

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
                            vol = prefs().getInt("vol_$name", 100)
                            muted = prefs().getBoolean("mute_$name", false)
                            send(JSONObject().put("t", "adj").put("ms", adj))
                            send(volMsg())
                            send(cfgMsg())
                            group = prefs().getString("group_$name", null)?.takeIf { it in groups } ?: groups[0]
                            val me = this
                            if (enabled) runOnWorker { inSession(sessionFor(group)) { me.sendCurrent() } }
                            notifyUi()
                        }
                        "info" -> onInfo(j.optInt("id"), j.optLong("dur"))
                        "ended" -> onEnded(j.optInt("id"))
                        "error" -> onTvError(this, j)
                        // controle remoto da TV
                        "key" -> forGroup(group) {
                            when (j.optString("k")) {
                                "toggle" -> togglePlay()
                                "fwd" -> skip(10_000)
                                "back" -> skip(-10_000)
                                "next" -> next()
                                "prev" -> prev()
                            }
                        }
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
            val me = this
            if (on) runOnWorker { inSession(sessionFor(group)) { me.sendCurrent() } }
            else {
                send(JSONObject().put("t", "stop"))
                send(audioMsg(false))
                send(mirrorMsg(false))
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

    private fun sendAll(j: JSONObject) = tvs.forEach { if (it.enabled && it.group == active.group) it.send(j) }
    private fun sendLive(j: JSONObject) = tvs.forEach { if (it.enabled && it.group == liveGroup) it.send(j) }

    private fun loadMsg(it: Item): JSONObject {
        val j = JSONObject().put("t", "load").put("id", sessionId).put("kind", it.kind).put("title", it.title)
        when {
            it.uri != null -> j.put("local", true).put("url", "/m/$currentIndex?s=$sessionId")
            it.url.startsWith("file:") -> j.put("local", true).put("url", "/f/" + java.net.URLEncoder.encode(it.url.substringAfterLast('/'), "UTF-8"))
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
    @Volatile var mirrorW = 0; @Volatile var mirrorH = 0
    private fun mirrorMsg(on: Boolean) = JSONObject().put("t", "mirror").put("on", on)
        .put("w", mirrorW).put("h", mirrorH).put("delay", audioDelay)

    // ---------------------------------------------------------------- tocar

    private fun runOnWorker(r: () -> Unit) = worker.post(r)

    fun playLink(raw: String, queueIt: Boolean = false) = runOnWorker {
        val url = raw.trim()
        val yt = youtubeId(url)
        if (yt == null && Iptv.maybeList(url)) {
            // pode ser uma lista de canais (IPTV): baixa e mostra os canais
            thread(isDaemon = true) {
                val list = Iptv.fetch(url)
                if (list.size > 1) {
                    rememberList(url, list.size)
                    main.post { onPlaylist?.invoke(url, list) }
                } else runOnWorker { startOrQueue(linkItem(url), queueIt) }
            }
            return@runOnWorker
        }
        val item = if (yt != null) Item("youtube", yt, "YouTube") else linkItem(url)
        if (yt != null) thread(isDaemon = true) { youtubeTitle(yt)?.let { t -> item.title = t; renameRecent(yt, t); notifyUi() } }
        startOrQueue(item, queueIt)
    }

    private fun linkItem(url: String) = Item("media", url, Uri.parse(url).lastPathSegment?.take(60) ?: url,
        mime = if (url.substringBefore('?').endsWith(".m3u8")) "m3u8" else null)

    private fun startOrQueue(item: Item, queueIt: Boolean) {
        if (queueIt && (current != null || caixinha)) { queue += item; notifyUi() } else load(item, -1)
    }

    // ---------------------------------------------------------------- fila

    /** põe na fila (se nada estiver tocando, já começa) */
    fun enqueue(item: Item) = runOnWorker { startOrQueue(item, true) }
    fun removeQueued(i: Int) = runOnWorker { if (i in queue.indices) { queue.removeAt(i); notifyUi() } }
    fun playQueued(i: Int) = runOnWorker { if (i in queue.indices) load(queue.removeAt(i), -1) }
    fun clearQueue() = runOnWorker { queue.clear(); notifyUi() }
    fun hasNext(): Boolean = (currentIndex >= 0 && currentIndex + 1 < tracks.size) || queue.isNotEmpty()

    // ---------------------------------------------------------------- recentes (continuar de onde parou)

    data class Recent(val j: JSONObject) {
        val title: String get() = j.optString("title")
        val kind: String get() = j.optString("kind")
        val pos: Long get() = j.optLong("pos")
        val dur: Long get() = j.optLong("dur")
        val thumb: String? get() = j.optString("thumb").takeIf { it.isNotBlank() }
        val url: String get() = j.optString("url")
    }

    fun recents(): List<Recent> = try {
        val a = org.json.JSONArray(prefs().getString("recents", "[]"))
        (0 until a.length()).map { Recent(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun saveRecents(l: List<JSONObject>) {
        prefs().edit().putString("recents", org.json.JSONArray(l.take(12)).toString()).apply()
    }

    private fun remember(it: Item) {
        if (it.uri != null || it.url.startsWith("file:")) return // música do celular já fica na lista dela
        val j = JSONObject().put("kind", it.kind).put("url", it.url).put("title", it.title)
            .put("proxy", it.proxy).put("pos", 0).put("dur", it.durMs).put("at", System.currentTimeMillis())
        it.mime?.let { m -> j.put("mime", m) }
        it.thumb?.let { t -> j.put("thumb", t) }
        if (it.headers.isNotEmpty()) j.put("headers", JSONObject(it.headers))
        saveRecents(listOf(j) + recents().map { r -> r.j }.filter { r -> r.optString("url") != it.url })
    }

    private fun rememberList(url: String, n: Int) {
        val j = JSONObject().put("kind", "iptv").put("url", url).put("title", "Lista de canais · $n canais")
            .put("at", System.currentTimeMillis())
        saveRecents(listOf(j) + recents().map { r -> r.j }.filter { r -> r.optString("url") != url })
    }

    /** guarda onde parou o que está tocando */
    private fun savePos() {
        val cur = current ?: return
        if (cur.uri != null) return
        val l = recents().map { it.j }
        l.firstOrNull { it.optString("url") == cur.url }?.let {
            it.put("pos", position()).put("dur", cur.durMs)
            saveRecents(l)
        }
    }

    private fun renameRecent(url: String, title: String) = runOnWorker {
        val l = recents().map { it.j }
        l.firstOrNull { it.optString("url") == url }?.let { it.put("title", title); saveRecents(l) }
    }

    /** nome do vídeo do YouTube (sem chave de API) */
    fun youtubeTitle(id: String): String? = try {
        val c = java.net.URL("https://www.youtube.com/oembed?format=json&url=https://youtu.be/$id").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).optString("title").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** cabeçalhos (Referer, cookies) do vídeo que passa pelo celular */
    fun headersFor(target: String): Map<String, String> {
        val host = try { java.net.URL(target).host } catch (_: Exception) { "" }
        val items = allSessions().mapNotNull { it.current }
        return (items.firstOrNull { it.url == target } ?: items.firstOrNull { try { java.net.URL(it.url).host == host } catch (_: Exception) { false } }
            ?: current)?.headers ?: emptyMap()
    }

    /** endereço do celular no Wi-Fi */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    /** pôr na fila de um grupo (modo festa) */
    fun enqueueIn(group: String, item: Item) = runOnWorker { inSession(sessionFor(group)) { startOrQueue(item, true) } }

    fun removeRecent(url: String) = runOnWorker {
        saveRecents(recents().map { it.j }.filter { it.optString("url") != url }); notifyUi()
    }

    fun playRecent(r: Recent, fromStart: Boolean = false) = runOnWorker {
        if (r.kind == "iptv") { playLink(r.url); return@runOnWorker }
        val h = r.j.optJSONObject("headers")
        val headers = h?.keys()?.asSequence()?.associateWith { k -> h.optString(k) } ?: emptyMap()
        val item = Item(r.kind, r.url, r.title, headers = headers, mime = r.j.optString("mime").takeIf { it.isNotBlank() },
            proxy = r.j.optBoolean("proxy"), thumb = r.thumb, durMs = r.dur)
        val resume = !fromStart && r.pos > 10_000 && (r.dur == 0L || r.pos < r.dur - 20_000)
        load(item, -1, if (resume) r.pos - 3000 else 0)
    }

    // ---------------------------------------------------------------- volume de todas

    /** volume médio das TVs ligadas (pros botões de volume do celular) */
    val masterVol: Int get() = tvs.filter { it.enabled }.map { it.vol }.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 100
    fun volAll(delta: Int) = tvs.forEach { if (it.enabled) it.setVol(it.vol + delta, false) }
    fun setVolAll(v: Int) = tvs.forEach { if (it.enabled) it.setVol(v, false) }

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

    fun playTrack(index: Int) = runOnWorker { playTrackNow(index) }

    private fun playTrackNow(index: Int) {
        val t = tracks.getOrNull(index) ?: return
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

    private fun load(item: Item, index: Int, startPos: Long = 0) {
        if (live && liveGroup == active.group) CaptureService.stop(app)
        savePos()
        sessionId = ++idSeq
        val sid = sessionId
        val ses = active
        current = item
        currentIndex = index
        playing = false
        basePos = startPos
        baseAt = now()
        endedHandled = false
        if (!item.proxy || recents().none { it.url == item.url }) remember(item)
        sendAll(loadMsg(item))
        sendAll(stateMsg())
        notifyUi()
        // dá um tempinho pras TVs carregarem e começa todo mundo junto
        worker.postDelayed({ inSession(ses) { if (sessionId == sid) doPlay() } }, 1200)
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
        savePos()
        baseAt = now()
        sendAll(stateMsg())
        notifyUi()
    }

    fun seek(ms: Long) = runOnWorker { seekNow(ms) }

    private fun seekNow(ms: Long) {
        if (current == null) return
        basePos = ms.coerceAtLeast(0)
        baseAt = if (playing) now() + Proto.START_LEAD_MS else now()
        endedHandled = false
        sendAll(stateMsg())
        notifyUi()
    }

    fun next() = runOnWorker {
        if (currentIndex >= 0 && currentIndex + 1 < tracks.size) playTrackNow(currentIndex + 1)
        else if (queue.isNotEmpty()) load(queue.removeAt(0), -1)
    }

    fun prev() = runOnWorker {
        if (position() > 3000 || currentIndex <= 0) seekNow(0) else playTrackNow(currentIndex - 1)
    }

    fun stopAll() = runOnWorker {
        savePos()
        current = null
        currentIndex = -1
        playing = false
        sessionId = ++idSeq
        sendAll(JSONObject().put("t", "stop"))
        notifyUi()
    }

    /** a TV não conseguiu abrir: tenta sozinho passando o vídeo pelo celular */
    private fun onTvError(tv: TvConn, j: JSONObject) = runOnWorker {
        val id = j.optInt("id", -1)
        val ses = if (id == -1) sessionFor(tv.group) else allSessions().firstOrNull { it.sessionId == id }
            ?: return@runOnWorker // erro de um vídeo antigo
        inSession(ses) { tvError(tv, j, ses) }
    }

    private fun tvError(tv: TvConn, j: JSONObject, ses: Session) {
        val cur = current
        val http = j.optInt("http")
        if (cur != null && cur.kind == "media" && cur.uri == null && !cur.proxy) {
            lastError = "A TV não abriu direto. Tentando passar o vídeo pelo celular…"
            notifyUi()
            val pos = position()
            load(cur.copy(proxy = true), currentIndex)
            if (pos > 3000) worker.postDelayed({ inSession(ses) { seekNow(pos) } }, 200)
            return
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
        val cur = allSessions().firstOrNull { it.sessionId == id }?.current ?: return
        if (dur > 0 && cur.durMs == 0L) { cur.durMs = dur; notifyUi() }
    }

    private fun onEnded(id: Int) = runOnWorker {
        val ses = allSessions().firstOrNull { it.sessionId == id } ?: return@runOnWorker
        inSession(ses) { endedNow() }
    }

    private fun endedNow() {
        if (endedHandled || !playing) return
        endedHandled = true
        if (currentIndex >= 0 && currentIndex + 1 < tracks.size) playTrackNow(currentIndex + 1)
        else if (queue.isNotEmpty()) load(queue.removeAt(0), -1)
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
        if (on && !mirroring) liveGroup = active.group
        caixinha = on
        inSession(sessionFor(liveGroup)) {
            if (on && current != null) {
                current = null; currentIndex = -1; playing = false; sessionId = ++idSeq
                sendAll(JSONObject().put("t", "stop"))
            }
        }
        sendLive(audioMsg(on))
        if (!on) audioSinks.forEach { it.close() }
        notifyUi()
    }

    // ---------------------------------------------------------------- espelhar a tela

    private val videoSinks = CopyOnWriteArrayList<VideoSink>()
    @Volatile private var lastConfig: ByteArray? = null
    /** pede um quadro completo ao codificador (quando entra uma TV nova ou perdeu pedaço) */
    @Volatile var onNeedKey: (() -> Unit)? = null

    fun setMirror(on: Boolean) = runOnWorker {
        if (on == mirroring) return@runOnWorker
        if (on && !caixinha) liveGroup = active.group
        mirroring = on
        inSession(sessionFor(liveGroup)) {
            if (on && current != null) {
                current = null; currentIndex = -1; playing = false; sessionId = ++idSeq
                sendAll(JSONObject().put("t", "stop"))
            }
        }
        sendLive(mirrorMsg(on))
        if (!on) { videoSinks.forEach { it.close() }; lastConfig = null }
        notifyUi()
    }

    /** o tamanho da imagem mudou (girou o celular) */
    fun mirrorSize(w: Int, h: Int) {
        mirrorW = w; mirrorH = h
        if (mirroring) runOnWorker { sendLive(mirrorMsg(true)) }
    }

    /** pacote: horário (8) + tipo (4: 1 = configuração, 2 = quadro completo) + tamanho (4) + dados */
    fun pushVideo(packet: ByteArray, flags: Int) {
        if (flags and 1 != 0) lastConfig = packet
        for (s in videoSinks) s.offer(packet, flags)
    }

    private fun runVideoServer() {
        try {
            val ss = ServerSocket(Proto.VIDEO_PORT).apply { reuseAddress = true }
            while (started) {
                val s = ss.accept()
                s.tcpNoDelay = true
                s.sendBufferSize = 256 * 1024
                val sink = VideoSink(s)
                lastConfig?.let { sink.offer(it, 1) }
                videoSinks += sink
                onNeedKey?.invoke()
            }
        } catch (_: Exception) {}
    }

    class VideoSink(private val socket: Socket) {
        private val q = ArrayBlockingQueue<ByteArray>(120)
        @Volatile private var needKey = true
        init {
            thread(name = "video-out", isDaemon = true) {
                try {
                    val out = socket.getOutputStream()
                    while (true) out.write(q.take())
                } catch (_: Exception) {}
                close()
            }
        }
        fun offer(p: ByteArray, flags: Int) {
            val config = flags and 1 != 0
            val key = flags and 2 != 0
            if (needKey && !config) { if (!key) return; needKey = false }
            if (!q.offer(p)) {
                // rede lenta: joga fora e espera o próximo quadro completo
                q.clear(); needKey = true
                lastConfig?.let { q.offer(it) }
                onNeedKey?.invoke()
            }
        }
        fun close() {
            try { socket.close() } catch (_: Exception) {}
            videoSinks.remove(this)
        }
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
