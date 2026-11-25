package com.geanalves.transmiplay

import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Servidorzinho que entrega as músicas do celular para as TVs (com suporte a pular/seek). */
class HttpServer(private val port: Int) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    fun start() {
        running = true
        thread(name = "http", isDaemon = true) {
            try {
                val ss = ServerSocket(port).apply { reuseAddress = true }
                server = ss
                while (running) {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun handle(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 30000
                val inp = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                val request = readLine(inp) ?: return
                var range: String? = null
                while (true) {
                    val h = readLine(inp) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("range:", ignoreCase = true)) range = h.substringAfter(':').trim()
                }
                val parts = request.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val idx = path.removePrefix("/m/").toIntOrNull()
                val item = idx?.let { Hub.tracks.getOrNull(it) }
                val uri = item?.uri
                if (uri == null) {
                    writeHead(out, "404 Not Found", "text/plain", 0, null)
                    return
                }
                val cr = Hub.app.contentResolver
                val size = fileSize(uri)
                val mime = cr.getType(uri) ?: "audio/mpeg"

                var start = 0L
                var end = if (size > 0) size - 1 else -1L
                var partial = false
                if (range != null && size > 0 && range.startsWith("bytes=")) {
                    val r = range.removePrefix("bytes=").substringBefore(',')
                    val a = r.substringBefore('-').trim()
                    val b = r.substringAfter('-').trim()
                    if (a.isEmpty() && b.isNotEmpty()) {
                        start = (size - b.toLong()).coerceAtLeast(0)
                    } else {
                        start = a.toLongOrNull() ?: 0
                        if (b.isNotEmpty()) end = (b.toLongOrNull() ?: end).coerceAtMost(size - 1)
                    }
                    if (start >= size) {
                        writeHead(out, "416 Range Not Satisfiable", mime, 0, "bytes */$size")
                        return
                    }
                    partial = true
                }
                val length = if (size > 0) end - start + 1 else -1L
                writeHead(
                    out,
                    if (partial) "206 Partial Content" else "200 OK",
                    mime,
                    length,
                    if (partial) "bytes $start-$end/$size" else null,
                )
                if (method == "HEAD") return
                cr.openInputStream(uri)?.use { src ->
                    var toSkip = start
                    while (toSkip > 0) {
                        val k = src.skip(toSkip)
                        if (k <= 0) { if (src.read() == -1) return; toSkip-- } else toSkip -= k
                    }
                    copy(src, out, length)
                }
            } catch (_: Exception) {}
        }
    }

    private fun fileSize(uri: android.net.Uri): Long {
        val cr = Hub.app.contentResolver
        try {
            cr.openAssetFileDescriptor(uri, "r")?.use { if (it.length > 0) return it.length }
        } catch (_: Exception) {}
        try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
            }
        } catch (_: Exception) {}
        return -1
    }

    private fun writeHead(out: OutputStream, status: String, mime: String, length: Long, contentRange: String?) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        if (contentRange != null) sb.append("Content-Range: ").append(contentRange).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun copy(src: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = if (length < 0) Long.MAX_VALUE else length
        while (left > 0) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
        out.flush()
    }
}
