package com.geanalves.transmiplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Cores do tema "Gelo azul": branco gelado, azul e o verde-água do logo. */
object Neon {
    // fundo: branco gelado em degradê
    val bgTop = Color.parseColor("#F7FAFE")
    val bgMid = Color.parseColor("#F1F6FD")
    val bgBottom = Color.parseColor("#E6EFFB")
    // cartões brancos com borda bem clarinha
    val card = Color.WHITE
    val cardHi = Color.parseColor("#EFF6FF")
    val stroke = Color.parseColor("#DCE6F5")
    // os nomes antigos continuam, agora com as cores novas
    val purple = Color.parseColor("#2563EB")     // azul principal
    val purple2 = Color.parseColor("#3B82F6")    // azul claro
    val lilac = Color.parseColor("#2563EB")      // textos de destaque
    val blue = Color.parseColor("#06B6D4")       // verde-água
    val green = Color.parseColor("#16A34A")
    val greenDark = Color.parseColor("#ECFDF3")
    val red = Color.parseColor("#DC2626")
    val soft = Color.parseColor("#64748B")       // texto secundário
    val white = Color.parseColor("#0F172A")      // texto principal (escuro)
    val onAccent = Color.WHITE                   // texto em cima do azul
    val track = Color.parseColor("#DBEAFE")

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun background() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(bgTop, bgMid, bgBottom))

    fun cardBg(ctx: Context, color: Int = card, radius: Int = 16, strokeColor: Int = stroke, strokeW: Int = 1) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radius).toFloat()
            if (strokeW > 0) setStroke(dp(ctx, strokeW), strokeColor)
        }

    fun buttonBg(ctx: Context, radius: Int = 26) = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(purple, purple2),
    ).apply { cornerRadius = dp(ctx, radius).toFloat() }

    fun circle(color: Int, strokeColor: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); if (strokeW > 0) setStroke(strokeW, strokeColor)
    }

    fun text(ctx: Context, s: CharSequence, size: Float = 15f, color: Int = white, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    fun icon(ctx: Context, res: Int, tint: Int = white, pad: Int = 8, onClick: (() -> Unit)? = null) =
        ImageView(ctx).apply {
            setImageResource(res); setColorFilter(tint)
            setPadding(dp(ctx, pad), dp(ctx, pad), dp(ctx, pad), dp(ctx, pad))
            if (onClick != null) {
                setOnClickListener { onClick() }
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
        }

    /** botão roxo grande com ícone */
    fun bigButton(ctx: Context, label: String, iconRes: Int?, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = buttonBg(ctx)
            setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 14))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            if (iconRes != null) addView(icon(ctx, iconRes, onAccent, 0), LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
            addView(text(ctx, label, 17f, onAccent, true).apply { setPadding(dp(ctx, 10), 0, 0, 0) })
        }

    /** "Transmi" branco + "play" roxo */
    fun brand(ctx: Context, first: String, second: String, size: Float): TextView {
        val sp = android.text.SpannableStringBuilder(first + second)
        sp.setSpan(android.text.style.ForegroundColorSpan(lilac), first.length, first.length + second.length, 0)
        return text(ctx, sp, size, white, true)
    }

    /** chave liga/desliga redondinha */
    class Toggle(ctx: Context, initial: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
        var on = initial
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { setOnClickListener { on = !on; onChange(on) }; isFocusable = true }
        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val w = width.toFloat()
            p.color = if (on) purple else Color.parseColor("#CBD5E1")
            c.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
            p.color = Color.WHITE
            val r = h / 2 - h * 0.12f
            c.drawCircle(if (on) w - h / 2 else h / 2, h / 2, r, p)
        }
    }

    /** bolinha de rádio */
    class Radio(ctx: Context) : View(ctx) {
        var checked = false
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f
            p.color = if (checked) purple2 else soft
            c.drawCircle(r, r, r - p.strokeWidth, p)
            if (checked) { p.style = Paint.Style.FILL; c.drawCircle(r, r, r * 0.45f, p) }
        }
    }

    /** caixinha de marcar roxa */
    class Check(ctx: Context, initial: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
        var checked = initial
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { setOnClickListener { checked = !checked; onChange(checked) } }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val rr = RectF(w * 0.06f, w * 0.06f, w * 0.94f, w * 0.94f)
            if (checked) {
                p.style = Paint.Style.FILL; p.color = purple
                c.drawRoundRect(rr, w * 0.18f, w * 0.18f, p)
                p.style = Paint.Style.STROKE; p.color = Color.WHITE; p.strokeWidth = w * 0.1f
                p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                val path = android.graphics.Path().apply {
                    moveTo(w * 0.27f, w * 0.52f); lineTo(w * 0.43f, w * 0.68f); lineTo(w * 0.74f, w * 0.34f)
                }
                c.drawPath(path, p)
            } else {
                p.style = Paint.Style.STROKE; p.color = soft; p.strokeWidth = w * 0.08f
                c.drawRoundRect(rr, w * 0.18f, w * 0.18f, p)
            }
        }
    }

    /** anel de progresso da "Transmissão Ativa" */
    class Ring(ctx: Context) : View(ctx) {
        var progress = 0f
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(c: Canvas) {
            val sw = width * 0.06f
            val r = RectF(sw, sw, width - sw, height - sw)
            p.strokeWidth = sw
            p.color = Color.parseColor("#2A2463")
            c.drawArc(r, 0f, 360f, false, p)
            p.shader = android.graphics.SweepGradient(width / 2f, height / 2f, intArrayOf(purple2, Color.parseColor("#E879F9"), purple2), null)
            c.drawArc(r, -90f, 360f * progress.coerceIn(0f, 1f), false, p)
            p.shader = null
        }
    }
}
