package com.geanalves.transmiplay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.geanalves.transmiplay.Neon.dp
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import kotlin.concurrent.thread

/**
 * Tela principal do Transmiplay no visual roxo: Início, Navegador, Controle e Configurações.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var tab = 0          // 0 início, 2 controle, 3 configurações (1 abre o navegador)
    private var source = -1      // 0 link, 1 arquivos, 2 som do celular

    private lateinit var root: FrameLayout
    private lateinit var pages: List<View>
    private lateinit var navItems: List<Pair<ImageView, TextView>>
    private lateinit var updateBar: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var updateBtn: TextView

    // início
    private lateinit var wifiPill: LinearLayout
    private lateinit var wifiTitle: TextView
    private lateinit var wifiSub: TextView
    private lateinit var sourceCards: List<LinearLayout>
    private lateinit var linkPanel: LinearLayout
    private lateinit var filesPanel: LinearLayout
    private lateinit var boxPanel: LinearLayout
    private lateinit var linkInput: EditText
    private lateinit var trackBox: LinearLayout
    private lateinit var boxButton: LinearLayout
    private lateinit var boxButtonText: TextView
    private lateinit var tvList: LinearLayout
    private lateinit var selectedText: TextView

    // controle
    private lateinit var ctrlEmpty: LinearLayout
    private lateinit var ctrlActive: LinearLayout
    private lateinit var orbit: OrbitView
    private lateinit var tvCountText: TextView
    private lateinit var thumb: ImageView
    private lateinit var thumbBadge: TextView
    private lateinit var nowTitle: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var prevBtn: View
    private lateinit var nextBtn: View
    private lateinit var syncCard: TextView
    private var dragging = false
    private var thumbUrl: String? = null

    // configurações
    private lateinit var qualityRadios: List<Neon.Radio>
    private lateinit var versionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Neon.bgTop
        window.navigationBarColor = Color.WHITE
        // ícones da barra de cima e de baixo escuros (fundo claro)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        TransmitService.start(this)
        Hub.start(applicationContext)
        source = getPreferences(MODE_PRIVATE).getInt("source", -1)
        setContentView(buildUi())
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) showSplash()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        handleIntent(intent)
        Updater.check(this, autoDownload = autoUpdate())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Hub.listener = { refresh() }
        Updater.listener = { refreshUpdate() }
        refresh()
        refreshUpdate()
        ui.post(ticker)
        Updater.check(this, autoDownload = autoUpdate())
    }

    override fun onPause() {
        super.onPause()
        Hub.listener = null
        Updater.listener = null
        ui.removeCallbacks(ticker)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0) selectTab(0) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
        }
    }

    private fun autoUpdate() = getSharedPreferences("transmiplay", MODE_PRIVATE).getBoolean("auto_update", true)

    // ================================================================== entradas

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val mode = i.getIntExtra(EXTRA_MODE, -2)
        if (mode >= 0) { selectTab(0); selectSource(mode) }
        if (mode == -1) selectTab(0)
        if (i.getBooleanExtra(EXTRA_CONTROL, false)) selectTab(2)
        when (i.action) {
            Intent.ACTION_SEND -> {
                val type = i.type ?: ""
                if (type.startsWith("text")) {
                    val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    val url = Regex("""https?://\S+""").find(text)?.value ?: return
                    selectSource(0)
                    linkInput.setText(url)
                    Hub.playLink(url)
                    selectTab(2)
                } else {
                    @Suppress("DEPRECATION")
                    val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                    selectSource(1)
                    Hub.addTracks(listOf(u))
                }
                i.action = null
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val list = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                selectSource(1)
                Hub.addTracks(list)
                i.action = null
            }
        }
    }

    // ================================================================== montagem

    private fun d(v: Int) = dp(this, v)
    private fun t(s: CharSequence, size: Float = 15f, color: Int = Neon.white, bold: Boolean = false) =
        Neon.text(this, s, size, color, bold)

    private fun buildUi(): View {
        root = FrameLayout(this).apply { background = Neon.background() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- topo: ☰  [logo] Transmiplay  ⚙
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(6), d(10), d(6), d(6))
        }
        val menu = Neon.icon(this, R.drawable.ic_menu, Neon.white, 10)
        menu.setBackgroundResource(android.R.drawable.list_selector_background)
        menu.setOnClickListener { showMenu(menu) }
        top.addView(menu, LinearLayout.LayoutParams(d(46), d(46)))
        val brandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        brandRow.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(d(30), d(30)))
        brandRow.addView(Neon.brand(this, "Transmi", "play", 22f).apply { setPadding(d(8), 0, 0, 0) })
        top.addView(brandRow, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(Neon.icon(this, R.drawable.ic_settings, Neon.lilac, 10) { selectTab(3) }, LinearLayout.LayoutParams(d(46), d(46)))
        col.addView(top)

        // ---- barrinha de atualização
        updateBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(this@MainActivity, Neon.cardHi, 14, Neon.purple2, 1)
            setPadding(d(12), d(8), d(8), d(8))
            visibility = View.GONE
        }
        updateBar.addView(Neon.icon(this, R.drawable.ic_update, Neon.lilac, 4), LinearLayout.LayoutParams(d(28), d(28)))
        updateText = t("", 13f).apply { setPadding(d(8), 0, d(8), 0) }
        updateBar.addView(updateText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        updateBtn = t("Atualizar", 14f, Neon.onAccent, true).apply {
            background = Neon.buttonBg(this@MainActivity, 18)
            setPadding(d(16), d(8), d(16), d(8))
            setOnClickListener { onUpdateClick() }
        }
        updateBar.addView(updateBtn)
        col.addView(updateBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            marginStart = d(14); marginEnd = d(14); bottomMargin = d(6)
        })

        // ---- páginas
        val holder = FrameLayout(this)
        pages = listOf(buildHome(), View(this), buildControl(), buildSettings())
        pages.forEach { holder.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        col.addView(holder, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ---- barra de baixo
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(Neon.stroke), android.graphics.drawable.ColorDrawable(Color.WHITE),
            )).apply { setLayerInset(1, 0, d(1), 0, 0) }
            setPadding(0, d(8), 0, d(10))
        }
        val navDefs = listOf(
            R.drawable.ic_home to "Início",
            R.drawable.ic_globe to "Navegador",
            R.drawable.ic_bars to "Controle",
            R.drawable.ic_settings to "Configurações",
        )
        navItems = navDefs.mapIndexed { i, (ic, label) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { selectTab(i) }
            }
            val iv = Neon.icon(this, ic, Neon.soft, 2)
            val tv = t(label, 11f, Neon.soft).apply { gravity = Gravity.CENTER; maxLines = 1 }
            item.addView(iv, LinearLayout.LayoutParams(d(28), d(28)))
            item.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            nav.addView(item, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            iv to tv
        }
        col.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        selectTab(0)
        selectSource(source)
        return root
    }

    private fun selectTab(i: Int) {
        if (i == 1) {
            startActivity(Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            return
        }
        tab = i
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { k, (iv, tv) ->
            val c = if (k == i) Neon.purple2 else Neon.soft
            iv.setColorFilter(c); tv.setTextColor(c)
        }
        refresh()
    }

    private fun scrollPage(content: LinearLayout) = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
    }

    private fun section(title: String, action: View? = null): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(2), d(18), d(2), d(10))
        }
        r.addView(t(title, 17f, Neon.white, true).apply { letterSpacing = 0.01f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (action != null) r.addView(action)
        return r
    }

    // ================================================================== INÍCIO

    private fun buildHome(): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), d(4), d(16), d(20)) }

        // Wi-Fi
        wifiPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(10), d(8), d(18), d(8))
        }
        val check = ImageView(this).apply {
            setImageResource(R.drawable.ic_check); setColorFilter(Neon.onAccent)
            background = Neon.circle(Neon.green); setPadding(d(6), d(6), d(6), d(6))
        }
        wifiPill.addView(check, LinearLayout.LayoutParams(d(34), d(34)))
        val wcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, 0, 0) }
        wifiTitle = t("Rede Wi-Fi conectada", 15f, Color.parseColor("#047857"), true)
        wifiSub = t("", 12f, Color.parseColor("#15803D"))
        wcol.addView(wifiTitle); wcol.addView(wifiSub)
        wifiPill.addView(wcol)
        c.addView(FrameLayout(this).apply {
            addView(wifiPill, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(4) })

        // o que transmitir
        c.addView(section("O que transmitir?"))
        val defs = listOf(
            Triple(R.drawable.ic_globe, "Sites e\nfilmes", -10),
            Triple(R.drawable.ic_folder, "Vídeos do\ncelular", 1),
            Triple(R.drawable.ic_link, "Link\n(YouTube...)", 0),
            Triple(R.drawable.ic_speaker, "Som do\ncelular", 2),
        )
        val cards = mutableListOf<LinearLayout>()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (rowI in 0..1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (colI in 0..1) {
                val (ic, label, key) = defs[rowI * 2 + colI]
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = Neon.cardBg(this@MainActivity, Neon.card, 22, 0, 0)
                    setPadding(d(8), d(20), d(8), d(18))
                    setOnClickListener { if (key == -10) selectTab(1) else selectSource(key) }
                    tag = key
                }
                card.addView(Neon.icon(this, ic, Neon.lilac, 2), LinearLayout.LayoutParams(d(40), d(40)))
                card.addView(t(label, 15f, Neon.white, true).apply { gravity = Gravity.CENTER; setPadding(0, d(8), 0, 0) })
                row.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (colI == 0) marginEnd = d(6) else marginStart = d(6)
                    bottomMargin = d(12)
                })
                cards += card
            }
            grid.addView(row)
        }
        sourceCards = cards
        c.addView(grid)

        // painel link
        linkPanel = panel()
        linkPanel.addView(t("Cole um link do YouTube ou de um vídeo/áudio (mp4, mp3, m3u8)", 13f, Neon.soft))
        linkInput = EditText(this).apply {
            hint = "https://..."
            setHintTextColor(Neon.soft); setTextColor(Neon.white)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            background = Neon.cardBg(this@MainActivity, Color.parseColor("#F8FAFC"), 12)
            setPadding(d(12), d(12), d(12), d(12))
        }
        linkPanel.addView(linkInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(10); bottomMargin = d(10) })
        val lr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lr.addView(smallButton("Colar") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.let { linkInput.setText(it) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        lr.addView(Neon.bigButton(this, "Transmitir", R.drawable.ic_cast) {
            val u = linkInput.text.toString().trim()
            if (!u.startsWith("http")) toast("Cole um link que comece com http")
            else { Hub.playLink(u); selectTab(2) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f).apply { marginStart = d(10) })
        linkPanel.addView(lr)
        c.addView(linkPanel)

        // painel arquivos
        filesPanel = panel()
        filesPanel.addView(Neon.bigButton(this, "Escolher vídeos e músicas", R.drawable.ic_folder) { pickMedia() })
        trackBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d(10), 0, 0) }
        filesPanel.addView(trackBox)
        c.addView(filesPanel)

        // painel caixinha
        boxPanel = panel()
        boxPanel.addView(t("Toque o que quiser no celular (qualquer app ou site): o som sai nas TVs, como uma caixinha Bluetooth.", 13f, Neon.soft))
        boxButton = Neon.bigButton(this, "Ligar som nas TVs", R.drawable.ic_speaker) { toggleCaixinha() }
        boxButtonText = (boxButton.getChildAt(1) as TextView)
        boxPanel.addView(boxButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(12) })
        boxPanel.addView(t("Precisa de Android 10 ou mais novo. Alguns apps bloqueiam a captura do som.", 12f, Neon.soft).apply {
            setPadding(0, d(8), 0, 0)
        })
        c.addView(boxPanel)

        // TVs
        c.addView(section("Televisões encontradas", Neon.icon(this, R.drawable.ic_refresh, Neon.lilac, 6) {
            toast("Procurando… abra o Simulplay nas TVs (mesmo Wi-Fi)")
            refresh()
        }.apply { layoutParams = LinearLayout.LayoutParams(d(36), d(36)) }))
        tvList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(tvList)

        val sel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(this@MainActivity)
            setPadding(d(14), d(14), d(14), d(14))
        }
        sel.addView(Neon.icon(this, R.drawable.ic_cast, Neon.purple2, 6), LinearLayout.LayoutParams(d(38), d(38)))
        selectedText = t("", 14f).apply { setPadding(d(12), 0, 0, 0) }
        sel.addView(selectedText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        c.addView(sel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(4) })

        c.addView(Neon.bigButton(this, "Transmitir para todas", R.drawable.ic_play) { transmitAll() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(16) })
        return scrollPage(c)
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Neon.cardBg(this@MainActivity, Neon.cardHi, 16, Neon.purple, 1)
        setPadding(d(14), d(14), d(14), d(14))
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(4) }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = t(label, 15f, Neon.white, true).apply {
        gravity = Gravity.CENTER
        background = Neon.cardBg(this@MainActivity, Neon.card, 26)
        setPadding(d(12), d(14), d(12), d(14))
        setOnClickListener { onClick() }
    }

    private fun selectSource(k: Int) {
        source = if (source == k && k >= 0 && ::linkPanel.isInitialized && panelFor(k)?.visibility == View.VISIBLE) -1 else k
        getPreferences(MODE_PRIVATE).edit().putInt("source", source).apply()
        if (!::sourceCards.isInitialized) return
        sourceCards.forEach { card ->
            val on = card.tag == source
            card.background = Neon.cardBg(this, if (on) Neon.cardHi else Neon.card, 22,
                if (on) Neon.purple2 else Neon.stroke, if (on) 2 else 0)
        }
        listOf(0 to linkPanel, 1 to filesPanel, 2 to boxPanel).forEach { (k2, p) ->
            p.visibility = if (k2 == source) View.VISIBLE else View.GONE
        }
    }

    private fun panelFor(k: Int) = when (k) { 0 -> linkPanel; 1 -> filesPanel; 2 -> boxPanel; else -> null }

    private fun transmitAll() {
        Hub.tvs.forEach { if (!it.enabled) it.setOn(true) }
        when {
            Hub.tvs.isEmpty() -> toast("Nenhuma TV ainda. Abra o Simulplay na TV, no mesmo Wi-Fi.")
            Hub.current != null || Hub.caixinha -> selectTab(2)
            source == 0 && linkInput.text.startsWith("http") -> { Hub.playLink(linkInput.text.toString().trim()); selectTab(2) }
            source == 1 && Hub.tracks.isNotEmpty() -> { Hub.playTrack(0); selectTab(2) }
            source == 1 -> pickMedia()
            source == 2 -> toggleCaixinha()
            else -> selectTab(1)
        }
    }

    // ================================================================== CONTROLE

    private fun buildControl(): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d(16), d(4), d(16), d(20))
        }
        // vazio
        ctrlEmpty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        ctrlEmpty.addView(ImageView(this).apply { setImageResource(R.drawable.logo); alpha = 0.9f }, LinearLayout.LayoutParams(d(120), d(120)).apply { topMargin = d(40) })
        ctrlEmpty.addView(t("Nada transmitindo agora", 20f, Neon.white, true).apply { gravity = Gravity.CENTER; setPadding(0, d(20), 0, d(6)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ctrlEmpty.addView(t("Escolha um site, um vídeo do celular, um link ou o som do celular na aba Início.", 14f, Neon.soft).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ctrlEmpty.addView(Neon.bigButton(this, "Abrir o navegador", R.drawable.ic_globe) { selectTab(1) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(24) })
        c.addView(ctrlEmpty, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ativo: "TVs em órbita"
        ctrlActive = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        ctrlActive.addView(t("Transmissão Ativa", 22f, Neon.white, true).apply { gravity = Gravity.CENTER; setPadding(0, d(4), 0, d(2)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ctrlActive.addView(t("Toque numa TV para tirar ou pôr na transmissão", 13f, Neon.soft).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        orbit = OrbitView(this).apply {
            contentDescription = "Controle da transmissão: toque no meio para tocar ou pausar, arraste o anel para avançar"
            onToggle = { Hub.togglePlay() }
            onTv = { i -> Hub.tvs.getOrNull(i)?.let { it.setOn(!it.enabled) } }
            onSeek = { p -> Hub.current?.durMs?.takeIf { it > 0 }?.let { Hub.seek((it * p).toLong()) } }
        }
        ctrlActive.addView(orbit, LinearLayout.LayoutParams(MATCH_PARENT, d(390)).apply { topMargin = d(8) })
        ctrlActive.addView(t("Transmitindo para", 15f, Neon.soft).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        tvCountText = t("", 32f, Neon.lilac, true).apply { gravity = Gravity.CENTER }
        ctrlActive.addView(tvCountText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // o que está tocando (cartão de vidro)
        val nowCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(this@MainActivity, Neon.card, 20, 0, 0)
            setPadding(d(10), d(10), d(14), d(10))
        }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.logo)
            background = Neon.cardBg(this@MainActivity, Neon.cardHi, 12, 0, 0)
            clipToOutline = true
        }
        nowCard.addView(thumb, LinearLayout.LayoutParams(d(64), d(64)))
        val nowCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0) }
        nowTitle = t("", 15f, Neon.white, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        nowCol.addView(nowTitle)
        thumbBadge = t("", 12f, Neon.lilac, true).apply { setPadding(0, d(4), 0, 0) }
        nowCol.addView(thumbBadge)
        nowCard.addView(nowCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        ctrlActive.addView(nowCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(18) })

        seek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Neon.lilac)
            thumbTintList = android.content.res.ColorStateList.valueOf(Neon.purple)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Neon.track)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) timeText.text = "${fmt(p * 1000L)} / ${fmt(Hub.current?.durMs ?: 0)}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar?) { dragging = false; Hub.seek((s?.progress ?: 0) * 1000L) }
            })
        }
        ctrlActive.addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(14) })
        timeText = t("0:00 / 0:00", 13f, Neon.soft).apply { gravity = Gravity.CENTER }
        ctrlActive.addView(timeText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, d(16), 0, 0) }
        fun ctl(ic: Int, label: String, f: () -> Unit): LinearLayout {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { f() }
                contentDescription = label
            }
            val iv = ImageView(this).apply {
                setImageResource(ic); setColorFilter(Neon.white)
                background = Neon.circle(Neon.card, Neon.stroke, d(1))
                setPadding(d(14), d(14), d(14), d(14))
            }
            b.addView(iv, LinearLayout.LayoutParams(d(52), d(52)))
            b.addView(t(label, 12f, Neon.soft).apply { gravity = Gravity.CENTER; setPadding(0, d(6), 0, 0) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            btns.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            return b
        }
        prevBtn = ctl(R.drawable.ic_prev, "Anterior") { Hub.prev() }
        ctl(R.drawable.ic_rewind10, "−10s") { Hub.skip(-10_000) }
        ctl(R.drawable.ic_forward10, "+10s") { Hub.skip(10_000) }
        nextBtn = ctl(R.drawable.ic_next, "Próxima") { Hub.next() }
        ctl(R.drawable.ic_stop, "Parar") { Hub.stopAll(); CaptureService.stop(this) }
        ctrlActive.addView(btns, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        syncCard = t("", 13f, Color.parseColor("#0E7490"), true).apply {
            gravity = Gravity.CENTER
            background = Neon.cardBg(this@MainActivity, Color.parseColor("#ECFEFF"), 20, 0, 0)
            setPadding(d(16), d(9), d(16), d(9))
        }
        ctrlActive.addView(FrameLayout(this).apply {
            addView(syncCard, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(18) })
        c.addView(ctrlActive, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return scrollPage(c)
    }

    // ================================================================== CONFIGURAÇÕES

    private fun buildSettings(): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), d(4), d(16), d(24)) }
        c.addView(t("Configurações", 22f, Neon.white, true).apply { gravity = Gravity.CENTER; setPadding(0, d(6), 0, 0) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        c.addView(section("Qualidade da Transmissão"))
        val qBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Neon.cardBg(this@MainActivity) }
        val opts = listOf("Automática" to "", "Alta Qualidade" to "(Full HD)", "Economia de Dados" to "(até 480p)")
        val radios = mutableListOf<Neon.Radio>()
        opts.forEachIndexed { i, (a, b) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(d(14), d(14), d(14), d(14))
                setOnClickListener { Hub.setQuality(i); radios.forEachIndexed { k, r -> r.checked = k == i } }
            }
            val r = Neon.Radio(this).apply { checked = Hub.quality == i }
            radios += r
            row.addView(r, LinearLayout.LayoutParams(d(24), d(24)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), 0, 0, 0) }
            col.addView(t(a, 15f)); if (b.isNotEmpty()) col.addView(t(b, 12f, Neon.soft))
            row.addView(col)
            qBox.addView(row)
            if (i < opts.size - 1) qBox.addView(divider())
        }
        qualityRadios = radios
        c.addView(qBox)

        c.addView(section("Transmissão"))
        val prefs = getSharedPreferences("transmiplay", MODE_PRIVATE)
        c.addView(toggleRow("Sincronização Automática", "Mantém todos os dispositivos no mesmo ponto", Hub.autoSync) { Hub.setAutoSync(it) })
        c.addView(toggleRow("Bloquear propagandas", "No navegador, bloqueia anúncios conhecidos",
            WebStore.getFlag(this, "block_ads", true)) { WebStore.setFlag(this, "block_ads", it) })
        c.addView(toggleRow("Bloquear pop-ups", "Impede sites de abrir janelas sozinhos",
            WebStore.getFlag(this, "block_popups", true)) { WebStore.setFlag(this, "block_popups", it) })

        c.addView(section("Atualizações"))
        c.addView(toggleRow("Atualizar sozinho", "Baixa a versão nova assim que sair", autoUpdate()) {
            prefs.edit().putBoolean("auto_update", it).apply()
        })
        versionText = t("", 13f, Neon.soft)
        c.addView(linkRow(R.drawable.ic_update, "Procurar atualização agora", versionText) {
            toast("Procurando…"); Updater.check(this, force = true, autoDownload = autoUpdate())
        })

        c.addView(section("Mais"))
        val more = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Neon.cardBg(this@MainActivity) }
        more.addView(linkRow(R.drawable.ic_tv, "Ajustar atraso das TVs", null, card = false) { adjustDialog() })
        more.addView(divider())
        more.addView(linkRow(R.drawable.ic_info, "Sobre o Transmiplay", null, card = false) {
            info("Sobre o Transmiplay",
                "Versão ${versionName()}\n\nManda vídeos, músicas, sites e o som do celular para várias TVs ao mesmo tempo, " +
                    "tudo sincronizado. Nas TVs, use o app Simulplay.\n\nFeito por Geanderson Alves.")
        })
        more.addView(divider())
        more.addView(linkRow(R.drawable.ic_help, "Ajuda e Suporte", null, card = false) {
            info("Ajuda",
                "• Abra o Simulplay em cada TV, no mesmo Wi-Fi do celular.\n" +
                    "• As TVs aparecem sozinhas em Início → Televisões encontradas.\n" +
                    "• Se uma TV ficar adiantada ou atrasada, use Ajustar atraso das TVs.\n" +
                    "• Se a TV não abrir o vídeo de um site, marque \"Encaminhar o vídeo através do telefone\" na Lista de vídeo.\n" +
                    "• Redes de convidados costumam bloquear os aparelhos de se verem: use a rede principal.")
        })
        c.addView(more)

        c.addView(Neon.bigButton(this, "Sair do App", R.drawable.ic_exit) {
            startService(Intent(this, TransmitService::class.java).setAction(TransmitService.ACTION_QUIT))
            finishAffinity()
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(22) })
        return scrollPage(c)
    }

    private fun divider() = View(this).apply { setBackgroundColor(Neon.stroke) }.also {
        it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = d(14); marginEnd = d(14) }
    }

    private fun toggleRow(title: String, sub: String, on: Boolean, f: (Boolean) -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(this@MainActivity)
            setPadding(d(14), d(14), d(14), d(14))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(t(title, 15f, Neon.white, true)); col.addView(t(sub, 12f, Neon.soft))
        r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val tg = Neon.Toggle(this, on, f)
        r.addView(tg, LinearLayout.LayoutParams(d(50), d(28)))
        r.setOnClickListener { tg.performClick() }
        r.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) }
        return r
    }

    private fun linkRow(ic: Int, title: String, sub: TextView?, card: Boolean = true, f: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            if (card) background = Neon.cardBg(this@MainActivity)
            setPadding(d(12), d(12), d(10), d(12))
            setOnClickListener { f() }
        }
        r.addView(Neon.icon(this, ic, Neon.white, 4), LinearLayout.LayoutParams(d(30), d(30)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0) }
        col.addView(t(title, 15f))
        if (sub != null) col.addView(sub)
        r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(t("›", 24f, Neon.soft))
        return r
    }

    // ================================================================== atualizar tela

    private fun refresh() {
        if (!::tvList.isInitialized) return
        // Wi-Fi
        val (ok, sub) = wifiStatus()
        wifiPill.background = Neon.cardBg(this, if (ok) Neon.greenDark else Color.parseColor("#FEF2F2"), 30,
            if (ok) Color.parseColor("#A7F3D0") else Color.parseColor("#FECACA"), 1)
        wifiTitle.text = if (ok) "Rede Wi-Fi conectada" else "Sem Wi-Fi"
        wifiTitle.setTextColor(if (ok) Color.parseColor("#047857") else Color.parseColor("#B91C1C"))
        wifiSub.text = sub
        wifiSub.setTextColor(if (ok) Color.parseColor("#15803D") else Color.parseColor("#DC2626"))
        (wifiPill.getChildAt(0) as ImageView).apply {
            setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_close)
            background = Neon.circle(if (ok) Neon.green else Neon.red)
        }

        // TVs
        tvList.removeAllViews()
        if (Hub.tvs.isEmpty()) {
            tvList.addView(t("Nenhuma TV ainda. Abra o Simulplay na TV, no mesmo Wi-Fi.", 14f, Neon.soft).apply {
                background = Neon.cardBg(this@MainActivity); setPadding(d(14), d(16), d(14), d(16))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        for (tv in Hub.tvs) tvList.addView(tvRow(tv))
        val n = Hub.activeTvs
        selectedText.text = android.text.SpannableStringBuilder().apply {
            append(if (n == 1) "1 televisão selecionada" else "$n televisões selecionadas")
            append("\n")
            val start = length
            append(if (n > 0) "Prontas para transmitir!" else "Marque as TVs acima")
            setSpan(android.text.style.ForegroundColorSpan(Neon.soft), start, length, 0)
            setSpan(android.text.style.RelativeSizeSpan(0.9f), start, length, 0)
        }

        // músicas/vídeos
        trackBox.removeAllViews()
        if (Hub.tracks.isEmpty()) trackBox.addView(t("Nenhum arquivo ainda.", 13f, Neon.soft))
        Hub.tracks.forEachIndexed { i, tr ->
            val isCur = i == Hub.currentIndex && Hub.current != null
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = Neon.cardBg(this@MainActivity, if (isCur) Neon.cardHi else Neon.card, 12, if (isCur) Neon.purple else Neon.stroke, 1)
                setPadding(d(12), d(8), d(4), d(8))
                setOnClickListener { Hub.playTrack(i); selectTab(2) }
            }
            row.addView(Neon.icon(this, if (isCur) R.drawable.ic_play else R.drawable.ic_music, Neon.lilac, 4),
                LinearLayout.LayoutParams(d(28), d(28)))
            row.addView(t(tr.title, 14f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(d(10), 0, 0, 0) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(Neon.icon(this, R.drawable.ic_close, Neon.soft, 8) { Hub.removeTrack(i) }, LinearLayout.LayoutParams(d(36), d(36)))
            trackBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(6) })
        }

        // caixinha
        boxButtonText.text = if (Hub.caixinha) "Desligar som nas TVs" else "Ligar som nas TVs"
        boxButton.background = if (Hub.caixinha) Neon.cardBg(this, Neon.red, 26, 0, 0) else Neon.buttonBg(this)

        // controle
        val cur = Hub.current
        val active = cur != null || Hub.caixinha
        ctrlEmpty.visibility = if (active) View.GONE else View.VISIBLE
        ctrlActive.visibility = if (active) View.VISIBLE else View.GONE
        tvCountText.text = if (n == 1) "1 TV" else "$n TVs"
        orbit.tvs = Hub.tvs.map { OrbitView.Tv(it.name, it.enabled) }
        orbit.playing = Hub.playing || (Hub.caixinha && cur == null)
        orbit.speaker = Hub.caixinha && cur == null
        orbit.seekable = cur != null
        if (Hub.caixinha && cur == null) {
            nowTitle.text = "Som do celular"
            thumbUrl = null
            thumb.setImageResource(R.drawable.logo)
            thumbBadge.text = "AO VIVO · modo caixinha"
            syncCard.text = "●  Som do celular tocando nas TVs"
            orbit.timeText = "AO VIVO"
        } else if (cur != null) {
            nowTitle.text = cur.title
            thumbBadge.text = when {
                cur.kind == "youtube" -> "YouTube"
                cur.uri != null -> "Do celular"
                else -> (cur.mime?.uppercase() ?: "LINK") + if (cur.proxy) " · pelo celular" else ""
            }
            val wanted = cur.thumb ?: (if (cur.kind == "youtube") "https://i.ytimg.com/vi/${cur.url}/hqdefault.jpg" else null)
            if (wanted != thumbUrl) {
                thumbUrl = wanted
                thumb.scaleType = ImageView.ScaleType.FIT_CENTER
                thumb.setImageResource(R.drawable.logo)
                if (wanted != null) loadThumb(wanted)
            }
            syncCard.text = if (Hub.autoSync) "●  Sincronizado em todas as TVs" else "●  Sincronização automática desligada"
        }
        val hasList = cur != null && Hub.currentIndex >= 0 && Hub.tracks.size > 1
        prevBtn.visibility = if (hasList) View.VISIBLE else View.GONE
        nextBtn.visibility = if (hasList) View.VISIBLE else View.GONE

        if (::versionText.isInitialized) versionText.text = "Você está na versão ${versionName()}"
        Hub.lastError?.let { toast(it); Hub.lastError = null }
        updateProgress()
    }

    private fun tvRow(tv: Hub.TvConn): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(this@MainActivity, Neon.card, 18, 0, 0)
            setPadding(d(10), d(10), d(14), d(10))
        }
        val screen = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#06B6D4"), Color.parseColor("#2563EB"))).apply { cornerRadius = d(8).toFloat() }
        }
        screen.addView(Neon.icon(this, R.drawable.ic_tv, Color.WHITE, 8), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        r.addView(screen, LinearLayout.LayoutParams(d(64), d(42)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0) }
        col.addView(t(tv.name, 16f, Neon.white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        val adj = if (tv.adj == 0) "" else " · ajuste ${if (tv.adj > 0) "+" else ""}${tv.adj} ms"
        val st = android.text.SpannableStringBuilder("● ").apply {
            setSpan(android.text.style.ForegroundColorSpan(if (tv.enabled) Neon.green else Neon.soft), 0, 1, 0)
            append(if (tv.enabled) "Conectada$adj" else "Fora desta transmissão")
        }
        col.addView(t(st, 12f, if (tv.enabled) Color.parseColor("#0E7490") else Neon.soft))
        r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(Neon.Check(this, tv.enabled) { tv.setOn(it) }, LinearLayout.LayoutParams(d(30), d(30)))
        r.setOnClickListener { adjustDialog(tv) }
        r.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(8) }
        return r
    }

    private fun updateProgress() {
        if (!::seek.isInitialized) return
        val cur = Hub.current ?: return
        val pos = Hub.position()
        val dur = cur.durMs
        seek.max = (dur / 1000).toInt().coerceAtLeast(1)
        seek.isEnabled = dur > 0
        if (!dragging) {
            seek.progress = (pos / 1000).toInt()
            timeText.text = if (dur > 0) "${fmt(pos)} / ${fmt(dur)}" else fmt(pos)
        }
        orbit.progress = if (dur > 0) pos.toFloat() / dur else 0f
        orbit.timeText = fmt(pos)
    }

    private fun loadThumb(url: String) {
        thread(isDaemon = true) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                val bytes = c.inputStream.use { it.readBytes() }
                val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@thread
                ui.post { if (thumbUrl == url) { thumb.scaleType = ImageView.ScaleType.CENTER_CROP; thumb.setImageBitmap(bmp) } }
            } catch (_: Exception) {}
        }
    }

    private fun wifiStatus(): Pair<Boolean, String> {
        var ip: String? = null
        var hotspot = false
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is java.net.Inet4Address && a.isSiteLocalAddress) {
                        if (ni.name.startsWith("wlan")) ip = a.hostAddress
                        if (ni.name.startsWith("ap") || ni.name.startsWith("swlan") || ni.name.contains("softap")) { hotspot = true; ip = ip ?: a.hostAddress }
                    }
                }
            }
        } catch (_: Exception) {}
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = if (Build.VERSION.SDK_INT >= 23) cm.getNetworkCapabilities(cm.activeNetwork) else null
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || (ip != null && !hotspot)
        return when {
            onWifi -> true to "IP do celular: ${ip ?: "—"}"
            hotspot -> true to "Roteador do celular ligado"
            else -> false to "Conecte no mesmo Wi-Fi das TVs"
        }
    }

    private fun versionName() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun info(title: String, msg: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ================================================================== atraso das TVs

    private fun adjustDialog(only: Hub.TvConn? = null) {
        val list = only?.let { listOf(it) } ?: Hub.tvs.toList()
        if (list.isEmpty()) { toast("Nenhuma TV conectada"); return }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(10), d(20), d(4)) }
        c.addView(t("Se uma TV estiver adiantada, toque em + (ela espera mais). Se estiver atrasada, toque em −.", 13f, Neon.soft))
        for (tv in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(12), 0, 0) }
            row.addView(t(tv.name, 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val v = t("${tv.adj} ms", 14f, Neon.lilac).apply { gravity = Gravity.CENTER }
            row.addView(smallButton("−") { tv.changeAdj(-20); v.text = "${tv.adj} ms" }, LinearLayout.LayoutParams(d(48), WRAP_CONTENT))
            row.addView(v, LinearLayout.LayoutParams(d(76), WRAP_CONTENT))
            row.addView(smallButton("+") { tv.changeAdj(20); v.text = "${tv.adj} ms" }, LinearLayout.LayoutParams(d(48), WRAP_CONTENT))
            c.addView(row)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Ajustar atraso").setView(c).setPositiveButton("Pronto", null).show()
    }

    // ================================================================== menu ☰

    private fun showMenu(anchor: View) {
        val pm = android.widget.PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Navegador (sites e filmes)")
        pm.menu.add(0, 2, 0, "Vídeos e músicas do celular")
        pm.menu.add(0, 3, 0, "Link direto")
        pm.menu.add(0, 4, 0, "Som do celular (caixinha)")
        pm.menu.add(0, 5, 0, "Controle da transmissão")
        pm.menu.add(0, 6, 0, "Ajustar atraso das TVs")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> selectTab(1)
                2 -> { selectTab(0); selectSource(1) }
                3 -> { selectTab(0); selectSource(0) }
                4 -> { selectTab(0); selectSource(2) }
                5 -> selectTab(2)
                6 -> adjustDialog()
            }
            true
        }
        pm.show()
    }

    // ================================================================== abertura

    private fun showSplash() {
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.WHITE, Neon.bgMid, Neon.bgBottom))
            isClickable = true
        }
        s.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(d(170), d(170)))
        s.addView(Neon.brand(this, "Transmi", "play", 42f).apply { gravity = Gravity.CENTER; setPadding(0, d(22), 0, d(6)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        s.addView(t("Transmita para várias TVs\nao mesmo tempo", 18f, Neon.soft).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val tag = android.text.SpannableStringBuilder("Conecte • ").apply {
            val a = length; append("Transmita • Aproveite")
            setSpan(android.text.style.ForegroundColorSpan(Neon.lilac), a, length, 0)
        }
        s.addView(t(tag, 16f, Neon.white, true).apply { gravity = Gravity.CENTER; setPadding(0, d(40), 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(s, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        ui.postDelayed({
            s.animate().alpha(0f).setDuration(350).withEndAction { root.removeView(s) }.start()
        }, 1300)
    }

    // ================================================================== atualização

    private fun refreshUpdate() {
        if (!::updateBar.isInitialized) return
        val r = Updater.release
        if (Updater.message.isNotEmpty()) { toast(Updater.message); Updater.clearMessage() }
        when (Updater.stage) {
            Updater.Stage.IDLE -> { updateBar.visibility = View.GONE; return }
            Updater.Stage.AVAILABLE -> {
                updateText.text = "Nova versão disponível (${r?.version})"; updateBtn.text = "Atualizar"; updateBtn.visibility = View.VISIBLE
            }
            Updater.Stage.DOWNLOADING -> {
                updateText.text = "Baixando atualização… ${Updater.percent}%"; updateBtn.visibility = View.GONE
            }
            Updater.Stage.READY -> {
                updateText.text = "Atualização pronta"; updateBtn.text = "Instalar"; updateBtn.visibility = View.VISIBLE
                if (autoUpdate() && !askedInstall) { askedInstall = true; onUpdateClick() }
            }
            Updater.Stage.INSTALLING -> { updateText.text = "Instalando…"; updateBtn.visibility = View.GONE }
            Updater.Stage.ERROR -> { updateText.text = "A atualização falhou"; updateBtn.text = "Tentar de novo"; updateBtn.visibility = View.VISIBLE }
        }
        updateBar.visibility = View.VISIBLE
    }

    private var askedInstall = false

    private fun onUpdateClick() {
        if (Updater.needsPermission(this)) {
            info("Falta uma permissão",
                "Pra atualizar sozinho, o Android precisa que você libere \"Instalar apps desconhecidos\" pro Transmiplay. " +
                    "Na próxima tela, ligue a chave e volte.")
            Updater.openPermissionScreen(this)
            return
        }
        when (Updater.stage) {
            Updater.Stage.READY -> Updater.install(this)
            else -> Updater.download(this, thenInstall = true)
        }
    }

    // ================================================================== arquivos

    private fun pickMedia() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(i, REQ_PICK) } catch (_: Exception) { toast("Não achei o seletor de arquivos") }
    }

    // ================================================================== caixinha

    private fun toggleCaixinha() {
        if (Hub.caixinha) { CaptureService.stop(this); return }
        if (Build.VERSION.SDK_INT < 29) { toast("O som do celular precisa de Android 10 ou mais novo"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleCaixinha()
            else toast("Sem a permissão de áudio não dá pra pegar o som")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { c -> for (k in 0 until c.itemCount) uris += c.getItemAt(k).uri }
            data.data?.let { if (it !in uris) uris += it }
            for (u in uris) try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            Hub.addTracks(uris)
        }
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null && Build.VERSION.SDK_INT >= 29) {
                Hub.lastError = null
                CaptureService.start(this, resultCode, data)
                selectTab(2)
            } else toast("Precisa da sua permissão pra pegar o som")
        }
    }

    companion object {
        const val REQ_PICK = 1
        const val REQ_AUDIO = 2
        const val REQ_PROJECTION = 3
        const val EXTRA_MODE = "mode"
        const val EXTRA_CONTROL = "control"
    }
}
