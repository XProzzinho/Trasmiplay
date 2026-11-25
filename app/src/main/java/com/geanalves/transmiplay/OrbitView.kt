package com.geanalves.transmiplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Controle "TVs em órbita": anel de progresso (arrastável), botão tocar/pausar no meio
 * e as TVs em volta — tocar numa TV tira ou põe ela na transmissão.
 */
class OrbitView(ctx: Context) : View(ctx) {
    data class Tv(val name: String, val on: Boolean)

    var progress = 0f
        set(v) { if (!dragging) { field = v.coerceIn(0f, 1f); invalidate() } }
    var playing = false
        set(v) { field = v; invalidate(); if (v) postInvalidateOnAnimation() }
    var speaker = false
        set(v) { field = v; invalidate() }
    var timeText = ""
        set(v) { field = v; invalidate() }
    var seekable = true
    var tvs: List<Tv> = emptyList()
        set(v) { field = v; invalidate() }

    var onToggle: (() -> Unit)? = null
    var onTv: ((Int) -> Unit)? = null
    var onSeek: ((Float) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val tvIcon = context.getDrawable(R.drawable.ic_tv)!!.mutate()
    private val speakerIcon = context.getDrawable(R.drawable.ic_speaker)!!.mutate()
    private val start = System.nanoTime()
    private val badgeRects = mutableListOf<RectF>()
    private var dragging = false
    private var downTarget = NONE
    private var dragP = 0f

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val s get() = min(width, height).toFloat()
    private val ringR get() = s * 0.25f
    // as TVs ficam na órbita sem cortar nas bordas
    private val orbitR get() = min(s * 0.39f, min(width, height) / 2f - 46 * dp)
    private val centerR get() = ringR - 14 * dp

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else width
        setMeasuredDimension(width, height)
    }

