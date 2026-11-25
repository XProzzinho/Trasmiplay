package com.geanalves.transmiplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Histórico, mais visitados e favoritos do navegador. */
object WebStore {
    data class Site(val url: String, var title: String, var count: Int, var last: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("web", Context.MODE_PRIVATE)

    private fun read(ctx: Context, key: String): MutableList<Site> {
        val out = mutableListOf<Site>()
        try {
            val arr = JSONArray(prefs(ctx).getString(key, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Site(o.getString("u"), o.optString("t"), o.optInt("c", 1), o.optLong("l"))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun write(ctx: Context, key: String, list: List<Site>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("c", it.count).put("l", it.last)) }
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }

    fun host(url: String): String = try { java.net.URL(url).host.removePrefix("www.") } catch (_: Exception) { url }

    /** guarda a visita; "mais visitados" agrupa por site (domínio) */
    fun visit(ctx: Context, url: String, title: String) {
        if (!url.startsWith("http")) return
        val now = System.currentTimeMillis()
        val hist = read(ctx, "history")
        hist.removeAll { it.url == url }
        hist.add(0, Site(url, title, 1, now))
        write(ctx, "history", hist.take(300))

        val h = host(url)
        val top = read(ctx, "top")
        val s = top.firstOrNull { host(it.url) == h }
        if (s != null) { s.count++; s.last = now } else {
            top += Site("https://" + (try { java.net.URL(url).host } catch (_: Exception) { h }), h, 1, now)
        }
        write(ctx, "top", top.sortedByDescending { it.count }.take(50))
    }

    fun history(ctx: Context) = read(ctx, "history")
    fun top(ctx: Context) = read(ctx, "top")
    fun favorites(ctx: Context) = read(ctx, "fav")

    fun isFavorite(ctx: Context, url: String) = read(ctx, "fav").any { it.url == url }

    fun toggleFavorite(ctx: Context, url: String, title: String): Boolean {
        val fav = read(ctx, "fav")
        val had = fav.removeAll { it.url == url }
        if (!had) fav.add(0, Site(url, title.ifBlank { host(url) }, 1, System.currentTimeMillis()))
        write(ctx, "fav", fav)
        return !had
    }

    fun remove(ctx: Context, key: String, url: String) {
        val l = read(ctx, key); l.removeAll { it.url == url }; write(ctx, key, l)
    }

    fun clearHistory(ctx: Context) {
        write(ctx, "history", emptyList())
    }

    fun saveOpenTabs(ctx: Context, urls: List<String>) =
        prefs(ctx).edit().putString("tabs", JSONArray(urls).toString()).apply()

    fun openTabs(ctx: Context): List<String> = try {
        val a = JSONArray(prefs(ctx).getString("tabs", "[]"))
        (0 until a.length()).map { a.getString(it) }.filter { it.startsWith("http") }
    } catch (_: Exception) { emptyList() }

    fun getFlag(ctx: Context, k: String, def: Boolean) = prefs(ctx).getBoolean(k, def)
    fun setFlag(ctx: Context, k: String, v: Boolean) = prefs(ctx).edit().putBoolean(k, v).apply()
}
