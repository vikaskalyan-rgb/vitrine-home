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
import java.util.Calendar
import java.util.Locale

/** Forecast data the weather card shows. */
class Weather(
    val place: String, val temp: Int, val code: Int, val isDay: Boolean, val hi: Int, val lo: Int,
    val days: List<Triple<String, Int, Pair<Int, Int>>>   // day name, code, (max, min)
)

fun weatherText(code: Int): String = when (code) {
    0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Cloudy"
    45, 48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"
    in 80..82 -> "Showers"; 85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorm"
    else -> "—"
}

/** Simple line-art weather glyphs (sun, moon, cloud, rain, storm, fog, snow). */
object WeatherGlyph {
    private val r = RectF()
    private val path = Path()

    fun draw(c: Canvas, code: Int, isDay: Boolean, cx: Float, cy: Float, size: Float, p: Paint) {
        val s = size / 2f
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = maxOf(1.5f, size * 0.06f)
        when {
            code <= 1 -> if (isDay) sun(c, cx, cy, s * 0.95f, p) else moon(c, cx, cy, s * 0.8f, p)
            code == 2 -> { if (isDay) sun(c, cx - s * 0.3f, cy - s * 0.3f, s * 0.55f, p) else moon(c, cx - s * 0.3f, cy - s * 0.35f, s * 0.45f, p); cloud(c, cx + s * 0.1f, cy + s * 0.2f, s * 0.8f, p) }
            code == 3 -> cloud(c, cx, cy, s, p)
            code == 45 || code == 48 -> for (k in -1..1) c.drawLine(cx - s * 0.9f + k * s * 0.15f, cy + k * s * 0.45f, cx + s * 0.9f + k * s * 0.15f, cy + k * s * 0.45f, p)
            code in 95..99 -> {
                cloud(c, cx, cy - s * 0.2f, s * 0.9f, p)
                path.reset(); path.moveTo(cx + s * 0.05f, cy + s * 0.15f); path.lineTo(cx - s * 0.25f, cy + s * 0.6f)
                path.lineTo(cx + s * 0.1f, cy + s * 0.6f); path.lineTo(cx - s * 0.15f, cy + s * 1.0f); c.drawPath(path, p)
            }
            code in 71..77 || code == 85 || code == 86 -> {
                cloud(c, cx, cy - s * 0.2f, s * 0.9f, p)
                p.style = Paint.Style.FILL
                for (k in -1..1) c.drawCircle(cx + k * s * 0.4f, cy + s * 0.75f, s * 0.08f, p)
                p.style = Paint.Style.STROKE
            }
            code in 51..67 || code in 80..82 -> {
                cloud(c, cx, cy - s * 0.2f, s * 0.9f, p)
                for (k in -1..1) c.drawLine(cx + k * s * 0.4f, cy + s * 0.5f, cx + k * s * 0.4f - s * 0.15f, cy + s * 0.9f, p)
            }
            else -> cloud(c, cx, cy, s, p)
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        c.drawCircle(cx, cy, s * 0.45f, p)
        for (k in 0 until 8) {
            val a = Math.PI * k / 4
            val cs = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
            c.drawLine(cx + cs * s * 0.68f, cy + sn * s * 0.68f, cx + cs * s * 0.95f, cy + sn * s * 0.95f, p)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        path.reset()
        r.set(cx - s, cy - s, cx + s, cy + s)
        path.arcTo(r, 60f, 240f)
        r.set(cx - s * 0.35f, cy - s * 0.95f, cx + s * 1.05f, cy + s * 0.95f)
        path.arcTo(r, 240f, -120f)
        path.close()
        c.drawPath(path, p)
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        path.reset()
        val base = cy + s * 0.45f
        path.moveTo(cx - s * 0.75f, base)
        r.set(cx - s * 0.95f, base - s * 0.62f, cx - s * 0.33f, base); path.arcTo(r, 90f, 180f)
        r.set(cx - s * 0.55f, base - s * 1.05f, cx + s * 0.35f, base - s * 0.15f); path.arcTo(r, 200f, 150f)
        r.set(cx + s * 0.05f, base - s * 0.75f, cx + s * 0.95f, base); path.arcTo(r, 270f, 180f)
        path.close()
        c.drawPath(path, p)
    }
}

private fun tp(px: Float, alpha: Int, light: Boolean = false, align: Paint.Align = Paint.Align.LEFT) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Theme.ink; this.alpha = alpha; textSize = px; textAlign = align
    typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
    setShadowLayer(3f, 0f, 1f, Theme.shadow)
}

/** Glass card base for widgets: not focusable, darker smoky material. */
open class GlassCard(context: Context, source: GlassSource, protected val px: (Float) -> Float) : GlassView(context, source) {
    init {
        isFocusable = false; isClickable = false; isLongClickable = false
        rest = Theme.card(Color.argb(60, 22, 24, 28))
        radius = px(32f)
    }
}

/**
 * Weather card, laid out on fixed rows so nothing can overlap:
 *   place ............................ glyph
 *   24°   Showers
 *         H 30°  L 24°
 *   ─────────────────────────────────────────
 *   Wed       Thu       Fri       Sat
 *   glyph     glyph     glyph     glyph
 *   29° 24°   31° 24°   30° 24°   31° 23°
 */
class WeatherCard(context: Context, source: GlassSource, px: (Float) -> Float) : GlassCard(context, source, px) {
    var weather: Weather? = null
        set(v) { field = v; invalidate() }
    private val place = tp(px(22f), 185)
    private val big = tp(px(74f), 255, light = true)
    private val cond = tp(px(25f), 235)
    private val hilo = tp(px(22f), 195)
    private val day = tp(px(22f), 190, align = Paint.Align.CENTER)
    private val temps = tp(px(22f), 235, align = Paint.Align.CENTER)
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink(46); strokeWidth = px(1.2f) }

