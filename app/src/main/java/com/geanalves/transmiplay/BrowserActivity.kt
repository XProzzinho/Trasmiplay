package com.geanalves.transmiplay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Navegador do Transmiplay: abre qualquer site, percebe os vídeos que a página toca
 * e manda pras TVs com o Simulplay.
 */
/** endereços que costumam ser de vídeo de propaganda */
fun looksLikeAdUrl(url: String): Boolean {
    val u = url.lowercase()
    val host = try { java.net.URL(u).host } catch (_: Exception) { "" }
    if (AdBlock.isAdHost(host)) return true
    val path = u.substringAfter(host)
    return listOf(
        "/ads/", "/ad/", "/adv/", "/advert", "preroll", "pre-roll", "midroll", "/vast", "vast.xml", "vast=",
        "/vpaid", "ima3", "adserver", "adtag", "/creative", "/promo/", "commercial", "sponsor",
    ).any { path.contains(it) }
}

@SuppressLint("SetJavaScriptEnabled")
class BrowserActivity : Activity() {

    // ------------------------------------------------------------------ cores
    private val bg = Color.parseColor("#0B0826")
    private val bar = Neon.bgTop
    private val field = Color.parseColor("#241D5E")
    private val line = Neon.stroke
    private val soft = Neon.soft
    private val blue = Neon.purple2
    private val green = Neon.green
    private val yellow = Color.parseColor("#F4C20D")

    // ------------------------------------------------------------------ dados
    data class Video(
        val url: String,
        val type: String,          // mp4, m3u8, mpd, webm, youtube...
        val pageUrl: String,
        @Volatile var pageTitle: String,
        @Volatile var thumb: String?,
        @Volatile var w: Int = 0,
        @Volatile var h: Int = 0,
        val ytId: String? = null,
        @Volatile var durSec: Double = 0.0,   // duração vista no <video> da página
        val referer: String = pageUrl,        // endereço do player (quando o vídeo está num quadro)
    ) {
        val fileName: String
            get() = ytId ?: (Uri.parse(url).lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "vídeo")

        /** parece propaganda? (endereço de anúncio ou vídeo bem curtinho) */
        val adLike: Boolean
            get() = ytId == null && (looksLikeAdUrl(url) || durSec in 0.5..90.0)

        /** nota pra escolher o vídeo principal: maior = melhor */
        val score: Double
            get() = when {
                ytId != null -> 1e9
                adLike -> -1.0
                else -> durSec + (if (type == "m3u8" || type == "mpd") 30.0 else 0.0) + (if (w >= 1280) 10.0 else 0.0)
            }
    }

    data class SubFound(val url: String, val mime: String)

