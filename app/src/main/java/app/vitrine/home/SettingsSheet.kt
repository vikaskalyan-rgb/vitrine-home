package app.vitrine.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import kotlin.math.cos
import kotlin.math.sin

/**
 * One row of the glass Settings sheet: a white line glyph, a title, an optional live subtitle, and either a
 * chevron (opens something) or a level bar (Volume: Left/Right adjust it). Glass shows only on the focused row.
 */
class SettingRow(
    context: Context, source: GlassSource, private val glyph: Int, private val title: String, private val rowH: Float
) : GlassView(context, source) {
    var subtitle: String? = null
        set(v) { field = v; invalidate() }
    /** 0..1 to draw a level bar instead of a chevron. */
    var level: Float? = null
        set(v) { field = v; invalidate() }
    var muted = false
        set(v) { field = v; invalidate() }
    /** On/off switch instead of a chevron (Reduce motion, Evening mode). */
    var toggle: Boolean? = null
        set(v) { field = v; invalidate() }
    private val swP = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Left/Right on this row: called with -1 or +1. */
    var onAdjust: ((Int) -> Unit)? = null

    private val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ink; textSize = rowH * 0.30f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(3f * dp, 0f, 1f * dp, Theme.shadow)
    }
    private val subP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ink; textSize = rowH * 0.22f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val valueP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ink; textSize = rowH * 0.26f; textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ink; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val barP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val path = Path()
    private val r = RectF()

    init {
        idleHidden = true; lens = false; focusScale = 1.02f; liftDp = 0f; isLongClickable = false
        contentDescription = title
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val a = onAdjust
        if (a != null && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            a(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun drawContent(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val alpha = if (active) 255 else 215
        // Glyph
        val gx = h * 0.52f; val gy = h / 2f; val s = h * 0.15f
        gp.strokeWidth = maxOf(1.5f, h * 0.026f); gp.alpha = alpha
        drawGlyph(canvas, glyph, gx, gy, s)
        // Right side: chevron, or value + bar
        val tx = h * 1.02f
        var right = w - h * 0.36f
        val lv = level
        val tg = toggle
        if (tg != null && lv == null) {
            // Switch: a pill track with a knob, filled when on.
            val tw = h * 0.50f; val th = h * 0.28f
            val x1 = w - h * 0.34f; val x0 = x1 - tw; val y0 = gy - th / 2f
            r.set(x0, y0, x1, y0 + th)
            swP.style = Paint.Style.FILL
            if (tg) { swP.color = Theme.ink(if (active) 255 else 225); canvas.drawRoundRect(r, th / 2f, th / 2f, swP) }
            else { swP.style = Paint.Style.STROKE; swP.strokeWidth = h * 0.02f; swP.color = Theme.ink(if (active) 170 else 110); canvas.drawRoundRect(r, th / 2f, th / 2f, swP) }
            swP.style = Paint.Style.FILL
            swP.color = if (tg) Theme.paper else Theme.ink(if (active) 200 else 150)
            canvas.drawCircle(if (tg) x1 - th / 2f else x0 + th / 2f, gy, th * 0.36f, swP)
            right = x0 - h * 0.2f
        } else if (lv == null) {
            val cx = w - h * 0.38f; val c = h * 0.07f
            gp.alpha = if (active) 230 else 120
            path.reset(); path.moveTo(cx - c * 0.5f, gy - c); path.lineTo(cx + c * 0.5f, gy); path.lineTo(cx - c * 0.5f, gy + c)
            canvas.drawPath(path, gp)
            right = cx - h * 0.25f
        } else {
            valueP.alpha = alpha
            val txt = if (muted) "Muted" else (lv * 100).toInt().toString()
            canvas.drawText(txt, w - h * 0.34f, gy - (valueP.descent() + valueP.ascent()) / 2f - h * 0.10f, valueP)
            val by = h * 0.76f; val x0 = tx; val x1 = w - h * 0.34f
            barP.strokeWidth = h * 0.05f
            barP.color = Theme.ink(if (active) 70 else 45)
            canvas.drawLine(x0, by, x1, by, barP)
            if (!muted && lv > 0f) {
                barP.color = Theme.ink(alpha)
                canvas.drawLine(x0, by, x0 + (x1 - x0) * lv.coerceIn(0f, 1f), by, barP)
            }
            right = w - h * 1.0f
        }
        // Title + subtitle
        titleP.alpha = alpha
        val sub = subtitle
        val maxW = right - tx
        if (sub.isNullOrEmpty() || lv != null) {
            val y = if (lv != null) h * 0.44f else gy - (titleP.descent() + titleP.ascent()) / 2f
            canvas.drawText(TextUtils.ellipsize(title, titleP, maxW, TextUtils.TruncateAt.END).toString(), tx, y, titleP)
        } else {
            canvas.drawText(TextUtils.ellipsize(title, titleP, maxW, TextUtils.TruncateAt.END).toString(), tx, h * 0.46f, titleP)
            subP.alpha = if (active) 190 else 140
            canvas.drawText(TextUtils.ellipsize(sub, subP, maxW, TextUtils.TruncateAt.END).toString(), tx, h * 0.76f, subP)
        }
    }

    private fun drawGlyph(c: Canvas, g: Int, cx: Float, cy: Float, s: Float) {
        when (g) {
            VOLUME -> {
                path.reset()
                path.moveTo(cx - s * 1.1f, cy - s * 0.4f); path.lineTo(cx - s * 0.55f, cy - s * 0.4f)
                path.lineTo(cx + s * 0.05f, cy - s * 0.95f); path.lineTo(cx + s * 0.05f, cy + s * 0.95f)
                path.lineTo(cx - s * 0.55f, cy + s * 0.4f); path.lineTo(cx - s * 1.1f, cy + s * 0.4f); path.close()
                c.drawPath(path, gp)
                if (!muted) for (k in 1..2) {
                    val rr = s * (0.45f + 0.42f * k)
                    r.set(cx - rr + s * 0.15f, cy - rr, cx + rr + s * 0.15f, cy + rr)
                    c.drawArc(r, -45f, 90f, false, gp)
                } else {
                    c.drawLine(cx + s * 0.45f, cy - s * 0.4f, cx + s * 1.15f, cy + s * 0.4f, gp)
                    c.drawLine(cx + s * 1.15f, cy - s * 0.4f, cx + s * 0.45f, cy + s * 0.4f, gp)
                }
            }
            WIFI -> {
                val by = cy + s * 0.8f
                for (k in 1..3) { val rr = s * 0.55f * k; r.set(cx - rr, by - rr, cx + rr, by + rr); c.drawArc(r, 225f, 90f, false, gp) }
                gp.style = Paint.Style.FILL; c.drawCircle(cx, by - s * 0.05f, s * 0.15f, gp); gp.style = Paint.Style.STROKE
            }
            BLUETOOTH -> {
                path.reset()
                path.moveTo(cx - s * 0.6f, cy - s * 0.5f); path.lineTo(cx + s * 0.55f, cy + s * 0.45f)
                path.lineTo(cx, cy + s); path.lineTo(cx, cy - s); path.lineTo(cx + s * 0.55f, cy - s * 0.45f)
                path.lineTo(cx - s * 0.6f, cy + s * 0.5f)
                c.drawPath(path, gp)
            }
            PICTURE -> { // brightness sun
                c.drawCircle(cx, cy, s * 0.45f, gp)
                for (k in 0 until 8) {
                    val a = Math.PI * k / 4
                    c.drawLine(cx + (s * 0.75f * cos(a)).toFloat(), cy + (s * 0.75f * sin(a)).toFloat(),
                        cx + (s * 1.05f * cos(a)).toFloat(), cy + (s * 1.05f * sin(a)).toFloat(), gp)
                }
            }
            SOUND -> { // equaliser bars
                val xs = floatArrayOf(-0.9f, -0.3f, 0.3f, 0.9f); val hs = floatArrayOf(0.5f, 1.0f, 0.7f, 0.35f)
                for (i in 0..3) c.drawLine(cx + s * xs[i], cy - s * hs[i], cx + s * xs[i], cy + s * hs[i], gp)
            }
            INPUTS -> {
                r.set(cx - s * 1.2f, cy - s * 0.9f, cx + s * 1.2f, cy + s * 0.6f); c.drawRoundRect(r, s * 0.25f, s * 0.25f, gp)
                c.drawLine(cx - s * 0.5f, cy + s * 1.05f, cx + s * 0.5f, cy + s * 1.05f, gp)
            }
            WALLPAPER -> {
                r.set(cx - s * 1.2f, cy - s * 0.95f, cx + s * 1.2f, cy + s * 0.95f); c.drawRoundRect(r, s * 0.3f, s * 0.3f, gp)
                path.reset(); path.moveTo(cx - s * 1.0f, cy + s * 0.65f); path.lineTo(cx - s * 0.3f, cy - s * 0.05f)
                path.lineTo(cx + s * 0.2f, cy + s * 0.45f); path.lineTo(cx + s * 0.55f, cy + s * 0.15f); path.lineTo(cx + s * 1.0f, cy + s * 0.6f)
                c.drawPath(path, gp); c.drawCircle(cx + s * 0.5f, cy - s * 0.4f, s * 0.15f, gp)
            }
            APPS -> {
                val q = s * 0.42f; val o = s * 0.5f
                for (dx in listOf(-o, o)) for (dy in listOf(-o, o)) {
                    r.set(cx + dx - q, cy + dy - q, cx + dx + q, cy + dy + q); c.drawRoundRect(r, q * 0.45f, q * 0.45f, gp)
                }
            }
            STORAGE -> {
                for (k in -1..1) { r.set(cx - s * 1.05f, cy + k * s * 0.62f - s * 0.25f, cx + s * 1.05f, cy + k * s * 0.62f + s * 0.25f); c.drawRoundRect(r, s * 0.2f, s * 0.2f, gp) }
            }
            CLOCK -> {
                c.drawCircle(cx, cy, s * 1.0f, gp)
                c.drawLine(cx, cy, cx, cy - s * 0.6f, gp); c.drawLine(cx, cy, cx + s * 0.45f, cy + s * 0.25f, gp)
            }
            INFO -> {
                c.drawCircle(cx, cy, s * 1.0f, gp)
                c.drawLine(cx, cy - s * 0.1f, cx, cy + s * 0.55f, gp)
                gp.style = Paint.Style.FILL; c.drawCircle(cx, cy - s * 0.48f, gp.strokeWidth * 0.75f, gp); gp.style = Paint.Style.STROKE
            }
            RESTART -> {
                r.set(cx - s, cy - s, cx + s, cy + s); c.drawArc(r, -60f, 300f, false, gp)
                val ax = cx + (s * cos(Math.toRadians(-60.0))).toFloat(); val ay = cy + (s * sin(Math.toRadians(-60.0))).toFloat()
                path.reset(); path.moveTo(ax - s * 0.5f, ay - s * 0.05f); path.lineTo(ax, ay); path.lineTo(ax + s * 0.05f, ay + s * 0.5f)
                c.drawPath(path, gp)
            }
            SLEEP -> { // crescent moon
                path.reset(); path.addCircle(cx, cy, s * 1.0f, Path.Direction.CW)
                val cut = Path(); cut.addCircle(cx + s * 0.6f, cy - s * 0.42f, s * 0.82f, Path.Direction.CW)
                path.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(path, gp)
            }
            else -> { // gear
                val ro = s * 1.05f; val ri = s * 0.4f
                c.drawCircle(cx, cy, ri, gp)
                path.reset()
                val n = 32
                for (k in 0 until n) {
                    val a = Math.PI * 2 * k / n
                    val rr = if ((k / 2) % 2 == 0) ro else ro * 0.8f
                    val px = cx + (rr * cos(a)).toFloat(); val py = cy + (rr * sin(a)).toFloat()
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close(); c.drawPath(path, gp)
            }
        }
    }

    companion object {
        const val VOLUME = 0; const val WIFI = 1; const val BLUETOOTH = 2; const val PICTURE = 3; const val SOUND = 4
        const val INPUTS = 5; const val WALLPAPER = 6; const val APPS = 7; const val STORAGE = 8; const val CLOCK = 9
        const val INFO = 10; const val RESTART = 11; const val GEAR = 12; const val SLEEP = 13
    }
}