    override fun drawContent(canvas: Canvas) {
        val pad = px(26f)
        val w = weather
        canvas.drawText(w?.place ?: "Weather", pad, pad - place.ascent(), place)
        if (w == null) { canvas.drawText("Loading…", pad, px(110f), cond); return }

        // Current conditions block (top ~half of the card).
        val tempBase = px(128f)
        val t = "${w.temp}°"
        canvas.drawText(t, pad - px(4f), tempBase, big)
        val tx = pad + big.measureText(t) + px(14f)
        canvas.drawText(weatherText(w.code), tx, tempBase - px(34f), cond)
        canvas.drawText("H ${w.hi}°   L ${w.lo}°", tx, tempBase - px(4f), hilo)
        gp.alpha = 255
        WeatherGlyph.draw(canvas, w.code, w.isDay, width - pad - px(40f), px(78f), px(76f), gp)

        // Divider, then the next four days.
        val divY = px(152f)
        canvas.drawLine(pad, divY, width - pad, divY, line)
        val n = minOf(4, w.days.size)
        if (n == 0) return
        val colW = (width - 2 * pad) / n
        for (i in 0 until n) {
            val (name, code, mm) = w.days[i]
            val cx = pad + colW * i + colW / 2f
            canvas.drawText(name, cx, divY + px(32f), day)
            gp.alpha = 230
            WeatherGlyph.draw(canvas, code, true, cx, divY + px(62f), px(32f), gp)
            canvas.drawText("${mm.first}°  ${mm.second}°", cx, divY + px(104f), temps)
        }
    }
}

class CalendarCard(context: Context, source: GlassSource, px: (Float) -> Float) : GlassCard(context, source, px) {
    /** Next event today or soon, e.g. "4:30 PM · Standup". */
    var nextEvent: String? = null
        set(v) { field = v; invalidate() }
    private val title = tp(px(24f), 235)
    private val head = tp(px(18f), 150, align = Paint.Align.CENTER)
    private val num = tp(px(20f), 225, align = Paint.Align.CENTER)
    private val today = tp(px(20f), 255, align = Paint.Align.CENTER).apply { color = Theme.paper; clearShadowLayer() }
    private val ev = tp(px(20f), 215)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink }

    override fun drawContent(canvas: Canvas) {
        val pad = px(26f)
        val now = Calendar.getInstance()
        val month = now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()) ?: ""
        canvas.drawText("$month ${now.get(Calendar.YEAR)}", pad, pad + title.textSize, title)
        val gridTop = pad + title.textSize + px(20f)
        val colW = (width - 2 * pad) / 7f
        val first = now.firstDayOfWeek
        val heads = arrayOf("S", "M", "T", "W", "T", "F", "S")
        for (i in 0 until 7) {
            val dow = (first - 1 + i) % 7
            canvas.drawText(heads[dow], pad + colW * i + colW / 2f, gridTop + head.textSize, head)
        }
        val cal = now.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val offset = (cal.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rowH = px(30f)
        val rowsTop = gridTop + head.textSize + px(10f)
        val d = now.get(Calendar.DAY_OF_MONTH)
        for (day in 1..days) {
            val cell = offset + day - 1
            val cx = pad + colW * (cell % 7) + colW / 2f
            val cy = rowsTop + rowH * (cell / 7) + rowH * 0.5f
            val base = cy + num.textSize * 0.36f
            if (day == d) {
                canvas.drawCircle(cx, cy, rowH * 0.48f, dot)
                canvas.drawText(day.toString(), cx, base, today)
            } else canvas.drawText(day.toString(), cx, base, num)
        }
        nextEvent?.let {
            val y = height - pad
            val t = TextUtils.ellipsize(it, ev, width - 2 * pad, TextUtils.TruncateAt.END).toString()
            canvas.drawText(t, pad, y, ev)
        }
    }
}
