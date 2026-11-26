package com.geanalves.transmiplay

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/** Janela "Caixa de som" de uma TV: mostra onde a TV toca e deixa escolher a caixa Bluetooth. */
class SpeakerDialog(private val act: Activity, private val tv: Hub.TvConn) {
    private fun d(v: Int) = Neon.dp(act, v)
    private fun t(s: CharSequence, size: Float = 15f, color: Int = Neon.white, bold: Boolean = false) =
        Neon.text(act, s, size, color, bold)

    private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(6), d(20), d(10)) }
    private var dialog: AlertDialog? = null
    var isOpen = false; private set

    fun show() {
        tv.btMsg = ""
        tv.bt("state")
        render()
        dialog = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Caixa de som · ${tv.name}")
            .setView(ScrollView(act).apply {
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(volumeHeader())
                    addView(body)
                })
            })
            .setPositiveButton("Fechar", null)
            .setOnDismissListener { isOpen = false }
            .create()
        isOpen = true
        dialog?.show()
    }

    /** volume e nome da TV (fica fora do render pra não pular enquanto arrasta) */
    private fun volumeHeader(): View {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(10), d(20), d(4)) }
        val label = t("", 14f, Neon.white, true)
        fun paint() { label.text = if (tv.muted) "Volume da TV: sem som" else "Volume da TV: ${tv.vol}%" }
        paint()
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mute = button(if (tv.muted) "Ligar som" else "Mudo") {}
        val bar = android.widget.SeekBar(act).apply {
            max = 100; progress = tv.vol
            progressTintList = android.content.res.ColorStateList.valueOf(Neon.purple)
            thumbTintList = android.content.res.ColorStateList.valueOf(Neon.purple)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) { tv.setVol(p, false); paint(); mute.text = "Mudo" }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        mute.setOnClickListener { tv.setVol(tv.vol, !tv.muted); paint(); mute.text = if (tv.muted) "Ligar som" else "Mudo" }
        row.addView(bar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(mute)
        box.addView(label)
        box.addView(row, lp(top = 6))
        box.addView(t("Renomear esta TV", 13f, Neon.purple, true).apply {
            setPadding(0, d(10), 0, d(4))
            setOnClickListener { renameDialog() }
        })
        box.addView(View(act).apply { setBackgroundColor(Neon.stroke) }, LinearLayout.LayoutParams(MATCH_PARENT, d(1)).apply { topMargin = d(8) })
        return box
    }

    private fun renameDialog() {
        val input = android.widget.EditText(act).apply {
            setText(tv.name); setSelection(text.length); isSingleLine = true
            setTextColor(Neon.white)
        }
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Nome da TV")
            .setMessage("Ex.: TV da sala, TV do quarto")
            .setView(android.widget.FrameLayout(act).apply { setPadding(d(20), 0, d(20), 0); addView(input) })
            .setPositiveButton("Salvar") { _, _ ->
                tv.rename(input.text.toString())
                dialog?.setTitle("Caixa de som · ${tv.name}")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun render() {
        body.removeAllViews()
        val st = tv.btState

        // aviso que veio da TV
        if (tv.btMsg.isNotBlank()) {
            body.addView(t(tv.btMsg, 14f, Color.parseColor("#1E40AF")).apply {
                background = Neon.cardBg(act, Color.parseColor("#EFF6FF"), 12, Color.parseColor("#BFDBFE"), 1)
                setPadding(d(12), d(10), d(12), d(10))
            }, lp(bottom = 12))
        }

        when {
            st == null -> { body.addView(t("Perguntando pra TV…", 14f, Neon.soft)); return }
            !st.optBoolean("has", true) -> { body.addView(t("Essa TV não tem Bluetooth.", 15f)); return }
            !st.optBoolean("perm", true) -> {
                // só TVs com Android 12 ou mais chegam aqui
                body.addView(t("Essa TV (Android 12 ou mais novo) só deixa ver as caixas com permissão.", 15f))
                body.addView(button("Abrir permissões do Simulplay na TV", primary = true) { tv.bt("appsettings") }, lp(top = 12))
                body.addView(t("Na TV: Permissões → Dispositivos por perto → Permitir. Depois volte aqui.", 13f, Neon.soft), lp(top = 8))
                body.addView(button("Ou parear pela tela de Bluetooth da TV") { tv.bt("settings") }, lp(top = 12))
                return
            }
        }
        st!!
        val connected = st.optJSONArray("connected") ?: JSONArray()
        val paired = st.optJSONArray("paired") ?: JSONArray()
        val found = st.optJSONArray("found") ?: JSONArray()
        val pending = st.optString("pending")
        val connectedAddr = (0 until connected.length()).map { connected.getJSONObject(it).optString("addr") }.toSet()

        // onde está tocando agora
        val now = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(act, Color.WHITE, 16, Neon.stroke, 1)
            setPadding(d(12), d(12), d(12), d(12))
        }
        now.addView(Neon.icon(act, R.drawable.ic_speaker, Color.WHITE, 8).apply {
            background = Neon.circle(Neon.purple)
        }, LinearLayout.LayoutParams(d(40), d(40)))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, 0, 0) }
        col.addView(t("Tocando agora em", 12f, Neon.soft))
        val name = if (connected.length() > 0) connected.getJSONObject(0).optString("name") else "Alto-falante da TV"
        col.addView(t(name, 16f, Neon.white, true))
        now.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (connected.length() > 0) {
            val addr = connected.getJSONObject(0).optString("addr")
            now.addView(button("Desconectar") { tv.bt("disconnect", addr) })
        }
        body.addView(now, lp(bottom = 16))

        if (!st.optBoolean("on", true)) {
            body.addView(t("O Bluetooth da TV está desligado.", 14f, Neon.soft))
            body.addView(button("Ligar e procurar caixas", primary = true) { tv.bt("scan") }, lp(top = 10))
            return
        }

        // pareadas
        body.addView(t("Caixas já pareadas nessa TV", 14f, Neon.white, true), lp(bottom = 6))
        if (paired.length() == 0) body.addView(t("Nenhuma ainda.", 13f, Neon.soft), lp(bottom = 4))
        for (i in 0 until paired.length()) {
            val o = paired.getJSONObject(i)
            val a = o.optString("addr")
            body.addView(deviceRow(o.optString("name"), when {
                a in connectedAddr -> "conectada"
                a == pending -> "esperando"
                else -> "conectar"
            }) { tv.bt("connect", a) }, lp(bottom = 6))
        }

        // por perto
        val scanning = st.optBoolean("scanning")
        body.addView(t("Caixas por perto", 14f, Neon.white, true), lp(top = 12, bottom = 6))
        for (i in 0 until found.length()) {
            val o = found.getJSONObject(i)
            val a = o.optString("addr")
            body.addView(deviceRow(o.optString("name"), if (a == pending) "esperando" else "parear") { tv.bt("connect", a) }, lp(bottom = 6))
        }
        if (found.length() == 0 && !scanning) body.addView(t("Toque em Procurar com a caixa ligada e piscando (modo de pareamento). Echo Dot: fale \"Alexa, emparelhar\".", 13f, Neon.soft), lp(bottom = 4))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(if (scanning) "Procurando…" else "Procurar caixas", primary = !scanning) {
            if (!scanning) tv.bt("scan")
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(button("Parear pela TV") { tv.bt("settings") },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = d(8) })
        body.addView(row, lp(top = 8))
        if (!st.optBoolean("scanPerm", true)) {
            body.addView(t("Se a busca não achar nada, a TV não deixou o app procurar. Use \"Parear pela TV\": escolha a caixa com o controle e ela aparece aqui em cima, pronta pra conectar.", 12f, Neon.soft), lp(top = 8))
            body.addView(t("Dar permissão pra procurar", 13f, Neon.purple, true).apply {
                setPadding(0, d(8), 0, 0); setOnClickListener { tv.bt("ask") }
            })
        }

        body.addView(t("Se a TV não deixar o app conectar sozinho, ela abre a tela de Bluetooth: aí é só escolher a caixa com o controle.", 12f, Neon.soft), lp(top = 14))

    }

    private fun deviceRow(name: String, state: String, onTap: () -> Unit): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Neon.cardBg(act, if (state == "conectada") Color.parseColor("#ECFEFF") else Color.WHITE, 14,
                if (state == "conectada") Color.parseColor("#A5F3FC") else Neon.stroke, 1)
            setPadding(d(12), d(8), d(8), d(8))
        }
        r.addView(Neon.icon(act, R.drawable.ic_speaker, Neon.purple, 4), LinearLayout.LayoutParams(d(28), d(28)))
        r.addView(t(name, 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(d(10), 0, d(8), 0) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        when (state) {
            "conectada" -> r.addView(t("✓ Conectada", 13f, Color.parseColor("#0E7490"), true).apply { setPadding(d(8), d(8), d(8), d(8)) })
            "esperando" -> r.addView(t("Conectando…", 13f, Neon.soft, true).apply { setPadding(d(8), d(8), d(8), d(8)) })
            "parear" -> r.addView(button("Parear e conectar", primary = true, onClick = onTap))
            else -> r.addView(button("Conectar", primary = true, onClick = onTap))
        }
        return r
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        t(label, 13f, if (primary) Color.WHITE else Neon.white, true).apply {
            gravity = Gravity.CENTER
            background = if (primary) Neon.buttonBg(act, 18) else Neon.cardBg(act, Color.WHITE, 18, Neon.stroke, 1)
            setPadding(d(14), d(9), d(14), d(9))
            setOnClickListener { onClick() }
        }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(top); bottomMargin = d(bottom) }
}
