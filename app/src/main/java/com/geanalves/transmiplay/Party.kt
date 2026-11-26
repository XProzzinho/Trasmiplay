package com.geanalves.transmiplay

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Modo festa: os amigos abrem uma página no celular deles (mesmo Wi-Fi, pelo QR code)
 * e põem links ou músicas do próprio celular na fila.
 */
object Party {
    private const val MAX_UPLOAD = 150L * 1024 * 1024
    private fun prefs() = Hub.app.getSharedPreferences("transmiplay", Context.MODE_PRIVATE)

    val on: Boolean get() = prefs().getBoolean("party_on", false)
    /** grupo de TVs que recebe as músicas da festa */
    val group: String get() = prefs().getString("party_group", null)?.takeIf { it in Hub.groups } ?: Hub.active.group
    @Volatile var added = 0; private set

    /** estilos da tela de festa na TV: chave, nome, descrição */
    val STYLES = listOf(
        Triple("luzes", "Show de luzes", "Palco escuro, luzes coloridas e confete"),
        Triple("neon", "Neon roxo", "Laser roxo e ciano, bordas brilhando"),
        Triple("laser", "Laser verde", "Estilo balada eletrônica, painel de DJ"),
        Triple("claro", "Claro", "Branco e azul, igual ao app"),
    )
    val style: String get() = prefs().getString("party_style", "luzes") ?: "luzes"
    fun setStyle(s: String) {
        prefs().edit().putString("party_style", s).apply()
        Thread { try { pushToTvs() } catch (_: Exception) {} }.start()
        Hub.notifyUi()
    }

