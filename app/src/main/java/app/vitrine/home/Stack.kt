package app.vitrine.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.animation.DecelerateInterpolator
import java.util.Calendar
import java.util.Locale

/** One widget inside the Smart Stack. */
interface StackPage {
    /** Whether there is anything worth showing (pages with no data are skipped). */
    val ready: Boolean
    fun draw(c: Canvas, w: Float, h: Float)
}

internal fun sp(px: Float, alpha: Int, light: Boolean = false, align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ink; this.alpha = alpha; textSize = px; textAlign = align
        typeface = Typeface.create(if (light) "sans-serif-light" else if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setShadowLayer(3f, 0f, 1f, Theme.shadow)
    }

internal fun ellip(t: String, p: TextPaint, w: Float) = TextUtils.ellipsize(t, p, w, TextUtils.TruncateAt.END).toString()

/** A thin rounded progress/level bar. */
internal fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, fill: Int, track: Paint, paint: Paint, r: RectF) {
    r.set(x, y, x + w, y + h); c.drawRoundRect(r, h / 2, h / 2, track)
    paint.color = fill
    r.set(x, y, x + w * frac.coerceIn(0f, 1f), y + h); c.drawRoundRect(r, h / 2, h / 2, paint)
}

/**
 * Smart Stack: one glass card that rotates between pages every 10 s with a short vertical slide,
 * skipping pages that have nothing to show. Vertical dots on the right edge show the position.
 */
class SmartStack(context: Context, source: GlassSource, px: (Float) -> Float) : GlassCard(context, source, px) {
    val pages = ArrayList<StackPage>()
    private var index = 0
    private var from = -1
    private var t = 1f
    private var anim: ValueAnimator? = null
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink }

    private fun readyList() = pages.indices.filter { pages[it].ready }

    /** Advance to the next page that has content. */
    fun next() {
        val r = readyList(); if (r.isEmpty()) return
        val pos = r.indexOf(index)
        show(r[(pos + 1).mod(r.size)])
    }

    /** Jump to a specific page (e.g. Now Playing when music starts). */
    fun showPage(p: StackPage) { val i = pages.indexOf(p); if (i >= 0 && i != index && p.ready) show(i) }

    val current: StackPage? get() = pages.getOrNull(index)

    private fun show(i: Int) {
        if (i == index) { invalidate(); return }
        from = index; index = i
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520; interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun drawContent(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (!(pages.getOrNull(index)?.ready ?: false)) { readyList().firstOrNull()?.let { index = it; from = -1; t = 1f } }
        val cur = pages.getOrNull(index) ?: return
        val slide = px(26f)
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        if (t < 1f && from >= 0 && from < pages.size) {
            val a = ((1f - t) * 255).toInt()
            canvas.saveLayerAlpha(0f, 0f, w, h, a)
            canvas.translate(0f, -slide * t)
            pages[from].draw(canvas, w, h)
            canvas.restore()
            canvas.saveLayerAlpha(0f, 0f, w, h, (t * 255).toInt())
            canvas.translate(0f, slide * (1f - t))
            cur.draw(canvas, w, h)
            canvas.restore()
        } else cur.draw(canvas, w, h)
        canvas.restore()
        // Dots, vertical, right edge.
        val r = readyList()
        if (r.size > 1) {
            val gap = px(14f); val total = gap * (r.size - 1)
            var y = h / 2 - total / 2
            for (i in r) {
                dot.alpha = if (i == index) 230 else 80
                canvas.drawCircle(w - px(16f), y, if (i == index) px(3.6f) else px(2.8f), dot)
                y += gap
            }
        }
    }
}

// ---------------------------------------------------------------- pages

