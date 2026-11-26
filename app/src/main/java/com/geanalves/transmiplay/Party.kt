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
                added++
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
                Hub.enqueueIn(group, Hub.Item("media", "file://" + f.absolutePath, title, by = by))
                added++
                json(out, JSONObject().put("ok", true).put("title", title))
            }
            else -> json(out, JSONObject().put("erro", "não achei"), "404 Not Found")
        }
        Hub.notifyUi()
    }

    private const val GUESS_MS = 210_000L // duração chutada quando ainda não se sabe (3:30)
    @Volatile private var sentOn = false

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
        s.queue.take(8).forEach {
            items.put(JSONObject().put("title", it.title).put("by", it.by ?: "").put("in", t))
            t += it.durMs.takeIf { d -> d > 0 } ?: GUESS_MS
        }
        var total = t
        s.queue.drop(8).forEach { total += it.durMs.takeIf { d -> d > 0 } ?: GUESS_MS }
        Hub.sendToGroup(g, JSONObject().put("t", "party").put("on", true).put("url", url() ?: "")
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