    /** amostra de cor de cada estilo (pros botões de escolher) */
    fun swatch(key: String, r: Float): android.graphics.drawable.GradientDrawable {
        val c = when (key) {
            "neon" -> intArrayOf(0xFF0B0418.toInt(), 0xFFA855F7.toInt(), 0xFF22D3EE.toInt())
            "laser" -> intArrayOf(0xFF030805.toInt(), 0xFF16A34A.toInt(), 0xFF4ADE80.toInt())
            "claro" -> intArrayOf(0xFFF7FAFE.toInt(), 0xFFDBEAFE.toInt(), 0xFF2563EB.toInt())
            else -> intArrayOf(0xFF050816.toInt(), 0xFFEC4899.toInt(), 0xFF3B82F6.toInt())
        }
        return android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, c).apply { cornerRadius = r }
    }

    fun setOn(v: Boolean) {
        prefs().edit().putBoolean("party_on", v).putString("party_group", Hub.active.group).apply()
        if (!v) dir().listFiles()?.forEach { f -> if (Hub.allSessions().none { it.current?.url?.endsWith(f.name) == true }) f.delete() }
        Hub.notifyUi()
    }

    fun url(): String? = Hub.localIp()?.let { "http://$it:${Proto.HTTP_PORT}/festa" }

    private fun dir() = File(Hub.app.cacheDir, "festa").apply { mkdirs() }

    fun fileUri(name: String): Uri? {
        val n = java.net.URLDecoder.decode(name, "UTF-8")
        if (n.contains('/') || n.startsWith(".")) return null
        val f = File(dir(), n)
        return if (f.exists()) Uri.fromFile(f) else null
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    private fun param(q: String, name: String): String? =
        q.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    private fun send(out: OutputStream, status: String, mime: String, body: ByteArray) {
        out.write(("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }

    private fun json(out: OutputStream, j: JSONObject, status: String = "200 OK") =
        send(out, status, "application/json; charset=utf-8", j.toString().toByteArray())

    fun handle(out: OutputStream, method: String, path: String, query: String, inp: InputStream, bodyLen: Long) {
        if (path == "/festa" || path == "/festa/") {
            val html = Hub.app.assets.open("festa.html").use { it.readBytes() }
            send(out, "200 OK", "text/html; charset=utf-8", html)
            return
        }
        if (!on) { json(out, JSONObject().put("on", false).put("erro", "O modo festa está desligado no celular."), "403 Forbidden"); return }
        when (path) {
            "/festa/estado" -> {
                val s = Hub.sessionFor(group)
                val q = JSONArray()
                s.queue.forEach { q.put(JSONObject().put("title", it.title).put("by", it.by ?: "")) }
                json(out, JSONObject().put("on", true).put("group", group).put("host", Hub.deviceName())
                    .put("now", s.current?.let { JSONObject().put("title", it.title).put("by", it.by ?: "").put("playing", s.playing) })
                    .put("queue", q))
            }
            "/festa/link" -> {
                if (method != "POST") { json(out, JSONObject().put("erro", "use POST"), "405 Method Not Allowed"); return }
                val body = readBody(inp, bodyLen.coerceAtMost(8192))
                val url = param(body, "u")?.trim() ?: ""
                val by = param(body, "n")?.trim()?.take(30)?.ifBlank { null }
                if (!url.startsWith("http")) { json(out, JSONObject().put("erro", "Cole um link que comece com http"), "400 Bad Request"); return }
                val yt = Hub.youtubeId(url)
                val item = if (yt != null) Hub.Item("youtube", yt, Hub.youtubeTitle(yt) ?: "YouTube", by = by)
                else Hub.Item("media", url, Uri.parse(url).lastPathSegment?.take(60) ?: url, by = by,
                    mime = if (url.substringBefore('?').endsWith(".m3u8")) "m3u8" else null)
                Hub.enqueueIn(group, item)
                justAdded(item)
                json(out, JSONObject().put("ok", true).put("title", item.title))
            }
            "/festa/enviar" -> {
                if (method != "POST") { json(out, JSONObject().put("erro", "use POST"), "405 Method Not Allowed"); return }
                if (bodyLen <= 0 || bodyLen > MAX_UPLOAD) { json(out, JSONObject().put("erro", "Arquivo grande demais (máximo 150 MB)"), "413 Payload Too Large"); return }
                val orig = param(query, "nome") ?: "musica.mp3"
                val ext = orig.substringAfterLast('.', "mp3").lowercase().take(5)
                if (ext !in listOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "mp4", "m4v", "webm", "mkv", "mov")) {
                    json(out, JSONObject().put("erro", "Só músicas e vídeos (mp3, m4a, mp4...)"), "415 Unsupported Media Type"); return
                }
                val by = param(query, "por")?.trim()?.take(30)?.ifBlank { null }
                val f = File(dir(), "f${System.currentTimeMillis()}.$ext")
                f.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var left = bodyLen
                    while (left > 0) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) break
                        o.write(buf, 0, n); left -= n
                    }
                    if (left > 0) { f.delete(); json(out, JSONObject().put("erro", "O envio parou no meio"), "400 Bad Request"); return }
                }
                val title = orig.substringBeforeLast('.').replace('_', ' ').take(60)
                val item = Hub.Item("media", "file://" + f.absolutePath, title, by = by)
                Hub.enqueueIn(group, item)
                justAdded(item)
                json(out, JSONObject().put("ok", true).put("title", title))
            }
            else -> json(out, JSONObject().put("erro", "não achei"), "404 Not Found")
        }
        Hub.notifyUi()
    }

    private const val GUESS_MS = 210_000L // duração chutada quando ainda não se sabe (3:30)
    @Volatile private var sentOn = false
    /** último pedido (as TVs mostram a lista na hora, com ele destacado) */
    @Volatile private var lastItem: Hub.Item? = null
    @Volatile private var seq = 0

    private fun justAdded(item: Hub.Item) {
        added++; seq++; lastItem = item
        // mostra logo nas TVs (sem esperar os 2 s)
        Thread { Thread.sleep(300); try { pushToTvs() } catch (_: Exception) {} }.start()
    }

    /** manda pras TVs da festa a fila com quem pediu e quando cada uma toca */
    fun pushToTvs() {
        if (!on) {
            if (sentOn) { sentOn = false; Hub.sendToAll(JSONObject().put("t", "party").put("on", false)) }
            return
        }
        sentOn = true
        val g = group
        val s = Hub.sessionFor(g)
        val cur = s.current
        var t = if (cur != null) {
            val d = cur.durMs.takeIf { it > 0 } ?: GUESS_MS
            (d - s.position()).coerceAtLeast(0)
        } else 0L
        val items = JSONArray()
        val last = lastItem
        var newIdx = -1
        var newIn = 0L
        s.queue.forEachIndexed { i, it ->
            if (it === last) { newIdx = i; newIn = t }
            if (i < 8) items.put(JSONObject().put("title", it.title).put("by", it.by ?: "").put("in", t))
            t += it.durMs.takeIf { d -> d > 0 } ?: GUESS_MS
        }
        val playingNew = last != null && cur === last
        val total = t
        val news = last?.let {
            JSONObject().put("seq", seq).put("title", it.title).put("by", it.by ?: "")
                .put("idx", newIdx).put("in", newIn).put("now", playingNew)
        }
        Hub.sendToGroup(g, JSONObject().put("t", "party").put("on", true).put("url", url() ?: "").put("new", news).put("style", style)
            .put("now", cur?.let { JSONObject().put("title", it.title).put("by", it.by ?: "") })
            .put("items", items).put("count", s.queue.size).put("left", total))
    }

    private fun readBody(inp: InputStream, len: Long): String {
        val b = ByteArray(len.toInt())
        var got = 0
        while (got < b.size) { val n = inp.read(b, got, b.size - got); if (n <= 0) break; got += n }
        return String(b, 0, got)
    }
}
