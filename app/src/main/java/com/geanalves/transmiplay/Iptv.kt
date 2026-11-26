package com.geanalves.transmiplay

import java.net.HttpURLConnection
import java.net.URL

/** Listas de canais .m3u (IPTV): lê a lista e vira uma lista de itens pra tocar. */
object Iptv {
    data class Channel(val name: String, val url: String, val logo: String?, val group: String?)

    /** links que podem ser uma lista de canais */
    fun maybeList(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u") || path.endsWith(".m3u8") || url.contains("type=m3u", ignoreCase = true)
    }

    fun toItem(ch: Channel) = Hub.Item("media", ch.url, ch.name, thumb = ch.logo,
        mime = if (ch.url.substringBefore('?').lowercase().endsWith(".m3u8")) "m3u8" else null)

    fun fetch(url: String): List<Channel> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Transmiplay")
        val text = c.inputStream.use { ins ->
            val buf = ByteArray(4 * 1024 * 1024)
            var n = 0
            while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            String(buf, 0, n)
        }
        parse(text, url)
    } catch (_: Exception) { emptyList() }

    /** lê o texto da lista; devolve vazio se for só um vídeo HLS comum */
    fun parse(text: String, base: String = ""): List<Channel> {
        if (!text.trimStart().startsWith("#EXTM3U")) return emptyList()
        // HLS normal (pedaços de um vídeo ou qualidades), não lista de canais
        if (text.contains("#EXT-X-TARGETDURATION") || text.contains("#EXT-X-STREAM-INF")) return emptyList()
        val out = mutableListOf<Channel>()
        var name: String? = null; var logo: String? = null; var group: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    name = line.substringAfterLast(',', "").trim().ifBlank { null }
                    logo = attr(line, "tvg-logo")
                    group = attr(line, "group-title")
                }
                line.startsWith("#EXTGRP:") -> group = line.removePrefix("#EXTGRP:").trim()
                line.isEmpty() || line.startsWith("#") -> {}
                else -> {
                    val u = resolve(base, line)
                    if (u.startsWith("http")) out += Channel(name ?: u.substringAfterLast('/').take(50), u, logo, group)
                    name = null; logo = null; group = null
                }
            }
        }
        return out
    }

    private fun attr(line: String, key: String): String? =
        Regex("""$key="([^"]*)"""").find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun resolve(base: String, u: String): String =
        if (u.startsWith("http") || base.isBlank()) u else try { URL(URL(base), u).toString() } catch (_: Exception) { u }
}