class CalendarPage(private val px: (Float) -> Float) : StackPage {
    var nextEvent: String? = null
    override val ready = true
    private val title = sp(px(24f), 235)
    private val head = sp(px(20f), 165, align = Paint.Align.CENTER)
    private val num = sp(px(23f), 235, align = Paint.Align.CENTER)
    private val today = sp(px(23f), 255, align = Paint.Align.CENTER, bold = true).apply { color = Theme.paper; clearShadowLayer() }
    private val ev = sp(px(20f), 215)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink }

    override fun draw(c: Canvas, w: Float, h: Float) {
        val pad = px(26f); val right = px(30f)
        val now = Calendar.getInstance()
        val month = now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()) ?: ""
        c.drawText("$month ${now.get(Calendar.YEAR)}", pad, pad + title.textSize, title)
        val gridTop = pad + title.textSize + px(20f)
        val colW = (w - pad - right) / 7f
        val first = now.firstDayOfWeek
        val heads = arrayOf("S", "M", "T", "W", "T", "F", "S")
        for (i in 0 until 7) c.drawText(heads[(first - 1 + i) % 7], pad + colW * i + colW / 2f, gridTop + head.textSize, head)
        val cal = now.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val offset = (cal.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rowsTop = gridTop + head.textSize + px(10f)
        // Rows sized to this month (5 or 6 weeks); the next-event line is shown only if there's room.
        val rows = (offset + days + 6) / 7
        val evSpace = ev.textSize + px(14f)
        val withEvent = (h - pad - rowsTop - evSpace) / rows
        val showEvent = nextEvent != null && withEvent >= px(33f)
        val rowH = minOf(px(36f), if (showEvent) withEvent else (h - pad * 0.7f - rowsTop) / rows)
        val d = now.get(Calendar.DAY_OF_MONTH)
        for (day in 1..days) {
            val cell = offset + day - 1
            val cx = pad + colW * (cell % 7) + colW / 2f
            val cy = rowsTop + rowH * (cell / 7) + rowH * 0.5f
            val base = cy + num.textSize * 0.36f
            if (day == d) { c.drawCircle(cx, cy, rowH * 0.48f, dot); c.drawText(day.toString(), cx, base, today) }
            else c.drawText(day.toString(), cx, base, num)
        }
        if (showEvent) nextEvent?.let { c.drawText(ellip(it, ev, w - pad - right), pad, h - pad, ev) }
    }
}

/** What's playing right now (Spotify, YouTube Music…), with artwork and progress. */
class NowPlayingPage(private val px: (Float) -> Float) : StackPage {
    var app: String = ""
    var title: String? = null
    var artist: String = ""
    var art: Bitmap? = null
    var playing = false
    var durationMs = 0L
    /** Current position, computed by the caller from the playback state. */
    var positionMs: () -> Long = { 0L }
    override val ready get() = title != null

