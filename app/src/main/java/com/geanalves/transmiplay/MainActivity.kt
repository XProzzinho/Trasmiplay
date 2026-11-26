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
    private lateinit var mirrorPanel: LinearLayout
    private lateinit var mirrorButton: LinearLayout
    private lateinit var partyPanel: LinearLayout
    private lateinit var partyBox: LinearLayout
    private lateinit var groupBar: LinearLayout
    private lateinit var otherGroups: LinearLayout
    private lateinit var ctrlTitle: TextView
    private var wantMirror = false
    private var partyKey = ""
    private var styleRowsRefresh: (() -> Unit)? = null
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
    private lateinit var queueBox: LinearLayout
    private lateinit var recentBox: LinearLayout
    private var dragging = false
    private var thumbUrl: String? = null
    private var speakerDlg: SpeakerDialog? = null
    private var lastBtMsg = ""

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
        Hub.onPlaylist = { url, list -> showChannels(url, list) }
        Updater.listener = { refreshUpdate() }
        refresh()
        refreshUpdate()
        ui.post(ticker)
        Updater.check(this, autoDownload = autoUpdate())
    }

    override fun onPause() {
        super.onPause()
        Hub.listener = null
        Hub.onPlaylist = null
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
            Triple(R.drawable.ic_cast, "Espelhar\na tela", 3),
            Triple(R.drawable.ic_star, "Modo\nfesta", 4),
        )
        val cards = mutableListOf<LinearLayout>()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (rowI in 0..2) {
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
        linkPanel.addView(t("Cole um link do YouTube, de um vídeo/áudio (mp4, mp3, m3u8) ou uma lista de canais (.m3u)", 13f, Neon.soft))
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
        lr.addView(smallButton("+ Fila") {
            val u = linkInput.text.toString().trim()
            if (!u.startsWith("http")) toast("Cole um link que comece com http")
            else { Hub.playLink(u, queueIt = true); toast(if (Hub.current != null) "Na fila: vai tocar depois do atual" else "Tocando") }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = d(8) })
        lr.addView(Neon.bigButton(this, "Transmitir", R.drawable.ic_cast) {
            val u = linkInput.text.toString().trim()
            if (!u.startsWith("http")) toast("Cole um link que comece com http")
            else { Hub.playLink(u); selectTab(2) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f).apply { marginStart = d(8) })
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

        // painel espelhar
        mirrorPanel = panel()
        mirrorPanel.addView(t("A tela do celular aparece em todas as TVs do grupo, com o som junto (Android 10 ou mais novo pro som). " +
            "Bom pra jogos, fotos, apps que não deixam transmitir e apresentações.", 13f, Neon.soft))
        mirrorButton = Neon.bigButton(this, "Espelhar a tela nas TVs", R.drawable.ic_cast) { toggleMirror() }
        mirrorPanel.addView(mirrorButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(12) })
        mirrorPanel.addView(t("Gire o celular que a TV gira junto. Tem um atraso pequeno (uns 0,3 s) pra todas as TVs ficarem juntas.", 12f, Neon.soft).apply {
            setPadding(0, d(8), 0, 0)
        })
        c.addView(mirrorPanel)

        // painel festa
        partyPanel = panel()
        partyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        partyPanel.addView(partyBox)
        c.addView(partyPanel)

        // continuar assistindo
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(recentBox)

        // TVs
        c.addView(section("Televisões encontradas", Neon.icon(this, R.drawable.ic_refresh, Neon.lilac, 6) {
            toast("Procurando TVs, Chromecast e Smart TVs no Wi-Fi…")
            Cast.refresh()
            refresh()
        }.apply { layoutParams = LinearLayout.LayoutParams(d(36), d(36)) }))
        groupBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        c.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(groupBar)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) })
        tvList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(tvList)
        otherGroups = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(otherGroups)

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
        listOf(0 to linkPanel, 1 to filesPanel, 2 to boxPanel, 3 to mirrorPanel, 4 to partyPanel).forEach { (k2, p) ->
            p.visibility = if (k2 == source) View.VISIBLE else View.GONE
        }
    }

    private fun panelFor(k: Int) = when (k) { 0 -> linkPanel; 1 -> filesPanel; 2 -> boxPanel; 3 -> mirrorPanel; 4 -> partyPanel; else -> null }

    private fun transmitAll() {
        Hub.groupTvs().forEach { if (!it.enabled) it.setOn(true) }
        when {
            Hub.groupTvs().isEmpty() && Cast.devices.none { it.enabled && it.group == Hub.active.group } ->
                toast("Nenhuma TV nesse grupo. Abra o Simulplay na TV, no mesmo Wi-Fi.")
            Hub.current != null || Hub.live -> selectTab(2)
            source == 0 && linkInput.text.startsWith("http") -> { Hub.playLink(linkInput.text.toString().trim()); selectTab(2) }
            source == 1 && Hub.tracks.isNotEmpty() -> { Hub.playTrack(0); selectTab(2) }
            source == 1 -> pickMedia()
            source == 2 -> toggleCaixinha()
            source == 3 -> toggleMirror()
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
        ctrlTitle = t("Transmissão Ativa", 22f, Neon.white, true).apply { gravity = Gravity.CENTER; setPadding(0, d(4), 0, d(2)) }
        ctrlActive.addView(ctrlTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ctrlActive.addView(t("Toque numa TV para tirar ou pôr na transmissão", 13f, Neon.soft).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        orbit = OrbitView(this).apply {
            contentDescription = "Controle da transmissão: toque no meio para tocar ou pausar, arraste o anel para avançar"
            onToggle = { Hub.togglePlay() }
            onTv = { i ->
                val tvs = Hub.groupTvs(); val ext = extInGroup()
                when {
                    i < tvs.size -> tvs[i].let { it.setOn(!it.enabled) }
                    i < tvs.size + ext.size -> ext[i - tvs.size].let { it.setEnabled(!it.enabled) }
                    else -> PhoneSpeaker.setEnabled(!PhoneSpeaker.enabled)
                }
            }
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

        // a seguir (fila)
        queueBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ctrlActive.addView(queueBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        syncCard = t("", 13f, Color.parseColor("#0E7490"), true).apply {
            setOnClickListener { showMicSync() }
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

        // estilo da tela de festa na TV
        c.addView(section("Estilo da festa na TV"))
        val sBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Neon.cardBg(this@MainActivity) }
        val styleRadios = mutableListOf<Neon.Radio>()
        Party.STYLES.forEachIndexed { i, (key, name, sub) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(d(14), d(14), d(14), d(14))
                setOnClickListener { Party.setStyle(key); styleRadios.forEachIndexed { k, r -> r.checked = k == i }; partyKey = "" }
            }
            val r = Neon.Radio(this).apply { checked = Party.style == key }
            styleRadios += r
            row.addView(r, LinearLayout.LayoutParams(d(24), d(24)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), 0, 0, 0) }
            col.addView(t(name, 15f)); col.addView(t(sub, 12f, Neon.soft))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(View(this).apply { background = Party.swatch(key, d(10).toFloat()) }, LinearLayout.LayoutParams(d(56), d(32)))
            sBox.addView(row)
            if (i < Party.STYLES.size - 1) sBox.addView(divider())
        }
        styleRowsRefresh = { styleRadios.forEachIndexed { k, r -> r.checked = Party.STYLES[k].first == Party.style } }
        c.addView(sBox)

        c.addView(section("Transmissão"))
        val prefs = getSharedPreferences("transmiplay", MODE_PRIVATE)
        c.addView(toggleRow("Sincronização Automática", "Mantém todos no mesmo ponto. Com uma TV só fica desligada sozinha (toca liso)", Hub.autoSync) { Hub.setAutoSync(it) })
        c.addView(toggleRow("Abrir o Simulplay quando a TV ligar", "A TV já fica pronta pra receber (algumas TVs não deixam)",
            Hub.bootStart) { Hub.setBootStart(it) })
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
        more.addView(linkRow(R.drawable.ic_wifi, "Sincronizar pelo microfone", null, card = false) { showMicSync() })
        more.addView(divider())
        more.addView(linkRow(R.drawable.ic_speaker, "Caixas de som Bluetooth das TVs", null, card = false) { pickSpeakerTv() })
        more.addView(divider())
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
                    "• Os botões de volume do celular mudam o volume das TVs enquanto transmite. O volume e o nome de cada TV ficam no ícone de caixa de som dela.\n" +
                    "• No controle da TV: OK pausa, ◀ ▶ voltam e avançam 10 s, canal +/− passa pra próxima.\n" +
                    "• Use \"+ Fila\" pra deixar vídeos e links esperando a vez.\n" +
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
        renderGroups()
        tvList.removeAllViews()
        val gTvs = Hub.groupTvs()
        if (gTvs.isEmpty()) {
            tvList.addView(t(if (Hub.tvs.isEmpty()) "Nenhuma TV ainda. Abra o Simulplay na TV, no mesmo Wi-Fi."
                else "Nenhuma TV com Simulplay nesse grupo. Segure o dedo numa TV pra mudar ela de grupo.", 14f, Neon.soft).apply {
                background = Neon.cardBg(this@MainActivity); setPadding(d(14), d(16), d(14), d(16))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(8) })
        }
        tvList.addView(phoneRow(), 0)
        for (tv in gTvs) tvList.addView(tvRow(tv))
        for (dv in extInGroup()) tvList.addView(extRow(dv))
        renderOtherGroups()
        val n = Hub.activeTvs
        selectedText.text = android.text.SpannableStringBuilder().apply {
            append(if (n == 1) "1 televisão selecionada" else "$n televisões selecionadas")
            if (PhoneSpeaker.enabled) append(" + este celular")
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
        boxButtonText.text = if (Hub.caixinha && !Hub.mirroring) "Desligar som nas TVs" else "Ligar som nas TVs"
        boxButton.background = if (Hub.caixinha && !Hub.mirroring) Neon.cardBg(this, Neon.red, 26, 0, 0) else Neon.buttonBg(this)
        (mirrorButton.getChildAt(1) as TextView).text = if (Hub.mirroring) "Parar de espelhar" else "Espelhar a tela nas TVs"
        mirrorButton.background = if (Hub.mirroring) Neon.cardBg(this, Neon.red, 26, 0, 0) else Neon.buttonBg(this)
        renderParty()

        // controle
        val cur = Hub.current
        val active = cur != null || (Hub.live && Hub.liveGroup == Hub.active.group)
        ctrlEmpty.visibility = if (active) View.GONE else View.VISIBLE
        ctrlActive.visibility = if (active) View.VISIBLE else View.GONE
        tvCountText.text = if (n == 1) "1 TV" else "$n TVs"
        orbit.tvs = Hub.groupTvs().map { OrbitView.Tv(it.name, it.enabled) } +
            extInGroup().map { OrbitView.Tv(it.name, it.enabled) } + OrbitView.Tv("Celular", PhoneSpeaker.enabled)
        val liveHere = Hub.live && Hub.liveGroup == Hub.active.group
        orbit.playing = Hub.playing || (liveHere && cur == null)
        orbit.speaker = liveHere && cur == null
        orbit.seekable = cur != null
        ctrlTitle.text = if (Hub.groups.size > 1) "Transmissão · ${Hub.active.group}" else "Transmissão Ativa"
        if (liveHere && cur == null) {
            nowTitle.text = if (Hub.mirroring) "Tela do celular" else "Som do celular"
            thumbUrl = null
            thumb.setImageResource(R.drawable.logo)
            thumbBadge.text = if (Hub.mirroring) "AO VIVO · espelhando a tela" else "AO VIVO · modo caixinha"
            syncCard.text = if (Hub.mirroring) "●  Tela do celular nas TVs" else "●  Som do celular tocando nas TVs"
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
            syncCard.text = when {
                !Hub.autoSync -> "●  Sincronização automática desligada"
                Hub.playersIn(Hub.active.group) < 2 -> "●  Uma TV só: tocando livre, sem correção"
                else -> "●  Sincronizado em todas as TVs"
            }
        }
        val hasList = cur != null && ((Hub.currentIndex >= 0 && Hub.tracks.size > 1) || Hub.queue.isNotEmpty())
        prevBtn.visibility = if (hasList) View.VISIBLE else View.GONE
        nextBtn.visibility = if (hasList) View.VISIBLE else View.GONE
        renderQueue()
        renderRecents()

        if (::versionText.isInitialized) versionText.text = "Você está na versão ${versionName()}"
        Hub.lastError?.let { toast(it); Hub.lastError = null }
        speakerDlg?.let { if (it.isOpen) it.render() }
        // aviso de caixa de som quando a janela está fechada
        Hub.tvs.firstOrNull { it.btMsg.isNotBlank() && it.btMsg != lastBtMsg }?.let {
            lastBtMsg = it.btMsg
            if (speakerDlg?.isOpen != true) toast("${it.name}: ${it.btMsg}")
        }
        updateProgress()
    }

    /** "Este celular": toca junto na caixa Bluetooth ligada no celular */
    private fun phoneRow(): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(this@MainActivity, if (PhoneSpeaker.enabled) Neon.cardHi else Neon.card, 18,
                if (PhoneSpeaker.enabled) Neon.purple else Neon.stroke, 1)
            setPadding(d(10), d(10), d(14), d(10))
        }
        val badge = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#0891B2"))).apply { cornerRadius = d(8).toFloat() }
        }
        badge.addView(Neon.icon(this, R.drawable.ic_speaker, Color.WHITE, 8), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        r.addView(badge, LinearLayout.LayoutParams(d(64), d(42)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, d(6), 0) }
        col.addView(t("Este celular", 16f, Neon.white, true))
        val out = PhoneSpeaker.outputName()
        val sub = when {
            !PhoneSpeaker.enabled -> "Toque junto na caixa ligada no celular"
            PhoneSpeaker.lastNote.isNotBlank() && Hub.current?.kind == "youtube" -> PhoneSpeaker.lastNote
            out != null -> "caixa: $out"
            else -> "alto-falante do celular"
        }
        col.addView(t(sub, 12f, if (PhoneSpeaker.enabled) Color.parseColor("#0E7490") else Neon.soft).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(Neon.icon(this, R.drawable.ic_settings, Neon.purple, 9) {
            try { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
        }.apply { contentDescription = "Bluetooth do celular" }, LinearLayout.LayoutParams(d(42), d(42)).apply { marginEnd = d(6) })
        r.addView(Neon.Toggle(this, PhoneSpeaker.enabled) { PhoneSpeaker.setEnabled(it) }, LinearLayout.LayoutParams(d(46), d(26)))
        r.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(8) }
        return r
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
            if (tv.muted) append(" · sem som") else if (tv.vol < 100) append(" · volume ${tv.vol}%")
            tv.btState?.optJSONArray("connected")?.optJSONObject(0)?.optString("name")?.let { append(" · caixa: $it") }
        }
        col.addView(t(st, 12f, if (tv.enabled) Color.parseColor("#0E7490") else Neon.soft))
        r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // caixa de som Bluetooth dessa TV
        val btName = tv.btState?.optJSONArray("connected")?.optJSONObject(0)?.optString("name")
        r.addView(Neon.icon(this, R.drawable.ic_speaker, if (btName != null) Color.parseColor("#06B6D4") else Neon.purple, 9) {
            openSpeaker(tv)
        }.apply { contentDescription = "Caixa de som da ${tv.name}" }, LinearLayout.LayoutParams(d(42), d(42)).apply { marginEnd = d(6) })
        r.addView(Neon.Check(this, tv.enabled) { tv.setOn(it) }, LinearLayout.LayoutParams(d(30), d(30)))
        r.setOnClickListener { adjustDialog(tv) }
        r.setOnLongClickListener { pickGroup(tv.name, tv.group) { Hub.moveTv(tv, it) }; true }
        r.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(8) }
        return r
    }

    // ================================================================== grupos, Chromecast/Smart TV e festa

    private fun extInGroup(): List<Cast.Device> = Cast.devices.filter { it.group == Hub.active.group }

    private fun chip(label: String, on: Boolean, onTap: () -> Unit) = t(label, 14f, if (on) Color.WHITE else Neon.white, true).apply {
        background = if (on) Neon.buttonBg(this@MainActivity, 18) else Neon.cardBg(this@MainActivity, Color.WHITE, 18, Neon.stroke, 1)
        setPadding(d(16), d(8), d(16), d(8))
        setOnClickListener { onTap() }
    }

    private fun renderGroups() {
        groupBar.removeAllViews()
        val gs = Hub.groups
        for (g in gs) {
            val n = Hub.tvs.count { it.group == g } + Cast.devices.count { it.group == g && it.enabled }
            groupBar.addView(chip("$g · $n", g == Hub.active.group) { Hub.selectGroup(g) }.apply {
                setOnLongClickListener {
                    if (gs.size > 1) AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                        .setTitle("Apagar o grupo \"$g\"?")
                        .setMessage("As TVs dele voltam pro grupo ${gs.first { it != g }}.")
                        .setPositiveButton("Apagar") { _, _ -> Hub.removeGroup(g) }
                        .setNegativeButton("Cancelar", null).show()
                    true
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = d(8) })
        }
        groupBar.addView(chip("+ Grupo", false) { newGroupDialog(null) })
    }

    private fun newGroupDialog(after: ((String) -> Unit)?) {
        val input = EditText(this).apply { hint = "Ex.: Quarto, Varanda"; isSingleLine = true; setTextColor(Neon.white) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Novo grupo de TVs")
            .setMessage("Cada grupo toca uma coisa diferente ao mesmo tempo. Depois segure o dedo numa TV pra pôr ela no grupo.")
            .setView(FrameLayout(this).apply { setPadding(d(20), 0, d(20), 0); addView(input) })
            .setPositiveButton("Criar") { _, _ ->
                val n = input.text.toString().trim()
                if (!Hub.addGroup(n)) toast("Escolha um nome que ainda não existe") else after?.invoke(n)
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun pickGroup(name: String, cur: String, onPick: (String) -> Unit) {
        val gs = Hub.groups
        val labels = (gs.map { if (it == cur) "$it  ✓" else it } + "+ Novo grupo").toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Grupo da $name")
            .setItems(labels) { _, i -> if (i < gs.size) onPick(gs[i]) else newGroupDialog(onPick) }
            .setNegativeButton("Cancelar", null).show()
    }

    /** resumo dos outros grupos (tocar em um troca pra ele) */
    private fun renderOtherGroups() {
        otherGroups.removeAllViews()
        for (g in Hub.groups) {
            if (g == Hub.active.group) continue
            val s = Hub.sessionFor(g)
            val n = Hub.tvs.count { it.group == g }
            val what = when {
                Hub.live && Hub.liveGroup == g -> if (Hub.mirroring) "espelhando a tela" else "som do celular"
                s.current != null -> (if (s.playing) "tocando " else "pausado: ") + s.current!!.title
                else -> "parado"
            }
            otherGroups.addView(miniRow("Grupo $g · $n ${if (n == 1) "TV" else "TVs"}", what, R.drawable.ic_tv, { Hub.selectGroup(g) }, null))
        }
    }

    private fun extRow(dv: Cast.Device): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(this@MainActivity, Neon.card, 18, 0, 0)
            setPadding(d(10), d(10), d(14), d(10))
        }
        val screen = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#94A3B8"), Color.parseColor("#475569"))).apply { cornerRadius = d(8).toFloat() }
        }
        screen.addView(Neon.icon(this, R.drawable.ic_cast, Color.WHITE, 8), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        r.addView(screen, LinearLayout.LayoutParams(d(64), d(42)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0) }
        col.addView(t(dv.name, 16f, Neon.white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(t(dv.label + if (dv.enabled && dv.status.isNotBlank()) " · ${dv.status}" else " · toque pra usar", 12f,
            if (dv.enabled) Color.parseColor("#0E7490") else Neon.soft).apply { maxLines = 2 })
        r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(Neon.Check(this, dv.enabled) { dv.setEnabled(it) }, LinearLayout.LayoutParams(d(30), d(30)))
        r.setOnClickListener { dv.setEnabled(!dv.enabled) }
        r.setOnLongClickListener { pickGroup(dv.name, dv.group) { dv.setGroup(it) }; true }
        r.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(8) }
        return r
    }

    private fun qr(text: String, size: Int): android.graphics.Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.parseColor("#0F172A") else Color.WHITE }
        return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun renderParty() {
        if (!::partyBox.isInitialized) return
        val url = Party.url()
        val key = "${Party.on}|$url|${Party.added}|${Party.group}|${Party.style}"
        if (key == partyKey) return
        partyKey = key
        partyBox.removeAllViews()
        partyBox.addView(t("Os amigos (no mesmo Wi-Fi) leem o QR code e põem músicas e vídeos na fila, pelo celular deles. Não precisam instalar nada.", 13f, Neon.soft),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (Party.on && url != null) {
            partyBox.addView(ImageView(this).apply { setImageBitmap(qr(url, 512)) },
                LinearLayout.LayoutParams(d(220), d(220)).apply { topMargin = d(14) })
            partyBox.addView(t(url, 14f, Neon.purple, true).apply { gravity = Gravity.CENTER; setPadding(0, d(8), 0, 0) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            partyBox.addView(t("Tocando no grupo ${Party.group}" + if (Party.added > 0) " · ${Party.added} pedidos" else "", 12f, Neon.soft).apply {
                gravity = Gravity.CENTER; setPadding(0, d(4), 0, 0)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            partyBox.addView(t("Estilo na TV", 13f, Neon.white, true).apply { setPadding(0, d(12), 0, d(6)) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            Party.STYLES.forEachIndexed { i, (key, name, _) ->
                chips.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(d(4), d(4), d(4), d(6))
                    background = if (Party.style == key) Neon.cardBg(this@MainActivity, Color.WHITE, 14, Neon.purple, 2)
                        else Neon.cardBg(this@MainActivity, Color.WHITE, 14, Neon.stroke, 1)
                    addView(View(this@MainActivity).apply { background = Party.swatch(key, d(10).toFloat()) },
                        LinearLayout.LayoutParams(MATCH_PARENT, d(40)))
                    addView(t(name, 11f, Neon.white, Party.style == key).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, d(4), 0, 0) },
                        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    setOnClickListener { Party.setStyle(key); partyKey = ""; styleRowsRefresh?.invoke(); renderParty() }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = d(6) })
            }
            partyBox.addView(chips, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(smallButton("Copiar link") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("festa", url))
                toast("Link copiado")
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(smallButton("Compartilhar") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Põe sua música na festa: $url"), "Mandar o link"))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = d(8) })
            partyBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(12) })
            partyBox.addView(Neon.bigButton(this, "Encerrar a festa", R.drawable.ic_close) { Party.setOn(false) }.apply {
                background = Neon.cardBg(this@MainActivity, Neon.red, 26, 0, 0)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(12) })
        } else {
            if (url == null) partyBox.addView(t("Conecte no Wi-Fi primeiro.", 13f, Neon.red), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(8) })
            partyBox.addView(Neon.bigButton(this, "Começar a festa", R.drawable.ic_star) { Party.setOn(true) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(12) })
        }
    }

    // ================================================================== fila, recentes e canais

    private fun miniRow(title: String, sub: String, ic: Int, onTap: () -> Unit, onClose: (() -> Unit)?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(this@MainActivity, Neon.card, 14, Neon.stroke, 1)
            setPadding(d(12), d(8), d(4), d(8))
            setOnClickListener { onTap() }
        }
        row.addView(Neon.icon(this, ic, Neon.purple, 4), LinearLayout.LayoutParams(d(28), d(28)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), 0, d(4), 0) }
        col.addView(t(title, 14f, Neon.white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (sub.isNotBlank()) col.addView(t(sub, 12f, Neon.soft).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (onClose != null) row.addView(Neon.icon(this, R.drawable.ic_close, Neon.soft, 8) { onClose() }, LinearLayout.LayoutParams(d(36), d(36)))
        row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(6) }
        return row
    }

    private fun renderQueue() {
        if (!::queueBox.isInitialized) return
        queueBox.removeAllViews()
        val q = Hub.queue.toList()
        if (q.isEmpty()) return
        queueBox.addView(section("A seguir (${q.size})", t("Limpar", 13f, Neon.purple, true).apply {
            setPadding(d(8), d(6), d(4), d(6)); setOnClickListener { Hub.clearQueue() }
        }))
        q.forEachIndexed { i, it ->
            val sub = (if (it.by != null) "pedido por ${it.by} · " else "") +
                when { it.kind == "youtube" -> "YouTube"; it.url.startsWith("file:") -> "Música da festa"; it.mime != null -> it.mime.uppercase(); else -> "Link" }
            queueBox.addView(miniRow("${i + 1}. ${it.title}", sub, R.drawable.ic_play, { Hub.playQueued(i) }, { Hub.removeQueued(i) }))
        }
    }

    private var recentsKey = ""
    private fun renderRecents() {
        if (!::recentBox.isInitialized) return
        val list = Hub.recents().take(5)
        val key = list.joinToString { it.url + it.pos / 10000 }
        if (key == recentsKey) return
        recentsKey = key
        recentBox.removeAllViews()
        if (list.isEmpty()) return
        recentBox.addView(section("Continuar assistindo"))
        for (r in list) {
            val sub = when {
                r.kind == "iptv" -> "Abrir a lista de canais"
                r.pos > 10_000 && (r.dur == 0L || r.pos < r.dur - 20_000) ->
                    (if (r.kind == "youtube") "YouTube · " else "") + "parou em ${fmt(r.pos)}" + (if (r.dur > 0) " de ${fmt(r.dur)}" else "")
                r.kind == "youtube" -> "YouTube"
                else -> "Assistir de novo"
            }
            val row = miniRow(r.title, sub, if (r.kind == "iptv") R.drawable.ic_tv else R.drawable.ic_history,
                { Hub.playRecent(r); if (r.kind != "iptv") selectTab(2) }, { Hub.removeRecent(r.url) })
            row.setOnLongClickListener { Hub.playRecent(r, fromStart = true); selectTab(2); toast("Do começo"); true }
            recentBox.addView(row)
        }
    }

    /** lista de canais (IPTV): procurar, tocar ou pôr na fila */
    private fun showChannels(url: String, list: List<Iptv.Channel>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), d(4), d(16), d(8)) }
        val search = EditText(this).apply {
            hint = "Procurar canal ou grupo"; isSingleLine = true
            setHintTextColor(Neon.soft); setTextColor(Neon.white)
            background = Neon.cardBg(this@MainActivity, Color.parseColor("#F8FAFC"), 12)
            setPadding(d(12), d(10), d(12), d(10))
        }
        box.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(8) })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(rows) }
        box.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55).toInt()))
        var dlg: AlertDialog? = null
        fun render(q: String) {
            rows.removeAllViews()
            val f = list.filter { q.isBlank() || it.name.contains(q, true) || (it.group?.contains(q, true) == true) }
            for (ch in f.take(300)) {
                val row = miniRow(ch.name, ch.group ?: "", R.drawable.ic_tv, {
                    Hub.playItem(Iptv.toItem(ch)); dlg?.dismiss(); selectTab(2)
                }, null)
                (row as LinearLayout).addView(t("+ Fila", 13f, Neon.purple, true).apply {
                    setPadding(d(10), d(8), d(10), d(8))
                    setOnClickListener { Hub.enqueue(Iptv.toItem(ch)); toast("Na fila: ${ch.name}") }
                })
                rows.addView(row)
            }
            if (f.size > 300) rows.addView(t("Mostrando 300 de ${f.size}. Procure pelo nome pra achar os outros.", 12f, Neon.soft))
            if (f.isEmpty()) rows.addView(t("Nenhum canal com esse nome.", 13f, Neon.soft))
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { render(e?.toString() ?: "") }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        render("")
        dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Canais (${list.size})")
            .setView(box)
            .setNegativeButton("Fechar", null)
            .show()
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

    // ================================================================== caixa de som Bluetooth

    private fun openSpeaker(tv: Hub.TvConn) {
        speakerDlg = SpeakerDialog(this, tv).also { it.show() }
    }

    private fun pickSpeakerTv() {
        val list = Hub.tvs.toList()
        when (list.size) {
            0 -> toast("Nenhuma TV conectada. Abra o Simulplay na TV.")
            1 -> openSpeaker(list[0])
            else -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Qual TV?")
                .setItems(list.map { it.name }.toTypedArray()) { _, i -> openSpeaker(list[i]) }
                .setNegativeButton("Cancelar", null).show()
        }
    }

    // ================================================================== sincronizar pelo microfone

    private var micBody: LinearLayout? = null
    private var micStatus = ""
    private val micResult = HashMap<String, String>()

    private fun showMicSync() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (Hub.tvs.isEmpty()) { toast("Nenhuma TV conectada. Abra o Simulplay nas TVs."); return }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(6), d(20), d(10)) }
        micBody = body
        renderMic()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Sincronizar pelo microfone")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Fechar", null)
            .setOnDismissListener { micBody = null }
            .show()
    }

    private fun renderMic() {
        val body = micBody ?: return
        body.removeAllViews()
        fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(top) }
        body.addView(t("O celular escuta as TVs e acerta o atraso de cada uma sozinho. Cada TV apita num tom diferente.", 14f, Neon.soft))
        body.addView(t("Antes: aumente o volume das TVs e deixe o ambiente quieto.", 13f, Neon.soft), lp(8))

        body.addView(t("Todas de uma vez", 15f, Neon.white, true), lp(16))
        body.addView(t("Fique no meio, mais ou menos na mesma distância de cada TV ou caixa, com o celular parado.", 13f, Neon.soft), lp(2))
        body.addView(Neon.bigButton(this, if (MicSync.running) "Escutando…" else "Sincronizar todas", R.drawable.ic_wifi) {
            if (!MicSync.running) startMic(syncables())
        }, lp(10))

        body.addView(t("Uma por vez", 15f, Neon.white, true), lp(18))
        body.addView(t("Chegue pertinho de uma TV (ou da caixa dela) e toque em Medir. Depois vá na próxima.", 13f, Neon.soft), lp(2))
        for (tv in syncables()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = Neon.cardBg(this@MainActivity, Color.WHITE, 14, Neon.stroke, 1)
                setPadding(d(12), d(10), d(8), d(10))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(t(tv.name, 15f, Neon.white, true))
            col.addView(t(micResult[tv.name] ?: "ajuste atual: ${tv.adj} ms", 12f, Neon.soft))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(t("Medir", 13f, Color.WHITE, true).apply {
                background = Neon.buttonBg(this@MainActivity, 16); setPadding(d(14), d(8), d(14), d(8))
                setOnClickListener { if (!MicSync.running) startMic(listOf(tv)) }
            })
            body.addView(row, lp(8))
        }
        if (micStatus.isNotBlank()) {
            body.addView(t(micStatus, 14f, Color.parseColor("#1E40AF"), true).apply {
                background = Neon.cardBg(this@MainActivity, Color.parseColor("#EFF6FF"), 12, Color.parseColor("#BFDBFE"), 1)
                setPadding(d(12), d(10), d(12), d(10))
            }, lp(14))
        }
        body.addView(t("Zerar medições", 13f, Neon.purple, true).apply {
            setPadding(0, d(14), 0, d(4))
            setOnClickListener { MicSync.forget(); micResult.clear(); micStatus = "Medições zeradas."; renderMic() }
        })
    }

    /** TVs + o celular (quando ele está tocando junto) */
    private fun syncables(): List<Hub.Syncable> =
        Hub.groupTvs().toList<Hub.Syncable>() + listOfNotNull(PhoneSpeaker.takeIf { it.enabled })

    private fun startMic(list: List<Hub.Syncable>) {
        if (Hub.playing) Hub.togglePlay() // pausa pra música não atrapalhar
        micStatus = "Prepare-se… as TVs vão apitar"
        renderMic()
        val before = list.associateWith { it.adj }
        MicSync.run(list, progress = { micStatus = it; renderMic() }) { res ->
            var heard = 0
            for ((tv, e) in res) {
                if (e == null) { micResult[tv.name] = "não ouvi o apito (aumente o volume ou chegue mais perto)"; continue }
                heard++
                val diff = tv.adj - (before[tv] ?: tv.adj)
                micResult[tv.name] = when {
                    MicSync.measured.size < 2 -> "medida ok · agora meça outra TV pra comparar"
                    diff == 0 -> "já estava certinha ✓"
                    diff < 0 -> "estava atrasada ${-diff} ms → corrigido ✓"
                    else -> "estava adiantada $diff ms → corrigido ✓"
                }
            }
            // as outras TVs também podem ter sido corrigidas (alinhadas com a nova)
            for (tv in syncables()) if (tv !in res.keys && MicSync.measured.containsKey(tv.name)) micResult[tv.name] = "ajuste: ${tv.adj} ms"
            micStatus = when {
                heard == 0 -> "Não ouvi nenhum apito. Aumente o volume das TVs e tente de novo."
                MicSync.measured.size < 2 -> "Pronto! Agora meça outra TV."
                else -> "Pronto! As TVs foram alinhadas."
            }
            renderMic()
            refresh()
        }
    }

    // ================================================================== atraso das TVs

    private fun adjustDialog(only: Hub.Syncable? = null) {
        val list = only?.let { listOf(it) } ?: syncables()
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

    private fun toggleMirror() {
        if (Hub.mirroring) { CaptureService.stop(this); return }
        if (Hub.caixinha) CaptureService.stop(this)
        wantMirror = true
        // o som junto precisa da permissão de áudio (Android 10+); sem ela espelha só a imagem
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun toggleCaixinha() {
        wantMirror = false
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
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) showMicSync()
            else toast("Sem o microfone não dá pra escutar as TVs")
        }
        if (requestCode == REQ_AUDIO) {
            val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (wantMirror) {
                if (!ok) toast("Sem a permissão de áudio a tela vai sem som")
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
            } else if (ok) toggleCaixinha()
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
            if (resultCode == RESULT_OK && data != null && (wantMirror || Build.VERSION.SDK_INT >= 29)) {
                Hub.lastError = null
                CaptureService.start(this, resultCode, data, mirror = wantMirror)
                selectTab(2)
            } else toast(if (wantMirror) "Precisa da sua permissão pra espelhar a tela" else "Precisa da sua permissão pra pegar o som")
        }
    }

    companion object {
        const val REQ_PICK = 1
        const val REQ_AUDIO = 2
        const val REQ_PROJECTION = 3
        const val REQ_MIC = 4
        const val EXTRA_MODE = "mode"
        const val EXTRA_CONTROL = "control"
    }
}
