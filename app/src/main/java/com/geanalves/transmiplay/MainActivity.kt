package com.geanalves.transmiplay

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val bg = Color.parseColor("#0E1320")
    private val card = Color.parseColor("#1A2133")
    private val blue = Color.parseColor("#2F80FF")
    private val soft = Color.parseColor("#9AA6C2")

    private val ui = Handler(Looper.getMainLooper())
    private var mode = 0 // 0 link, 1 músicas, 2 caixinha

    private lateinit var tvBox: LinearLayout
    private lateinit var modeButtons: List<Button>
    private lateinit var panels: List<View>
    private lateinit var linkInput: EditText
    private lateinit var trackBox: LinearLayout
    private lateinit var boxButton: Button
    private lateinit var boxInfo: TextView
    private lateinit var playerCard: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var playBtn: Button
    private lateinit var errorLabel: TextView
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        TransmitService.start(this)
        Hub.start(applicationContext)
        mode = getPreferences(MODE_PRIVATE).getInt("mode", 0)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Hub.listener = { refresh() }
        refresh()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        Hub.listener = null
        ui.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ compartilhar de outros apps

    private fun handleShare(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val type = i.type ?: ""
                if (type.startsWith("text")) {
                    val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    val url = Regex("""https?://\S+""").find(text)?.value ?: return
                    selectMode(0)
                    linkInput.setText(url)
                    Hub.playLink(url)
                } else {
                    @Suppress("DEPRECATION")
                    val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                    selectMode(1)
                    Hub.addTracks(listOf(u))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val list = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                selectMode(1)
                Hub.addTracks(list)
            }
        }
        i.action = null
    }

    // ------------------------------------------------------------------ montagem da tela

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun text(s: String, size: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, color: Int = blue, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = rounded(color, 14)
        stateListAnimator = null
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(card, 20)
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }

    private fun LinearLayout.addGap(h: Int) = addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, dp(h)))

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        root.addView(text("Transmiplay", 30f, Color.WHITE, true))
        root.addView(text("Manda o som e o vídeo pras TVs com o Simulplay", 14f, soft))
        root.addGap(16)

        // TVs
        val tvCard = cardBox()
        tvCard.addView(text("TVs conectadas", 17f, Color.WHITE, true))
        tvCard.addGap(6)
        tvBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvCard.addView(tvBox)
        root.addView(tvCard)
        root.addGap(14)

        // modos
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("Link", "Minhas músicas", "Caixinha")
        modeButtons = names.mapIndexed { i, n ->
            button(n, card) { selectMode(i) }.also {
                it.textSize = 14f
                modeRow.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                })
            }
        }
        root.addView(modeRow)
        root.addGap(10)

        // painel link
        val linkPanel = cardBox()
        linkPanel.addView(text("Cole um link do YouTube ou de um áudio/vídeo (mp3, mp4...)", 14f, soft))
        linkPanel.addGap(8)
        linkInput = EditText(this).apply {
            hint = "https://..."
            setHintTextColor(soft)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            background = rounded(Color.parseColor("#0E1320"), 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        linkPanel.addView(linkInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        linkPanel.addGap(10)
        val linkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        linkRow.addView(button("Colar", Color.parseColor("#2A3450")) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.let { linkInput.setText(it) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        linkRow.addView(button("Transmitir") {
            val u = linkInput.text.toString().trim()
            if (!u.startsWith("http")) toast("Cole um link que comece com http")
            else Hub.playLink(u)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f).apply { marginStart = dp(8) })
        linkPanel.addView(linkRow)
        linkPanel.addGap(8)
        linkPanel.addView(text("Dica: no YouTube, toque em Compartilhar → Transmiplay.", 13f, soft))

        // painel músicas
        val musicPanel = cardBox()
        musicPanel.addView(button("+ Escolher músicas do celular") { pickMusic() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        musicPanel.addGap(10)
        trackBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        musicPanel.addView(trackBox)

        // painel caixinha
        val boxPanel = cardBox()
        boxPanel.addView(text("Modo caixinha", 17f, Color.WHITE, true))
        boxPanel.addGap(4)
        boxInfo = text(
            "Liga e toca o que quiser no celular (qualquer app ou site): o som sai nas TVs, " +
                "como uma caixinha Bluetooth. Alguns apps bloqueiam a captura do som.", 14f, soft,
        )
        boxPanel.addView(boxInfo)
        boxPanel.addGap(12)
        boxButton = button("Ligar caixinha") { toggleCaixinha() }
        boxPanel.addView(boxButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        panels = listOf(linkPanel, musicPanel, boxPanel)
        panels.forEach { root.addView(it) }
        root.addGap(14)

        // player
        playerCard = cardBox()
        playerCard.addView(text("Tocando nas TVs", 13f, soft))
        nowTitle = text("", 17f, Color.WHITE, true).apply { maxLines = 2 }
        playerCard.addView(nowTitle)
        playerCard.addGap(6)
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) timeLabel.text = "${fmt(p.toLong() * 1000)} / ${fmt(Hub.current?.durMs ?: 0)}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    dragging = false
                    Hub.seek((s?.progress ?: 0) * 1000L)
                }
            })
        }
        playerCard.addView(seek)
        timeLabel = text("0:00", 13f, soft)
        playerCard.addView(timeLabel)
        playerCard.addGap(8)
        val ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) } }
        ctrl.addView(button("⏮", Color.parseColor("#2A3450")) { Hub.prev() }, lp())
        playBtn = button("⏸") { Hub.togglePlay() }
        ctrl.addView(playBtn, lp())
        ctrl.addView(button("⏭", Color.parseColor("#2A3450")) { Hub.next() }, lp())
        ctrl.addView(button("■", Color.parseColor("#2A3450")) { Hub.stopAll() }, lp())
        playerCard.addView(ctrl)
        root.addView(playerCard)

        root.addGap(10)
        errorLabel = text("", 13f, Color.parseColor("#FF8A80"))
        root.addView(errorLabel)

        root.addGap(10)
        root.addView(text(
            "Como usar: abra o Simulplay em cada TV, no mesmo Wi-Fi do celular. " +
                "Elas aparecem aqui sozinhas. Se uma TV ficar adiantada ou atrasada, " +
                "use o − e o + do lado do nome dela.", 13f, soft,
        ))

        selectMode(mode)
        return ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(root)
        }
    }

    private fun selectMode(m: Int) {
        mode = m
        getPreferences(MODE_PRIVATE).edit().putInt("mode", m).apply()
        modeButtons.forEachIndexed { i, b -> b.background = rounded(if (i == m) blue else card, 14) }
        panels.forEachIndexed { i, p -> p.visibility = if (i == m) View.VISIBLE else View.GONE }
        refresh()
    }

    // ------------------------------------------------------------------ atualizar tela

    private fun refresh() {
        if (!::tvBox.isInitialized) return
        tvBox.removeAllViews()
        if (Hub.tvs.isEmpty()) {
            tvBox.addView(text("Nenhuma TV ainda. Abra o Simulplay na TV (mesmo Wi-Fi).", 14f, soft))
        } else for (tv in Hub.tvs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(text("📺  ${tv.name}", 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val small = { LinearLayout.LayoutParams(dp(44), dp(40)) }
            row.addView(button("−", Color.parseColor("#2A3450")) { tv.changeAdj(-20) }.apply { setPadding(0, 0, 0, 0) }, small())
            val label = if (tv.adj == 0) "0 ms" else (if (tv.adj > 0) "+" else "") + "${tv.adj} ms"
            row.addView(text(label, 13f, soft).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(70), WRAP_CONTENT))
            row.addView(button("+", Color.parseColor("#2A3450")) { tv.changeAdj(20) }.apply { setPadding(0, 0, 0, 0) }, small())
            tvBox.addView(row)
        }

        trackBox.removeAllViews()
        if (Hub.tracks.isEmpty()) trackBox.addView(text("Nenhuma música ainda.", 14f, soft))
        Hub.tracks.forEachIndexed { i, t ->
            val isCur = i == Hub.currentIndex && Hub.current != null
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (isCur) Color.parseColor("#22355E") else Color.parseColor("#141A2A"), 12)
                setPadding(dp(12), dp(8), dp(6), dp(8))
                setOnClickListener { Hub.playTrack(i) }
            }
            row.addView(text((if (isCur) "▶  " else "♪  ") + t.title, 15f).apply { maxLines = 1 },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(text("✕", 16f, soft).apply {
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { Hub.removeTrack(i) }
            })
            trackBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }

        val on = Hub.caixinha
        boxButton.text = if (on) "Desligar caixinha" else "Ligar caixinha"
        boxButton.background = rounded(if (on) Color.parseColor("#D64545") else blue, 14)

        val cur = Hub.current
        playerCard.visibility = if (cur != null) View.VISIBLE else View.GONE
        if (cur != null) {
            nowTitle.text = cur.title
            playBtn.text = if (Hub.playing) "⏸" else "▶"
        }
        errorLabel.text = Hub.lastError ?: ""
        updateProgress()
    }

    private fun updateProgress() {
        if (!::seek.isInitialized) return
        val cur = Hub.current ?: return
        val pos = Hub.position()
        val dur = cur.durMs
        seek.max = (dur / 1000).toInt().coerceAtLeast(1)
        if (!dragging) {
            seek.progress = (pos / 1000).toInt()
            timeLabel.text = if (dur > 0) "${fmt(pos)} / ${fmt(dur)}" else fmt(pos)
        }
        seek.isEnabled = dur > 0
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ escolher músicas

    private fun pickMusic() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(i, REQ_PICK) } catch (_: Exception) { toast("Não achei o seletor de arquivos") }
    }

    // ------------------------------------------------------------------ caixinha

    private fun toggleCaixinha() {
        if (Hub.caixinha) { CaptureService.stop(this); return }
        if (Build.VERSION.SDK_INT < 29) { toast("O modo caixinha precisa de Android 10 ou mais novo"); return }
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
            else toast("Sem a permissão de áudio a caixinha não consegue pegar o som")
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
            } else toast("A caixinha precisa da sua permissão pra pegar o som")
        }
    }

    companion object {
        const val REQ_PICK = 1
        const val REQ_AUDIO = 2
        const val REQ_PROJECTION = 3
    }
}