    inner class Tab {
        val web: WebView = makeWebView(this)
        val videos = LinkedHashMap<String, Video>()
        val subs = LinkedHashMap<String, SubFound>()
        @Volatile var pageUrl = ""
        @Volatile var title = ""
        @Volatile var ogImage: String? = null
        @Volatile var blocked = 0
        val isHome get() = pageUrl.isEmpty()
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val thumbs = ConcurrentHashMap<String, Bitmap>()
    private val tabs = mutableListOf<Tab>()
    private lateinit var tab: Tab
    private var userAgent = ""

    // ------------------------------------------------------------------ views
    private lateinit var root: FrameLayout
    private lateinit var address: EditText
    private lateinit var addrIcon: ImageView
    private lateinit var foundBtn: FrameLayout
    private lateinit var shield: FrameLayout
    private lateinit var shieldIcon: ImageView
    private lateinit var shieldCount: TextView
    private lateinit var foundCount: TextView
    private lateinit var tabsCount: TextView
    private lateinit var castIcon: ImageView
    private lateinit var progress: View
    private lateinit var webHolder: FrameLayout
    private lateinit var home: ScrollView
    private lateinit var homeList: LinearLayout
    private lateinit var homeTabTop: TextView
    private lateinit var homeTabFav: TextView
    private lateinit var restoreBar: LinearLayout
    private lateinit var mini: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: ImageView
    private lateinit var drawer: FrameLayout
    private lateinit var drawerPanel: LinearLayout
    private var videoList: View? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var homeShowsFav = false

    private val blockPopups get() = WebStore.getFlag(this, "block_popups", true)
    private val blockAds get() = WebStore.getFlag(this, "block_ads", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Neon.bgTop
        window.navigationBarColor = bg
        TransmitService.start(this)
        Hub.start(applicationContext)
        setContentView(buildUi())
        newTab(null)
        val saved = WebStore.openTabs(this)
        when {
            saved.isEmpty() -> {}
            WebStore.getFlag(this, "always_restore", false) -> restoreTabs(saved)
            else -> restoreBar.visibility = View.VISIBLE
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val u = i?.dataString ?: return
        if (u.startsWith("http")) open(u)
    }

    override fun onResume() {
        super.onResume()
        Hub.listener = { refreshMini() }
        refreshMini()
        updateShield()
        tabs.forEach { it.web.onResume() }
    }

    override fun onPause() {
        super.onPause()
        Hub.listener = null
        WebStore.saveOpenTabs(this, tabs.map { it.pageUrl })
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreenView != null -> fullscreenCallback?.onCustomViewHidden()
            drawer.visibility == View.VISIBLE -> closeDrawer()
            videoList != null -> closeVideoList()
            !tab.isHome && tab.web.canGoBack() -> tab.web.goBack()
            !tab.isHome -> goHome()
            tabs.size > 1 -> closeTab(tab)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ================================================================== montagem da tela

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun icon(res: Int, tint: Int = Color.WHITE, pad: Int = 10, onClick: (() -> Unit)? = null) =
        ImageView(this).apply {
            setImageResource(res)
            setColorFilter(tint)
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
            if (onClick != null) {
                setOnClickListener { onClick() }
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
        }

    private fun text(s: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun buildUi(): View {
        root = FrameLayout(this).apply { background = Neon.background() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- barra de cima
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bar)
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        top.addView(icon(R.drawable.ic_menu) { openDrawer() }, LinearLayout.LayoutParams(dp(42), dp(46)))

        // escudo: ícone do app colorido = bloqueando anúncios, cinza = desligado
        shield = FrameLayout(this).apply {
            setOnClickListener { toggleShield() }
            setOnLongClickListener { shieldInfo(); true }
        }
        shieldIcon = ImageView(this).apply { setImageResource(R.drawable.logo) }
        shield.addView(shieldIcon, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        shieldCount = text("", 9f, Color.WHITE, true).apply {
            background = circle(Color.parseColor("#D64545"))
            gravity = Gravity.CENTER
            setPadding(dp(3), 0, dp(3), 0)
            visibility = View.GONE
        }
        shield.addView(shieldCount, FrameLayout.LayoutParams(WRAP_CONTENT, dp(15), Gravity.TOP or Gravity.END))
        top.addView(shield, LinearLayout.LayoutParams(dp(40), dp(42)).apply { marginEnd = dp(4) })

        val fieldBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(field, 4)
        }
        address = EditText(this).apply {
            hint = "Navegar"
            setHintTextColor(soft)
            setTextColor(Color.WHITE)
            textSize = 14f
            background = null
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setPadding(dp(10), dp(8), dp(4), dp(8))
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    go(text.toString()); true
                } else false
            }
            setOnFocusChangeListener { _, f -> updateAddrIcon(f) }
        }
        fieldBox.addView(address, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addrIcon = icon(R.drawable.ic_search, soft, 8) { onAddrIcon() }
        fieldBox.addView(addrIcon, LinearLayout.LayoutParams(dp(38), dp(38)))
        top.addView(fieldBox, LinearLayout.LayoutParams(0, dp(40), 1f))

        // botão amarelo "achei vídeo"
        foundBtn = FrameLayout(this).apply {
            visibility = View.GONE
            setOnClickListener { openVideoList() }
        }
        val dot = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(Color.parseColor("#3A2C00"))
            background = circle(yellow)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        foundBtn.addView(dot, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        foundCount = text("", 10f, Color.WHITE, true).apply {
            background = circle(Color.parseColor("#D64545"))
            gravity = Gravity.CENTER
        }
        foundBtn.addView(foundCount, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END))
        top.addView(foundBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(4) })

        // abas
        val tabsBox = FrameLayout(this).apply {
            setOnClickListener { showTabs() }
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        tabsBox.addView(icon(R.drawable.ic_tabs, Color.WHITE, 11), FrameLayout.LayoutParams(dp(44), dp(46)))
        tabsCount = text("1", 9f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        tabsBox.addView(tabsCount, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER).apply {
            leftMargin = dp(2); topMargin = -dp(1)
        })
        top.addView(tabsBox, LinearLayout.LayoutParams(dp(44), dp(46)))

        castIcon = icon(R.drawable.ic_cast) { showDevices() }
        top.addView(castIcon, LinearLayout.LayoutParams(dp(44), dp(46)))
        val more = icon(R.drawable.ic_more)
        more.setOnClickListener { showMore(more) }
        more.setBackgroundResource(android.R.drawable.list_selector_background)
        top.addView(more, LinearLayout.LayoutParams(dp(36), dp(46)))
        col.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        progress = View(this).apply { setBackgroundColor(blue); visibility = View.GONE }
        col.addView(progress, LinearLayout.LayoutParams(0, dp(2)))

        // ---- "deseja abrir novamente"
        restoreBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Neon.cardHi)
            setPadding(dp(12), dp(12), dp(12), dp(4))
            visibility = View.GONE
        }
        restoreBar.addView(text("Deseja abrir novamente os sites que você estava visitando?", 14f).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val rRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rBtn(t: String, f: () -> Unit) = text(t, 13f).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(10))
            setOnClickListener { restoreBar.visibility = View.GONE; f() }
        }
        rRow.addView(rBtn("Sempre restaurar") {
            WebStore.setFlag(this, "always_restore", true); restoreTabs(WebStore.openTabs(this))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        rRow.addView(rBtn("Não") { WebStore.saveOpenTabs(this, emptyList()) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        rRow.addView(rBtn("Restaurar") { restoreTabs(WebStore.openTabs(this)) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        restoreBar.addView(rRow)
        col.addView(restoreBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- área do site
        webHolder = FrameLayout(this)
        col.addView(webHolder, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        home = buildHome()
        webHolder.addView(home, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- mini player
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#2A1F6E"))
            setPadding(dp(10), dp(4), dp(4), dp(4))
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(this@BrowserActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONTROL, true)) }
        }
        mini.addView(icon(R.drawable.ic_cast, blue, 8), LinearLayout.LayoutParams(dp(36), dp(36)))
        val miniCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, 0, 0) }
        miniCol.addView(text("Transmitindo nas TVs", 11f, soft))
        miniTitle = text("", 14f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        miniCol.addView(miniTitle)
        mini.addView(miniCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        miniPlay = icon(R.drawable.ic_pause) { Hub.togglePlay() }
        mini.addView(miniPlay, LinearLayout.LayoutParams(dp(46), dp(46)))
        mini.addView(icon(R.drawable.ic_stop) { Hub.stopAll() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        col.addView(mini, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- menu lateral
        drawer = buildDrawer()
        root.addView(drawer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return root
    }

    // ================================================================== página inicial

    private fun buildHome(): ScrollView {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(24), dp(14), dp(24))
        }
        c.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        c.addView(Neon.brand(this, "Bem-vindo ao Transmi", "play", 20f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        c.addView(text("Abra um site, dê play no vídeo e toque no botão amarelo ▶ lá em cima pra mandar pras TVs.", 14f, soft).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(18))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Neon.cardBg(this@BrowserActivity, Neon.card, 14)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        val tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        homeTabTop = text("Mais visitados", 13f, Color.WHITE, true).apply {
            setPadding(dp(18), dp(14), dp(18), dp(12)); setOnClickListener { homeShowsFav = false; fillHome() }
        }
        homeTabFav = text("Favoritos", 13f, soft).apply {
            setPadding(dp(18), dp(14), dp(18), dp(12)); setOnClickListener { homeShowsFav = true; fillHome() }
        }
        tabsRow.addView(homeTabTop); tabsRow.addView(homeTabFav)
        cardBox.addView(tabsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        homeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardBox.addView(homeList)
        cardBox.addView(text("Ver mais", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(10))
            setOnClickListener { showHistory() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        c.addView(cardBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return ScrollView(this).apply { addView(c); isFillViewport = true }
    }

    private fun fillHome() {
        homeTabTop.setTextColor(if (homeShowsFav) soft else Color.WHITE)
        homeTabFav.setTextColor(if (homeShowsFav) Color.WHITE else soft)
        homeTabTop.paint.isUnderlineText = !homeShowsFav
        homeTabFav.paint.isUnderlineText = homeShowsFav
        homeTabTop.invalidate(); homeTabFav.invalidate()
        homeList.removeAllViews()
        val list = if (homeShowsFav) WebStore.favorites(this) else WebStore.top(this)
        if (list.isEmpty()) {
            homeList.addView(text(
                if (homeShowsFav) "Nenhum favorito ainda. No ⋮ de um site, toque em Favoritar."
                else "Os sites que você abrir aparecem aqui.", 13f, soft,
            ).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(24), dp(16), dp(8)) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        list.take(8).forEach { s -> homeList.addView(siteRow(s, if (homeShowsFav) "fav" else "top") { fillHome() }) }
    }

    private fun letterBadge(name: String): TextView {
        val colors = listOf("#C62828", "#AD1457", "#6A1B9A", "#283593", "#00838F", "#2E7D32", "#EF6C00", "#4E342E")
        val c = Color.parseColor(colors[Math.floorMod(name.hashCode(), colors.size)])
        return text(name.firstOrNull()?.uppercase() ?: "?", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = circle(c)
        }
    }

    private fun siteRow(s: WebStore.Site, key: String, onChange: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), 0, dp(8))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { open(s.url) }
        }
        val name = WebStore.host(s.url)
        row.addView(letterBadge(name), LinearLayout.LayoutParams(dp(30), dp(30)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(6), 0) }
        col.addView(text(if (key == "top") name else s.title.ifBlank { name }, 14f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(text(s.url, 11f, soft).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val dots = icon(R.drawable.ic_more, soft, 10)
        dots.setOnClickListener {
            val pm = PopupMenu(this, dots)
            pm.menu.add(0, 1, 0, "Abrir")
            pm.menu.add(0, 2, 0, "Abrir em nova aba")
            pm.menu.add(0, 3, 0, if (WebStore.isFavorite(this, s.url)) "Tirar dos favoritos" else "Favoritar")
            pm.menu.add(0, 4, 0, "Remover")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> open(s.url)
                    2 -> newTab(s.url)
                    3 -> { WebStore.toggleFavorite(this, s.url, s.title); onChange() }
                    4 -> { WebStore.remove(this, key, s.url); onChange() }
                }
                true
            }
            pm.show()
        }
        row.addView(dots, LinearLayout.LayoutParams(dp(40), dp(40)))
        return row
    }

    // ================================================================== abas

    private fun newTab(url: String?) {
        val t = Tab()
        tabs += t
        switchTo(t)
        if (url != null) open(url)
    }

    private fun switchTo(t: Tab) {
        if (::tab.isInitialized) webHolder.removeView(tab.web)
        tab = t
        if (t.web.parent == null) webHolder.addView(t.web, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        t.web.visibility = if (t.isHome) View.GONE else View.VISIBLE
        home.visibility = if (t.isHome) View.VISIBLE else View.GONE
        if (t.isHome) fillHome()
        address.setText(if (t.isHome) "" else t.pageUrl)
        tabsCount.text = tabs.size.toString()
        updateFound()
        updateAddrIcon(false)
        updateShield()
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        tabs.remove(t)
        webHolder.removeView(t.web)
        t.web.destroy()
        if (tabs.isEmpty()) tabs += Tab()
        if (t == tab) switchTo(tabs[(i - 1).coerceAtLeast(0).coerceAtMost(tabs.size - 1)])
        tabsCount.text = tabs.size.toString()
    }

    private fun restoreTabs(urls: List<String>) {
        if (urls.isEmpty()) return
        if (tab.isHome && tabs.size == 1) { open(urls[0]); urls.drop(1).forEach { u -> val t = Tab(); tabs += t; t.pageUrl = u; t.title = WebStore.host(u); t.web.loadUrl(u) } }
        else urls.forEach { u -> val t = Tab(); tabs += t; t.pageUrl = u; t.title = WebStore.host(u); t.web.loadUrl(u) }
        tabsCount.text = tabs.size.toString()
    }

    private fun showTabs() {
        val names = tabs.map { (if (it == tab) "● " else "") + (if (it.isHome) "Página inicial" else it.title.ifBlank { it.pageUrl }) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Abas (${tabs.size})")
            .setItems(names.toTypedArray()) { _, i -> switchTo(tabs[i]) }
            .setPositiveButton("Nova aba") { _, _ -> newTab(null) }
            .setNeutralButton("Fechar esta aba") { _, _ -> closeTab(tab) }
            .setNegativeButton("Voltar", null)
            .show()
    }

    // ================================================================== navegar

    private fun go(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> "https://www.google.com/search?q=" + Uri.encode(s)
        }
        open(url)
    }

    private fun open(url: String) {
        hideKeyboard()
        restoreBar.visibility = View.GONE
        tab.pageUrl = url
        home.visibility = View.GONE
        tab.web.visibility = View.VISIBLE
        address.setText(url)
        tab.web.loadUrl(url)
    }

    private fun goHome() {
        tab.web.stopLoading()
        tab.web.loadUrl("about:blank")
        tab.pageUrl = ""
        tab.title = ""
        tab.videos.clear()
        tab.subs.clear()
        switchTo(tab)
    }

    private fun hideKeyboard() {
        address.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun updateAddrIcon(focused: Boolean) {
        val res = when {
            focused -> R.drawable.ic_close
            tab.isHome -> R.drawable.ic_search
            else -> R.drawable.ic_refresh
        }
        addrIcon.setImageResource(res)
    }

    private fun onAddrIcon() {
        when {
            address.hasFocus() -> { address.setText(""); }
            tab.isHome -> go(address.text.toString())
            else -> tab.web.reload()
        }
    }

    private fun makeWebView(t: Tab): WebView {
        val w = WebView(this)
        w.setBackgroundColor(Color.WHITE)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        if (userAgent.isEmpty()) userAgent = w.settings.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(PageBridge(t), "TP")
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // detector de vídeo roda antes do site, em todos os quadros (players embutidos)
            try { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, sniffJs(), setOf("*")) } catch (_: Exception) {}
        }
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // roda o bloqueador antes da página, inclusive dentro dos quadros (o próprio script vê se está ligado)
            try { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, AdBlock.script(this@BrowserActivity), setOf("*")) } catch (_: Exception) {}
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (blockAds && u.startsWith("http") && AdBlock.isAdHost(request.url.host ?: "")) {
                    countBlocked(t) // redirecionamento pra propaganda: fica na página
                    return true
                }
                if (u.startsWith("http")) return false
                // links "intent://", "market://"... normalmente são propaganda: não abre
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url == "about:blank") return
                if (t.pageUrl != url && sameDocChange(t.pageUrl, url).not()) {
                    t.videos.clear(); t.subs.clear(); t.ogImage = null; t.blocked = 0
                    if (t == tab) updateShield()
                }
                if (blockAds) view.evaluateJavascript(AdBlock.script(this@BrowserActivity), null)
                t.pageUrl = url
                if (t == tab) {
                    if (!address.hasFocus()) address.setText(url)
                    progress.visibility = View.VISIBLE
                    updateFound()
                    updateAddrIcon(address.hasFocus())
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == "about:blank") return
                t.pageUrl = url
                t.title = view.title ?: ""
                WebStore.visit(this@BrowserActivity, url, t.title)
                if (blockAds) view.evaluateJavascript(AdBlock.script(this@BrowserActivity), null)
                view.evaluateJavascript(sniffJs(), null)
                view.evaluateJavascript(SCAN_JS, null)
                if (t == tab) {
                    progress.visibility = View.GONE
                    if (!address.hasFocus()) address.setText(url)
                }
                youtubeCheck(t, url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // sites como o YouTube trocam de página sem recarregar
                if (url == "about:blank") return
                if (url != t.pageUrl) {
                    t.pageUrl = url
                    if (t == tab && !address.hasFocus()) address.setText(url)
                }
                youtubeCheck(t, url)
                view.evaluateJavascript(SCAN_JS, null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                val host = request.url.host ?: ""
                if (blockAds && !request.isForMainFrame && AdBlock.shouldBlock(u, t.pageUrl)) {
                    countBlocked(t)
                    // resposta falsa (e não erro): o site acha que o anúncio carregou
                    return AdBlock.fakeResponse(u, request.requestHeaders?.get("Accept"))
                }
                sniff(t, u, request.requestHeaders)
                return null
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (t != tab) return
                progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                progress.layoutParams = progress.layoutParams.apply { width = root.width * p / 100 }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                t.title = title ?: ""
                t.videos.values.forEach { if (it.pageUrl == t.pageUrl && it.ytId == null) it.pageTitle = t.title }
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (blockPopups) return false
                // abre o pop-up numa aba nova
                val nt = Tab()
                tabs += nt
                switchTo(nt)
                home.visibility = View.GONE
                nt.web.visibility = View.VISIBLE
                (resultMsg.obj as WebView.WebViewTransport).webView = nt.web
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenView = view
                fullscreenCallback = callback
                root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }

            override fun onHideCustomView() {
                fullscreenView?.let { root.removeView(it) }
                fullscreenView = null
                fullscreenCallback = null
            }
        }
        w.visibility = View.GONE
        return w
    }

    private fun sameDocChange(a: String, b: String) = a.substringBefore('#') == b.substringBefore('#')

    // ================================================================== achar vídeos

    /** olha cada coisa que a página baixa e separa o que é vídeo */
    private fun sniff(t: Tab, url: String, reqHeaders: Map<String, String>?) {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        if (lower.contains("googlevideo.com/videoplayback")) return // YouTube é tratado à parte
        val type = when {
            path.endsWith(".m3u8") || lower.contains(".m3u8") || lower.contains("mime=application%2fx-mpegurl") ||
                lower.contains("format=m3u8") || lower.contains("type=hls") -> "m3u8"
            path.endsWith(".mpd") || lower.contains(".mpd?") || lower.contains("format=mpd") -> "mpd"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "mp4"
            path.endsWith(".webm") -> "webm"
            path.endsWith(".mkv") -> "mkv"
            path.endsWith(".mov") -> "mov"
            path.endsWith(".flv") -> "flv"
            path.endsWith(".mp3") -> "mp3"
            path.endsWith(".m4a") || path.endsWith(".aac") -> "m4a"
            path.endsWith(".vtt") -> { addSub(t, url, "text/vtt"); return }
            path.endsWith(".srt") -> { addSub(t, url, "application/x-subrip"); return }
            else -> return
        }
        addVideo(t, url, type)
    }

    private fun addSub(t: Tab, url: String, mime: String) = ui.post {
        if (!t.subs.containsKey(url)) t.subs[url] = SubFound(url, mime)
    }

    private fun addVideo(t: Tab, url: String, type: String, w: Int = 0, h: Int = 0, dur: Double = 0.0, referer: String? = null) = ui.post {
        val existing = t.videos[url]
        if (existing != null) {
            if (w > 0) { existing.w = w; existing.h = h }
            if (dur > 0 && dur.isFinite()) existing.durSec = dur
            return@post
        }
        if (t.videos.size >= 40) return@post
        // m3u8 de pedaços (ts) de uma lista que já temos não interessa
        t.videos[url] = Video(url, type, t.pageUrl, t.title.ifBlank { WebStore.host(t.pageUrl) }, t.ogImage, w, h,
            durSec = if (dur.isFinite() && dur > 0) dur else 0.0,
            referer = referer?.takeIf { it.startsWith("http") } ?: t.pageUrl)
        if (t == tab) { updateFound(pulse = true) }
        videoList?.let { if (t == tab) renderVideoList() }
    }

    private fun youtubeCheck(t: Tab, url: String) {
        val id = Hub.youtubeId(url) ?: return
        val key = "yt:$id"
        ui.post {
            if (t.videos.containsKey(key)) return@post
            t.videos.entries.removeAll { it.value.ytId != null }
            t.videos[key] = Video(
                key, "youtube", url, t.title.removeSuffix(" - YouTube").ifBlank { "YouTube" },
                "https://i.ytimg.com/vi/$id/hqdefault.jpg", 0, 0, id,
            )
            if (t == tab) updateFound(pulse = true)
        }
    }

    inner class PageBridge(private val t: Tab) {
        @JavascriptInterface
        fun adsOn(): Boolean = blockAds

        /** vídeo achado pelo conteúdo (lista m3u8/mpd) ou pelo player, inclusive dentro de quadros */
        @JavascriptInterface
        fun foundType(src: String, type: String, frameUrl: String, frameTitle: String) {
            if (!src.startsWith("http") || src.contains("googlevideo.com")) return
            val ty = if (type in setOf("m3u8", "mpd", "mp4", "webm", "mp3")) type else "mp4"
            if (t.title.isBlank() && frameTitle.isNotBlank()) t.title = frameTitle
            addVideo(t, src, ty, referer = frameUrl)
        }

        @JavascriptInterface
        fun blocked() = countBlocked(t)

        @JavascriptInterface
        fun found(src: String, title: String, og: String, w: Int, h: Int, dur: Double) {
            if (og.isNotBlank()) t.ogImage = og
            if (!src.startsWith("http")) return
            if (src.contains("googlevideo.com")) return
            val p = src.lowercase().substringBefore('?')
            val type = when {
                p.endsWith(".m3u8") -> "m3u8"
                p.endsWith(".mpd") -> "mpd"
                p.endsWith(".webm") -> "webm"
                p.endsWith(".mp3") -> "mp3"
                else -> "mp4"
            }
            if (title.isNotBlank() && t.title.isBlank()) t.title = title
            addVideo(t, src, type, w, h, dur)
        }

        @JavascriptInterface
        fun meta(title: String, og: String) {
            if (og.isNotBlank()) {
                t.ogImage = og
                ui.post { t.videos.values.forEach { v -> if (v.thumb == null) v.thumb = og } }
            }
        }
    }

    @Volatile private var sniffCache: String? = null
    private fun sniffJs(): String = sniffCache ?: assets.open("sniff.js").bufferedReader().readText().also { sniffCache = it }

    // ================================================================== escudo (bloqueador)

    private fun countBlocked(t: Tab) {
        t.blocked++
        if (t == tab) ui.post { updateShield() }
    }

    private fun updateShield() {
        if (!::shieldIcon.isInitialized) return
        if (blockAds) {
            shieldIcon.colorFilter = null
            shieldIcon.alpha = 1f
            val n = tab.blocked
            shieldCount.text = if (n > 99) "99+" else n.toString()
            shieldCount.visibility = if (n > 0) View.VISIBLE else View.GONE
        } else {
            shieldIcon.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
            shieldIcon.alpha = 0.45f
            shieldCount.visibility = View.GONE
        }
    }

    private fun toggleShield() {
        val on = !blockAds
        WebStore.setFlag(this, "block_ads", on)
        WebStore.setFlag(this, "block_popups", on)
        tab.blocked = 0
        updateShield()
        shield.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).withEndAction {
            shield.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
        toast(if (on) "Bloqueio de anúncios LIGADO" else "Bloqueio de anúncios DESLIGADO")
        if (!tab.isHome) tab.web.reload()
    }

    private fun shieldInfo() {
        val on = blockAds
        info(
            "Escudo do Transmiplay",
            (if (on) "Ligado: ${tab.blocked} anúncios e rastreadores bloqueados nesta página.\n\n"
            else "Desligado: os sites mostram os anúncios normalmente.\n\n") +
                "Toque no ícone pra ligar ou desligar. Ligado ele corta propagandas, pop-ups, redirecionamentos " +
                "e as camadas invisíveis que abrem anúncio quando você toca no vídeo.\n\n" +
                "Se algum site não funcionar direito, desligue o escudo e recarregue.",
        )
    }

    private fun fmtDur(sec: Double): String {
        val s = sec.toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun updateFound(pulse: Boolean = false) {
        val n = tab.videos.values.count { !it.adLike }.takeIf { it > 0 } ?: tab.videos.size
        foundBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
        foundCount.text = if (n > 9) "9+" else n.toString()
        foundCount.visibility = if (n > 1) View.VISIBLE else View.GONE
        if (pulse && n > 0) {
            foundBtn.animate().scaleX(1.3f).scaleY(1.3f).setDuration(160).withEndAction {
                foundBtn.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }.start()
        }
    }

    // ================================================================== lista de vídeo

    private var listBox: LinearLayout? = null
    private var forwardBox: CheckBox? = null

    private fun openVideoList() {
        if (videoList != null) return
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            isClickable = true
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bar)
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        head.addView(icon(R.drawable.ic_back) { closeVideoList() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        head.addView(text("Lista de vídeo", 18f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        head.addView(icon(R.drawable.ic_subtitles) { showSubtitles() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        head.addView(icon(R.drawable.ic_cast) { showDevices() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val more = icon(R.drawable.ic_more)
        more.setBackgroundResource(android.R.drawable.list_selector_background)
        more.setOnClickListener {
            val pm = PopupMenu(this, more)
            pm.menu.add(0, 1, 0, "Limpar lista")
            pm.menu.add(0, 2, 0, "O que é \"encaminhar\"?")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { tab.videos.clear(); updateFound(); renderVideoList() }
                    2 -> info(
                        "Encaminhar pelo telefone",
                        "Normalmente cada TV baixa o vídeo direto do site. Alguns sites só entregam o vídeo pro aparelho " +
                            "que abriu a página. Marcando essa opção, o vídeo passa pelo celular antes de ir pras TVs. " +
                            "Use se a TV mostrar erro ou ficar carregando. Gasta mais Wi-Fi e bateria do celular.",
                    )
                }
                true
            }
            pm.show()
        }
        head.addView(more, LinearLayout.LayoutParams(dp(40), dp(48)))
        screen.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val fwd = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(10))
        }
        fwd.addView(text("Encaminhar o vídeo através do telefone", 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        forwardBox = CheckBox(this).apply {
            isChecked = WebStore.getFlag(this@BrowserActivity, "forward", false)
            setOnCheckedChangeListener { _, c -> WebStore.setFlag(this@BrowserActivity, "forward", c) }
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        fwd.addView(forwardBox)
        fwd.setOnClickListener { forwardBox?.toggle() }
        screen.addView(fwd)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(12)) }
        screen.addView(ScrollView(this).apply { addView(listBox) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        videoList = screen
        root.addView(screen, root.indexOfChild(drawer), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        renderVideoList()
    }

    private fun closeVideoList() {
        videoList?.let { root.removeView(it) }
        videoList = null
        listBox = null
    }

    private fun renderVideoList() {
        val box = listBox ?: return
        box.removeAllViews()
        // vídeo principal primeiro; o que parece propaganda vai pro fim
        val list = tab.videos.values.reversed().sortedWith(
            compareBy<Video> { it.adLike }.thenByDescending { it.score },
        )
        if (list.isEmpty()) {
            box.addView(text("Nenhum vídeo achado nessa página ainda. Dê play no vídeo do site e volte aqui.", 14f, soft).apply {
                setPadding(dp(8), dp(24), dp(8), 0)
            })
            return
        }
        for (v in list) box.addView(videoRow(v))
    }

    private fun videoRow(v: Video): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(8), 0, dp(8))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { cast(v) }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#2A2A2A"))
        }
        row.addView(img, LinearLayout.LayoutParams(dp(112), dp(72)))
        loadThumb(v.thumb, v.pageUrl, img)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        col.addView(text(v.pageTitle, 13f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(text(v.fileName, 13f, soft).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(10), 0, dp(2))
        })
        val durLabel = if (v.durSec >= 1) " · " + fmtDur(v.durSec) else ""
        val typeLabel = when {
            v.ytId != null -> "YouTube (sincronizado)"
            v.w > 0 -> "${v.type} (${v.w}x${v.h})"
            else -> v.type
        }
        col.addView(text(
            if (v.adLike) "Propaganda (provável) · $typeLabel$durLabel" else "$typeLabel$durLabel",
            11f, if (v.adLike) Color.parseColor("#FF8A80") else green,
        ))
        if (v.adLike) row.alpha = 0.55f
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val dots = icon(R.drawable.ic_more, soft, 10)
        dots.setOnClickListener {
            val pm = PopupMenu(this, dots)
            pm.menu.add(0, 1, 0, "Transmitir nas TVs")
            pm.menu.add(0, 2, 0, "Transmitir passando pelo celular")
            pm.menu.add(0, 3, 0, "Copiar link do vídeo")
            pm.menu.add(0, 4, 0, "Remover da lista")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> cast(v, forceForward = false)
                    2 -> cast(v, forceForward = true)
                    3 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("video", if (v.ytId != null) v.pageUrl else v.url))
                        toast("Link copiado")
                    }
                    4 -> { tab.videos.remove(v.url); updateFound(); renderVideoList() }
                }
                true
            }
            pm.show()
        }
        row.addView(dots, LinearLayout.LayoutParams(dp(36), dp(40)))
        return row
    }

    private fun loadThumb(url: String?, referer: String, into: ImageView) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        thumbs[url]?.let { into.setImageBitmap(it); return }
        into.tag = url
        io.execute {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Referer", referer)
                val bytes = c.inputStream.use { it.readBytes() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                while (o.outWidth / sample > 480) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@execute
                thumbs[url] = bmp
                ui.post { if (into.tag == url) into.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }
    }

    // ================================================================== transmitir

    private fun headersFor(v: Video): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = userAgent
        h["Referer"] = v.referer
        try {
            val p = URL(v.referer)
            h["Origin"] = "${p.protocol}://${p.host}" + (if (p.port > 0) ":${p.port}" else "")
        } catch (_: Exception) {}
        CookieManager.getInstance().getCookie(v.url)?.let { if (it.isNotBlank()) h["Cookie"] = it }
        return h
    }

    private fun cast(v: Video, forceForward: Boolean? = null) {
        if (Hub.tvs.isEmpty()) {
            toast("Nenhuma TV conectada. Abra o Simulplay na TV, no mesmo Wi-Fi.")
            showDevices()
            return
        }
        val item = if (v.ytId != null) {
            Hub.Item("youtube", v.ytId, v.pageTitle, thumb = v.thumb)
        } else {
            val fwd = forceForward ?: (forwardBox?.isChecked ?: WebStore.getFlag(this, "forward", false))
            Hub.Item(
                "media", v.url, v.pageTitle,
                headers = headersFor(v),
                mime = v.type,
                proxy = fwd,
                thumb = v.thumb,
            )
        }
        Hub.playItem(item)
        // pausa o vídeo no celular pra não tocar em dobro
        tab.web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null)
        closeVideoList()
        toast("Mandando pras TVs: ${v.pageTitle}")
    }

    private fun refreshMini() {
        val cur = Hub.current
        mini.visibility = if (cur != null || Hub.caixinha) View.VISIBLE else View.GONE
        miniTitle.text = when {
            Hub.caixinha -> "Modo caixinha ligado"
            cur != null -> cur.title
            else -> ""
        }
        miniPlay.setImageResource(if (Hub.playing) R.drawable.ic_pause else R.drawable.ic_play)
        miniPlay.visibility = if (cur != null) View.VISIBLE else View.GONE
        castIcon.setColorFilter(if (Hub.tvs.isNotEmpty()) blue else Color.WHITE)
        if (devicesBox != null) fillDevices()
        if (Hub.lastError != null) {
            toast(Hub.lastError!!)
            Hub.lastError = null
        }
    }

    // ================================================================== escolher TVs

    private var devicesBox: LinearLayout? = null

    private fun showDevices() {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Neon.cardHi)
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        head.addView(text("Selecione o dispositivo para transmitir para", 18f))
        head.addView(text("Procurando por:", 13f, Color.parseColor("#DDDDDD")).apply { setPadding(0, dp(12), 0, 0) })
        head.addView(text("Simulplay (Android TV, Google TV, Fire TV)", 12f, soft))
        c.addView(head)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        devicesBox = box
        c.addView(box)
        fillDevices()
        c.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        fun item(t: String, f: () -> Unit) = text(t, 15f).apply {
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { f() }
        }
        c.addView(item("Solucionar problemas") {
            info(
                "Solucionar problemas",
                "• Abra o app Simulplay em cada TV.\n" +
                    "• Celular e TVs precisam estar no mesmo Wi-Fi (ou TV no roteador do celular).\n" +
                    "• Alguns roteadores bloqueiam aparelhos de se verem (\"isolamento de clientes\" / rede de convidados). Use a rede principal.\n" +
                    "• Feche e abra o Simulplay na TV se ela não aparecer em 10 segundos.\n" +
                    "• Se o vídeo não carregar na TV, marque \"Encaminhar o vídeo através do telefone\" na Lista de vídeo.",
            )
        })
        c.addView(item("Ajustar atraso das TVs") { startActivity(Intent(this, MainActivity::class.java)) })
        c.addView(item("Instalar o Simulplay na TV") {
            info(
                "Instalar na TV",
                "No Fire TV ou na TV Android, instale o app Downloader e digite:\n\n" +
                    "github.com/XProzzinho/Simuplay/releases/latest/download/Simulplay.apk",
            )
        })
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(ScrollView(this).apply { addView(c) })
            .setPositiveButton("Fechar", null)
            .setOnDismissListener { devicesBox = null }
            .create()
        d.show()
    }

    private fun fillDevices() {
        val box = devicesBox ?: return
        box.removeAllViews()
        if (Hub.tvs.isEmpty()) {
            box.addView(text("Procurando TVs com o Simulplay aberto…", 14f, soft).apply {
                setPadding(dp(20), dp(16), dp(20), dp(16))
            })
            return
        }
        for (tv in Hub.tvs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(12), dp(10))
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            row.addView(icon(R.drawable.ic_tv, if (tv.enabled) blue else soft, 6), LinearLayout.LayoutParams(dp(40), dp(40)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            col.addView(text(tv.name, 15f))
            col.addView(text(if (tv.enabled) "Recebendo" else "Desligada desta transmissão", 12f, soft))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val cb = CheckBox(this).apply {
                isChecked = tv.enabled
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnCheckedChangeListener { _, on -> tv.setOn(on) }
            }
            row.addView(cb)
            row.setOnClickListener { cb.toggle() }
            box.addView(row)
        }
    }

    // ================================================================== legendas

    private fun showSubtitles() {
        val labels = mutableListOf("Remover legendas", "Arquivo local (.srt / .vtt)", "Legendas a partir de URL")
        val found = tab.subs.values.toList()
        found.forEach { labels += "Da página: " + (Uri.parse(it.url).lastPathSegment ?: it.url) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Legendas")
            .setItems(labels.toTypedArray()) { _, i ->
                when (i) {
                    0 -> { Hub.setSubtitle(null); toast("Legendas removidas") }
                    1 -> {
                        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        try { startActivityForResult(pick, REQ_SUB) } catch (_: Exception) { toast("Não achei o seletor de arquivos") }
                    }
                    2 -> askSubUrl()
                    else -> {
                        val s = found[i - 3]
                        Hub.setSubtitle(Hub.Sub(s.url, s.mime, false))
                        toast("Legenda escolhida")
                    }
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun askSubUrl() {
        val input = EditText(this).apply {
            hint = "https://.../legenda.srt"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Legenda a partir de URL")
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("Usar") { _, _ ->
                val u = input.text.toString().trim()
                if (u.startsWith("http")) {
                    Hub.setSubtitle(Hub.Sub(u, subMime(u), false))
                    toast("Legenda escolhida")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun subMime(name: String) =
        if (name.lowercase().substringBefore('?').endsWith(".vtt")) "text/vtt" else "application/x-subrip"

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SUB && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            var name = u.lastPathSegment ?: ""
            try {
                contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0)
                }
            } catch (_: Exception) {}
            Hub.subUri = u
            Hub.setSubtitle(Hub.Sub(u.toString(), subMime(name), true))
            toast("Legenda escolhida: $name")
        }
    }

    // ================================================================== menu ⋮

    private fun showMore(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        if (!tab.isHome) {
            m.add(0, 1, 0, "Voltar")
            m.add(0, 2, 0, "Avançar")
            m.add(0, 3, 0, "Recarregar")
            m.add(0, 4, 0, if (WebStore.isFavorite(this, tab.pageUrl)) "Tirar dos favoritos" else "Favoritar esta página")
            m.add(0, 5, 0, "Copiar link")
            m.add(0, 6, 0, "Compartilhar")
        }
        m.add(0, 7, 0, "Página inicial")
        m.add(0, 8, 0, "Nova aba")
        m.add(0, 9, 0, "Histórico")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> if (tab.web.canGoBack()) tab.web.goBack() else goHome()
                2 -> if (tab.web.canGoForward()) tab.web.goForward()
                3 -> tab.web.reload()
                4 -> toast(if (WebStore.toggleFavorite(this, tab.pageUrl, tab.title)) "Adicionado aos favoritos" else "Tirado dos favoritos")
                5 -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("link", tab.pageUrl))
                    toast("Link copiado")
                }
                6 -> startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.pageUrl), "Compartilhar",
                ))
                7 -> goHome()
                8 -> newTab(null)
                9 -> showHistory()
            }
            true
        }
        pm.show()
    }

    private fun showHistory() {
        val hist = WebStore.history(this)
        if (hist.isEmpty()) { toast("Histórico vazio"); return }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dlg: AlertDialog
        hist.take(100).forEach { s ->
            c.addView(siteRow(s, "history") { dlg.dismiss(); showHistory() }.apply {
                setOnClickListener { dlg.dismiss(); open(s.url) }
            })
        }
        dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Histórico do navegador")
            .setView(ScrollView(this).apply { addView(c) })
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Limpar tudo") { _, _ -> WebStore.clearHistory(this); toast("Histórico limpo") }
            .create()
        dlg.show()
    }

    private fun showFavorites() {
        val fav = WebStore.favorites(this)
        if (fav.isEmpty()) { toast("Nenhum favorito ainda"); return }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dlg: AlertDialog
        fav.forEach { s ->
            c.addView(siteRow(s, "fav") { dlg.dismiss(); showFavorites() }.apply {
                setOnClickListener { dlg.dismiss(); open(s.url) }
            })
        }
        dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Favoritos")
            .setView(ScrollView(this).apply { addView(c) })
            .setPositiveButton("Fechar", null)
            .create()
        dlg.show()
    }

    // ================================================================== menu lateral

    private fun buildDrawer(): FrameLayout {
        val d = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { closeDrawer() }
        }
        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#120E3A"))
            isClickable = true
            setPadding(0, dp(18), 0, dp(12))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(14)) }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(54), dp(54)))
        head.addView(Neon.brand(this, "Transmi", "play", 19f).apply { setPadding(0, dp(8), 0, 0) })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        head.addView(text("v$ver", 12f, soft))
        drawerPanel.addView(head)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(this@BrowserActivity, Neon.cardHi, 30, Neon.purple2, 1)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener { closeDrawer(); if (tab.isHome) fillHome() }
        }
        nav.addView(icon(R.drawable.ic_globe, Color.WHITE, 8), LinearLayout.LayoutParams(dp(40), dp(40)))
        nav.addView(text("Navegador", 14f).apply { setPadding(dp(14), 0, 0, 0) })
        drawerPanel.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            marginStart = dp(8); marginEnd = dp(8)
        })

        fun entry(ic: Int, label: String, indent: Boolean = false, f: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (indent) 30 else 16), dp(4), dp(16), dp(4))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { closeDrawer(); f() }
            }
            r.addView(icon(ic, Color.WHITE, 9), LinearLayout.LayoutParams(dp(42), dp(42)))
            r.addView(text(label, 14f).apply { setPadding(dp(16), 0, 0, 0) })
            drawerPanel.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        fun divider() = drawerPanel.addView(View(this).apply { setBackgroundColor(Color.parseColor("#333333")) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8); marginStart = dp(16); marginEnd = dp(16) })

        entry(R.drawable.ic_star, "Favoritos", true) { showFavorites() }
        entry(R.drawable.ic_history, "Histórico do navegador", true) { showHistory() }
        entry(R.drawable.ic_home, "Página inicial", true) { goHome() }
        divider()
        entry(R.drawable.ic_folder, "Arquivos do telefone") { openMain(1) }
        entry(R.drawable.ic_link, "Link direto") { openMain(0) }
        entry(R.drawable.ic_speaker, "Caixinha (som do celular)") { openMain(2) }
        divider()
        entry(R.drawable.ic_tv, "TVs e ajuste de atraso") { openMain(-1) }
        entry(R.drawable.ic_settings, "Configurações") { showSettings() }

        val scroll = ScrollView(this).apply { addView(drawerPanel); setBackgroundColor(Color.parseColor("#120E3A")) }
        d.addView(scroll, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.8f).toInt(), MATCH_PARENT))
        return d
    }

    private fun openMain(mode: Int) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_MODE, mode))
    }

    private fun openDrawer() {
        hideKeyboard()
        drawer.visibility = View.VISIBLE
        val panel = drawer.getChildAt(0)
        panel.translationX = -panel.width.toFloat().coerceAtLeast(dp(300).toFloat())
        panel.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeDrawer() {
        val panel = drawer.getChildAt(0)
        panel.animate().translationX(-panel.width.toFloat()).setDuration(180).withEndAction {
            drawer.visibility = View.GONE
        }.start()
    }

    private fun showSettings() {
        val items = arrayOf("Bloquear pop-ups", "Bloquear propagandas conhecidas", "Sempre restaurar as abas")
        val keys = arrayOf("block_popups", "block_ads", "always_restore")
        val defs = booleanArrayOf(true, true, false)
        val checked = BooleanArray(3) { WebStore.getFlag(this, keys[it], defs[it]) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Configurações")
            .setMultiChoiceItems(items, checked) { _, i, v -> WebStore.setFlag(this, keys[i], v) }
            .setNeutralButton("Limpar dados dos sites") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                tabs.forEach { it.web.clearCache(true) }
                toast("Cookies e cache apagados")
            }
            .setPositiveButton("OK", null)
            .show()
    }

    // ================================================================== utilidades

    private fun info(title: String, msg: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val REQ_SUB = 21

        /** procura <video> na página e avisa o app (roda em cada página) */
        const val SCAN_JS = """(function(){
  function og(){var m=document.querySelector('meta[property="og:image"],meta[name="twitter:image"]');return m?(m.content||''):'';}
  function scan(){
    try{
      TP.meta(document.title||'', og());
      var els=document.querySelectorAll('video,video source,audio');
      for(var i=0;i<els.length;i++){
        var v=els[i]; var s=v.currentSrc||v.src||'';
        var vid=v.tagName==='SOURCE'?v.parentElement:v;
        if(s&&s.indexOf('blob:')!==0&&s.indexOf('data:')!==0)
          TP.found(s,document.title||'',og(),(vid&&vid.videoWidth)||0,(vid&&vid.videoHeight)||0,(vid&&isFinite(vid.duration)&&vid.duration)||0);
      }
    }catch(e){}
  }
  if(!window.__tpOn){
    window.__tpOn=1;
    document.addEventListener('play',function(){setTimeout(scan,300);},true);
    document.addEventListener('loadedmetadata',function(){scan();},true);
    setInterval(scan,3000);
  }
  scan();
})();"""

        /** propagandas e pop-ups mais comuns em sites de vídeo */
        val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "adsterratools.com",
            "exoclick.com", "juicyads.com", "trafficjunky.net", "onclickads.net", "onclickmax.com",
            "hilltopads.net", "adcash.com", "clickadu.com", "mgid.com", "taboola.com", "outbrain.com",
            "a-ads.com", "monetag.com", "highperformanceformat.com", "profitablegatecpm.com",
            "realsrv.com", "pemsrv.com", "tsyndicate.com", "dtscout.com", "adnxs.com", "zeydoo.com",
            "imasdk.googleapis.com", "spotxchange.com", "springserve.com", "vidoomy.com", "aniview.com",
            "teads.tv", "connatix.com", "primis.tech", "unrulymedia.com", "smartadserver.com", "adform.net",
            "pubmatic.com", "rubiconproject.com", "openx.net", "criteo.com", "a-mo.net", "adsrvr.org",
        )
    }
}