    override fun onDraw(c: Canvas) {
        val t = (System.nanoTime() - start) / 1e9f
        val prog = if (dragging) dragP else progress

        // pulso saindo do anel enquanto toca
        if (playing) {
            val ph = (t % 1.6f) / 1.6f
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * dp; p.shader = null
            p.color = Color.argb((150 * (1 - ph)).toInt(), 37, 99, 235)
            c.drawCircle(cx, cy, ringR * (1 + 0.32f * ph), p)
        }

        // órbita pontilhada
        p.style = Paint.Style.STROKE; p.strokeWidth = 1 * dp
        p.color = Color.parseColor("#BFDBFE")
        p.pathEffect = DashPathEffect(floatArrayOf(3 * dp, 7 * dp), 0f)
        c.drawCircle(cx, cy, orbitR, p)
        p.pathEffect = null

        // anel de progresso
        val box = RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        p.strokeWidth = 10 * dp; p.strokeCap = Paint.Cap.ROUND
        p.color = Neon.track
        c.drawCircle(cx, cy, ringR, p)
        if (seekable && prog > 0f) {
            p.shader = SweepGradient(cx, cy, intArrayOf(Color.parseColor("#06B6D4"), Neon.purple, Neon.purple2, Color.parseColor("#06B6D4")), null)
            c.save(); c.rotate(-90f, cx, cy)
            c.drawArc(box, 0f, 360f * prog, false, p)
            c.restore()
            p.shader = null
            // bolinha pra arrastar
            val a = Math.toRadians((360.0 * prog) - 90)
            val kx = cx + ringR * cos(a).toFloat(); val ky = cy + ringR * sin(a).toFloat()
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            c.drawCircle(kx, ky, (if (dragging) 11 else 7) * dp, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = Neon.purple
            c.drawCircle(kx, ky, (if (dragging) 11 else 7) * dp, p)
            p.strokeWidth = 10 * dp
        }

        // centro
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#F8FAFC")
        c.drawCircle(cx, cy, centerR, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1 * dp; p.color = Neon.stroke
        c.drawCircle(cx, cy, centerR, p)
        val iconSize = centerR * 0.55f
        val iy = cy - centerR * 0.14f
        p.style = Paint.Style.FILL; p.color = Neon.purple
        when {
            speaker -> {
                val r = (iconSize / 2).toInt()
                speakerIcon.setTint(Neon.purple)
                speakerIcon.setBounds((cx - r).toInt(), (iy - r).toInt(), (cx + r).toInt(), (iy + r).toInt())
                speakerIcon.draw(c)
            }
            playing -> {
                val w = iconSize * 0.24f; val h = iconSize * 0.8f; val gap = iconSize * 0.16f
                c.drawRoundRect(RectF(cx - gap - w, iy - h / 2, cx - gap, iy + h / 2), 3 * dp, 3 * dp, p)
                c.drawRoundRect(RectF(cx + gap, iy - h / 2, cx + gap + w, iy + h / 2), 3 * dp, 3 * dp, p)
            }
            else -> {
                val h = iconSize * 0.8f
                val path = Path().apply {
                    moveTo(cx - h * 0.35f, iy - h / 2); lineTo(cx - h * 0.35f, iy + h / 2); lineTo(cx + h * 0.5f, iy); close()
                }
                c.drawPath(path, p)
            }
        }
        txt.textSize = 17 * sp; txt.color = Neon.white
        c.drawText(timeText, cx, cy + centerR * 0.52f, txt)

        // TVs em volta
        badgeRects.clear()
        val n = tvs.size
        val bw = 84 * dp; val bh = 60 * dp
        for (i in 0 until n) {
            val tv = tvs[i]
            val ang = Math.toRadians(-90.0 + 360.0 * i / n)
            val x = cx + orbitR * cos(ang).toFloat()
            val y = cy + orbitR * sin(ang).toFloat()
            val r = RectF(x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2)
            badgeRects += r
            val glow = if (tv.on && playing) 0.5f + 0.5f * sin(t * 2.2f + i).toFloat() else 0f
            if (glow > 0f) {
                p.style = Paint.Style.FILL; p.color = Color.argb((55 * glow).toInt(), 37, 99, 235)
                val g = 6 * dp * glow
                c.drawRoundRect(RectF(r.left - g, r.top - g, r.right + g, r.bottom + g), 18 * dp, 18 * dp, p)
            }
            p.style = Paint.Style.FILL
            p.color = if (tv.on) Color.WHITE else Color.parseColor("#F1F5F9")
            c.drawRoundRect(r, 14 * dp, 14 * dp, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * dp
            p.color = if (tv.on) Neon.purple else Neon.stroke
            c.drawRoundRect(r, 14 * dp, 14 * dp, p)
            tvIcon.setTint(if (tv.on) Neon.purple else Color.parseColor("#94A3B8"))
            val isz = (22 * dp).toInt()
            tvIcon.setBounds((x - isz / 2).toInt(), (r.top + 8 * dp).toInt(), (x + isz / 2).toInt(), (r.top + 8 * dp).toInt() + isz)
            tvIcon.draw(c)
            txt.textSize = 11 * sp; txt.color = if (tv.on) Neon.white else Color.parseColor("#94A3B8")
            c.drawText(ellipsize(tv.name, bw - 10 * dp), x, r.bottom - 10 * dp, txt)
            p.style = Paint.Style.FILL
            p.color = if (tv.on) Color.parseColor("#06B6D4") else Color.parseColor("#CBD5E1")
            c.drawCircle(r.right - 9 * dp, r.top + 9 * dp, 4 * dp, p)
        }
        if (n == 0) {
            txt.textSize = 13 * sp; txt.color = Neon.soft
            c.drawText("Abra o Simulplay nas TVs", cx, cy - orbitR + 6 * dp, txt)
        }

        if (playing || dragging) postInvalidateOnAnimation()
    }

    private fun ellipsize(s: String, max: Float): String {
        if (txt.measureText(s) <= max) return s
        var e = s
        while (e.length > 1 && txt.measureText("$e…") > max) e = e.dropLast(1)
        return "$e…"
    }

    private fun angleP(x: Float, y: Float): Float {
        var a = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())) + 90
        if (a < 0) a += 360
        return (a / 360).toFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val d = hypot(e.x - cx, e.y - cy)
                val bi = badgeRects.indexOfFirst { it.contains(e.x, e.y) }
                downTarget = when {
                    bi >= 0 -> bi
                    d < centerR -> CENTER
                    seekable && d in (ringR - 26 * dp)..(ringR + 26 * dp) -> RING
                    else -> NONE
                }
                if (downTarget == RING) {
                    dragging = true; dragP = angleP(e.x, e.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                return downTarget != NONE
            }
            MotionEvent.ACTION_MOVE -> if (dragging) { dragP = angleP(e.x, e.y); invalidate() }
            MotionEvent.ACTION_UP -> {
                when {
                    dragging -> { dragging = false; progress = dragP; onSeek?.invoke(dragP) }
                    downTarget == CENTER && hypot(e.x - cx, e.y - cy) < centerR -> { performClick(); onToggle?.invoke() }
                    downTarget >= 0 && badgeRects.getOrNull(downTarget)?.contains(e.x, e.y) == true -> onTv?.invoke(downTarget)
                }
                downTarget = NONE
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; downTarget = NONE; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        private const val NONE = -1
        private const val CENTER = -2
        private const val RING = -3
    }
}