    private val label = sp(px(18f), 160, bold = true)
    private val tt = sp(px(25f), 250, bold = true)
    private val ar = sp(px(20f), 190)
    private val time = sp(px(16f), 150)
    private val timeR = sp(px(16f), 150, align = Paint.Align.RIGHT)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink(60) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink(40) }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink }
    private val r = RectF(); private val m = Matrix(); private val path = Path()

    private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

    override fun draw(c: Canvas, w: Float, h: Float) {
        val pad = px(26f); val right = px(30f)
        c.drawText((if (playing) "NOW PLAYING · " else "PAUSED · ") + app.uppercase(Locale.getDefault()), pad, pad + label.textSize, label)
        val artS = px(132f); val artTop = pad + label.textSize + px(18f)
        r.set(pad, artTop, pad + artS, artTop + artS)
        val b = art
        if (b != null) {
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = maxOf(artS / b.width, artS / b.height)
            m.setScale(s, s); m.postTranslate(pad + (artS - b.width * s) / 2, artTop + (artS - b.height * s) / 2)
            sh.setLocalMatrix(m); artPaint.shader = sh
            c.drawRoundRect(r, px(16f), px(16f), artPaint)
        } else c.drawRoundRect(r, px(16f), px(16f), ph)
        val tx = pad + artS + px(20f); val tw = w - tx - right
        c.drawText(ellip(title ?: "", tt, tw), tx, artTop + px(34f), tt)
        c.drawText(ellip(artist, ar, tw), tx, artTop + px(66f), ar)
        // Play / pause glyph.
        val gx = tx + px(10f); val gy = artTop + px(108f); val g = px(11f)
        if (playing) { r.set(gx - g * 0.8f, gy - g, gx - g * 0.25f, gy + g); c.drawRoundRect(r, 2f, 2f, icon); r.set(gx + g * 0.25f, gy - g, gx + g * 0.8f, gy + g); c.drawRoundRect(r, 2f, 2f, icon) }
        else { path.reset(); path.moveTo(gx - g * 0.6f, gy - g); path.lineTo(gx + g, gy); path.lineTo(gx - g * 0.6f, gy + g); path.close(); c.drawPath(path, icon) }
        // Progress.
        val y = h - pad - px(26f)
        val pos = positionMs()
        val frac = if (durationMs > 0) pos.toFloat() / durationMs else 0f
        fill.shader = LinearGradient(pad, 0f, w - right, 0f, Theme.ink(255), Theme.ink(200), Shader.TileMode.CLAMP)
        r.set(pad, y, w - right, y + px(6f)); c.drawRoundRect(r, px(3f), px(3f), track)
        r.set(pad, y, pad + (w - right - pad) * frac.coerceIn(0f, 1f), y + px(6f)); c.drawRoundRect(r, px(3f), px(3f), fill)
        if (durationMs > 0) {
            c.drawText(fmt(pos), pad, y + px(26f), time)
            c.drawText(fmt(durationMs), w - right, y + px(26f), timeR)
        }
    }
}

/** Shows from Android TV's "Watch next" list, if this TV lets launchers read it. */
class ContinuePage(private val px: (Float) -> Float) : StackPage {
    class Item(val title: String, val sub: String, val app: String, val frac: Float)
    var items: List<Item> = emptyList()
    override val ready get() = items.isNotEmpty()
    private val label = sp(px(18f), 160, bold = true)
    private val tt = sp(px(23f), 245, bold = true)
    private val sub = sp(px(18f), 175)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink(60) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun draw(c: Canvas, w: Float, h: Float) {
        val pad = px(26f); val right = px(30f)
        c.drawText("CONTINUE WATCHING", pad, pad + label.textSize, label)
        var y = pad + label.textSize + px(30f)
        for (it in items.take(3)) {
            c.drawText(ellip(it.title, tt, w - pad - right), pad, y + tt.textSize * 0.8f, tt)
            c.drawText(ellip(listOf(it.app, it.sub).filter { s -> s.isNotBlank() }.joinToString(" · "), sub, w - pad - right), pad, y + px(48f), sub)
            if (it.frac > 0f) bar(c, pad, y + px(60f), w - pad - right, px(4f), it.frac, Theme.ink, track, fill, r)
            y += px(84f)
        }
    }
}

/** US AQI with a colour band (Good → Hazardous) and PM2.5 / PM10. */
class AirPage(private val px: (Float) -> Float) : StackPage {
    var place = ""
    var aqi = -1
    var pm25 = 0.0
    var pm10 = 0.0
    override val ready get() = aqi >= 0
    private val label = sp(px(18f), 160, bold = true)
    private val big = sp(px(64f), 255, light = true)
    private val cat = sp(px(24f), 235, bold = true)
    private val small = sp(px(19f), 185)
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0); style = Paint.Style.STROKE }
    private val r = RectF()

    private fun category(a: Int) = when {
        a <= 50 -> "Good"; a <= 100 -> "Moderate"; a <= 150 -> "Unhealthy for sensitive groups"
        a <= 200 -> "Unhealthy"; a <= 300 -> "Very unhealthy"; else -> "Hazardous"
    }

    override fun draw(c: Canvas, w: Float, h: Float) {
        val pad = px(26f); val right = px(30f)
        c.drawText(if (place.isBlank()) "AIR QUALITY" else "AIR QUALITY · ${place.uppercase(Locale.getDefault())}", pad, pad + label.textSize, label)
        val base = pad + label.textSize + px(78f)
        c.drawText(aqi.toString(), pad - px(3f), base, big)
        val tx = pad + big.measureText(aqi.toString()) + px(16f)
        c.drawText(ellip(category(aqi), cat, w - tx - right), tx, base - px(30f), cat)
        c.drawText("US AQI", tx, base - px(2f), small)
        // Colour band with a marker at the current value (0..300 scale).
        val by = base + px(34f); val bw = w - pad - right; val bh = px(8f)
        band.shader = LinearGradient(pad, 0f, pad + bw, 0f,
            intArrayOf(Color.rgb(88, 200, 120), Color.rgb(240, 210, 70), Color.rgb(245, 150, 60), Color.rgb(235, 80, 80), Color.rgb(160, 80, 170), Color.rgb(120, 40, 60)),
            floatArrayOf(0f, 0.17f, 0.34f, 0.5f, 0.67f, 1f), Shader.TileMode.CLAMP)
        r.set(pad, by, pad + bw, by + bh); c.drawRoundRect(r, bh / 2, bh / 2, band)
        val mx = pad + bw * (aqi / 300f).coerceIn(0f, 1f)
        ring.strokeWidth = px(2f)
        c.drawCircle(mx, by + bh / 2, px(9f), marker); c.drawCircle(mx, by + bh / 2, px(9f), ring)
        c.drawText("PM2.5  ${"%.0f".format(pm25)} µg/m³", pad, h - pad, small)
        c.drawText("PM10  ${"%.0f".format(pm10)}", pad + (w - pad - right) * 0.60f, h - pad, small)
    }
}

/** Free memory, free storage, Wi-Fi name and signal, uptime. */
class HealthPage(private val px: (Float) -> Float) : StackPage {
    var memFree = 0L; var memTotal = 1L
    var diskFree = 0L; var diskTotal = 1L
    var wifi = ""; var signal = 0  // 0..4
    var uptime = ""
    override val ready get() = memTotal > 1
    private val label = sp(px(18f), 160, bold = true)
    private val k = sp(px(20f), 200)
    private val v = sp(px(20f), 240, align = Paint.Align.RIGHT, bold = true)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ink(55) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private fun gb(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()) else "${b / (1L shl 20)} MB"

    override fun draw(c: Canvas, w: Float, h: Float) {
        val pad = px(26f); val right = px(30f); val bw = w - pad - right
        c.drawText("TV HEALTH", pad, pad + label.textSize, label)
        var y = pad + label.textSize + px(40f)
        fun row(name: String, value: String, frac: Float, color: Int) {
            c.drawText(name, pad, y, k); c.drawText(value, w - right, y, v)
            bar(c, pad, y + px(12f), bw, px(5f), frac, color, track, fill, r)
            y += px(62f)
        }
        val memF = memFree.toFloat() / memTotal
        row("Memory free", gb(memFree), memF, if (memF < 0.2f) Color.rgb(245, 150, 60) else Theme.ink)
        val dF = diskFree.toFloat() / diskTotal
        row("Storage free", gb(diskFree), dF, if (dF < 0.15f) Color.rgb(245, 150, 60) else Theme.ink)
        val sig = if (wifi == "Offline") "Not connected" else if (wifi == "Ethernet") "Connected" else listOf("Weak", "Weak", "Fair", "Good", "Excellent")[signal.coerceIn(0, 4)]
        row(if (wifi.isBlank()) "Wi-Fi" else ellip(wifi, k, bw * 0.6f), sig, signal / 4f, if (wifi == "Offline") Color.rgb(245, 150, 60) else Theme.ink)
        c.drawText("Up $uptime", pad, h - pad + px(4f), k)
    }
}
