package app.vitrine.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent

private fun tp(size: Float, alpha: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, ink: Int = Color.WHITE) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; this.alpha = alpha; textSize = size; textAlign = align
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

/**
 * Glass pill above the dock while something is playing: artwork, title, artist, play/pause.
 * OK = play/pause, Left/Right = previous/next track.
 */
class NowBar(context: Context, source: GlassSource) : GlassView(context, source) {
    var title: String = ""
    var artist: String = ""
    var playing = false
    var art: Bitmap? = null
        set(v) {
            field = v
            artShader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }
    var onToggle: (() -> Unit)? = null
    var onSkip: ((Int) -> Unit)? = null

    private var artShader: BitmapShader? = null
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 255, 255, 255) }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val r = RectF(); private val m = Matrix(); private val path = Path()

    init { lens = true; focusScale = 1.04f; liftDp = 10f; isLongClickable = false }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = h / 2f
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { onSkip?.invoke(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { onSkip?.invoke(1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    fun set(t: String, a: String, p: Boolean, b: Bitmap?) {
        title = t; artist = a; playing = p
        if (b !== art) art = b
        invalidate()
    }

    override fun drawContent(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = h * 0.16f; val s = h - pad * 2
        // Artwork, rounded
        r.set(pad + h * 0.06f, pad, pad + h * 0.06f + s, pad + s)
        val sh = artShader; val b = art
        if (sh != null && b != null) {
            val sc = maxOf(s / b.width, s / b.height)
            m.setScale(sc, sc); m.postTranslate(r.left + (s - b.width * sc) / 2f, r.top + (s - b.height * sc) / 2f)
            sh.setLocalMatrix(m); artPaint.shader = sh
            canvas.drawRoundRect(r, s * 0.22f, s * 0.22f, artPaint)
        } else canvas.drawRoundRect(r, s * 0.22f, s * 0.22f, ph)
        // Play / pause glyph on the right
        val cx = w - h * 0.62f; val cy = h / 2f; val g = h * 0.17f
        icon.alpha = if (active) 255 else 220
        if (playing) {
            canvas.drawRoundRect(cx - g * 0.75f, cy - g, cx - g * 0.2f, cy + g, g * 0.15f, g * 0.15f, icon)
            canvas.drawRoundRect(cx + g * 0.2f, cy - g, cx + g * 0.75f, cy + g, g * 0.15f, g * 0.15f, icon)
        } else {
            path.reset(); path.moveTo(cx - g * 0.6f, cy - g); path.lineTo(cx + g * 0.95f, cy); path.lineTo(cx - g * 0.6f, cy + g); path.close()
            canvas.drawPath(path, icon)
        }
        // Title + artist
        val tx = r.right + h * 0.22f; val maxW = cx - g * 2f - tx
        val tt = tp(h * 0.27f, if (active) 255 else 235, bold = true)
        val at = tp(h * 0.21f, if (active) 200 else 160)
        canvas.drawText(TextUtils.ellipsize(title, tt, maxW, TextUtils.TruncateAt.END).toString(), tx, h * 0.47f, tt)
        if (artist.isNotEmpty())
            canvas.drawText(TextUtils.ellipsize(artist, at, maxW, TextUtils.TruncateAt.END).toString(), tx, h * 0.76f, at)
    }
}

/** "PS5 is on — press OK to switch": shown when an HDMI device powers on. */
class DeviceCard(context: Context, source: GlassSource, private val name: String, private val kind: String = "tv") : GlassView(context, source) {
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ink; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val r = RectF()
    init { lens = true; focusScale = 1.03f; liftDp = 14f; isLongClickable = false; contentDescription = "$name is on" }

    /** Called when the card loses focus (the user moved away), so it can dismiss itself. */
    var onBlur: (() -> Unit)? = null
    override fun onFocus(f: Boolean) { super.onFocus(f); if (!f) onBlur?.invoke() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = h * 0.32f
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun drawContent(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = h * 0.55f; val cy = h / 2f; val s = h * 0.16f
        gp.strokeWidth = maxOf(1.5f, h * 0.03f)
        InputGlyph.draw(canvas, kind, cx, cy, s, gp, r)
        val tx = h * 1.05f; val maxW = w - tx - h * 0.3f
        val t1 = tp(h * 0.27f, 255, bold = true, ink = Theme.ink); val t2 = tp(h * 0.2f, 190, ink = Theme.ink)
        canvas.drawText(TextUtils.ellipsize("$name is on", t1, maxW, TextUtils.TruncateAt.END).toString(), tx, h * 0.46f, t1)
        canvas.drawText("Press OK to switch  ·  Back to dismiss", tx, h * 0.74f, t2)
    }
}

/** Line icons for named inputs: console, set-top box, streaming stick, laptop, disc player, soundbar, camera, TV. */
object InputGlyph {
    private val path = Path()
    fun draw(c: Canvas, kind: String, cx: Float, cy: Float, s: Float, gp: Paint, r: RectF) {
        when (kind) {
            "console" -> { // gamepad
                r.set(cx - s * 1.3f, cy - s * 0.6f, cx + s * 1.3f, cy + s * 0.75f); c.drawRoundRect(r, s * 0.6f, s * 0.6f, gp)
                c.drawLine(cx - s * 0.85f, cy + s * 0.07f, cx - s * 0.35f, cy + s * 0.07f, gp)
                c.drawLine(cx - s * 0.6f, cy - s * 0.18f, cx - s * 0.6f, cy + s * 0.32f, gp)
                c.drawCircle(cx + s * 0.55f, cy - s * 0.05f, s * 0.1f, gp); c.drawCircle(cx + s * 0.85f, cy + s * 0.22f, s * 0.1f, gp)
            }
            "box" -> { // set-top box
                r.set(cx - s * 1.3f, cy - s * 0.35f, cx + s * 1.3f, cy + s * 0.45f); c.drawRoundRect(r, s * 0.15f, s * 0.15f, gp)
                c.drawCircle(cx + s * 0.95f, cy + s * 0.05f, s * 0.08f, gp)
                c.drawLine(cx - s * 1.0f, cy + s * 0.05f, cx - s * 0.2f, cy + s * 0.05f, gp)
            }
            "stick" -> { // streaming stick
                r.set(cx - s * 1.1f, cy - s * 0.3f, cx + s * 0.6f, cy + s * 0.3f); c.drawRoundRect(r, s * 0.2f, s * 0.2f, gp)
                r.set(cx + s * 0.6f, cy - s * 0.15f, cx + s * 1.15f, cy + s * 0.15f); c.drawRect(r, gp)
            }
            "laptop" -> {
                r.set(cx - s * 1.0f, cy - s * 0.9f, cx + s * 1.0f, cy + s * 0.45f); c.drawRoundRect(r, s * 0.12f, s * 0.12f, gp)
                c.drawLine(cx - s * 1.35f, cy + s * 0.8f, cx + s * 1.35f, cy + s * 0.8f, gp)
            }
            "disc" -> { c.drawCircle(cx, cy, s * 1.0f, gp); c.drawCircle(cx, cy, s * 0.25f, gp) }
            "speaker" -> { // soundbar
                r.set(cx - s * 1.4f, cy - s * 0.3f, cx + s * 1.4f, cy + s * 0.3f); c.drawRoundRect(r, s * 0.3f, s * 0.3f, gp)
                for (k in -2..2) c.drawPoint(cx + k * s * 0.5f, cy, gp)
            }
            "camera" -> {
                r.set(cx - s * 1.2f, cy - s * 0.6f, cx + s * 1.2f, cy + s * 0.8f); c.drawRoundRect(r, s * 0.2f, s * 0.2f, gp)
                c.drawCircle(cx, cy + s * 0.1f, s * 0.4f, gp)
                path.reset(); path.moveTo(cx - s * 0.5f, cy - s * 0.6f); path.lineTo(cx - s * 0.3f, cy - s * 0.9f)
                path.lineTo(cx + s * 0.3f, cy - s * 0.9f); path.lineTo(cx + s * 0.5f, cy - s * 0.6f); c.drawPath(path, gp)
            }
            else -> { // TV
                r.set(cx - s * 1.25f, cy - s * 0.95f, cx + s * 1.25f, cy + s * 0.65f); c.drawRoundRect(r, s * 0.25f, s * 0.25f, gp)
                c.drawLine(cx - s * 0.55f, cy + s * 1.1f, cx + s * 0.55f, cy + s * 1.1f, gp)
            }
        }
    }
}

/**
 * Now playing, always above the dock: artwork, title and artist on the left, a wave in the middle that
 * moves with the music, and previous / play-pause / next on the right. When nothing is playing it
 * shows the last thing played. No box at rest; a white border when it has focus.
 * Left/Right pick a control (play/pause first), OK presses it, holding OK for 2 s calls [onHold].
 */
class NowChip(context: Context, source: GlassSource) : GlassView(context, source) {
    var title: String = ""
    var artist: String = ""
    var playing = false
        set(v) { field = v; kick() }
    var art: Bitmap? = null
        set(v) {
            field = v
            artShader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }
    /** 0 = previous, 1 = play/pause, 2 = next. */
    var onAction: ((Int) -> Unit)? = null
    var onHold: (() -> Unit)? = null
    /** Loudness per band, 0..1, low to high (from the TV's sound output); null = not available. */
    var levels: (() -> FloatArray?)? = null
    /** False while Home is hidden, so the wave stops drawing. */
    var waveAllowed = true
        set(v) { field = v; kick() }

    private var sel = 1
    private var holdFired = false
    private var downSeen = false
    private val holdRun = Runnable { holdFired = true; onHold?.invoke() }
    private var artShader: BitmapShader? = null
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val r = RectF(); private val m = Matrix(); private val path = Path(); private val wpath = Path()
    private val env = FloatArray(16)
    private var phase = 0f
    private val t0 = System.nanoTime()

    init {
        lens = false; focusScale = 1.03f; liftDp = 0f; isLongClickable = false; outlineProvider = null
        setOnClickListener { onAction?.invoke(sel) }
    }

    fun set(t: String, a: String, p: Boolean, b: Bitmap?) {
        title = t; artist = a; playing = p
        if (b !== art) art = b
        invalidate()
    }

    override fun onFocus(f: Boolean) { super.onFocus(f); if (f) sel = 1; invalidate() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            // Past the first or last control, Left/Right leave the widget as usual.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (sel > 0) { sel--; invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (sel < 2) { sel++; invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) { downSeen = true; holdFired = false; removeCallbacks(holdRun); postDelayed(holdRun, 2000L) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            removeCallbacks(holdRun)
            // Only a press that started here counts.
            if (downSeen && !holdFired) { playSoundEffect(android.view.SoundEffectConstants.CLICK); onAction?.invoke(sel) }
            holdFired = false; downSeen = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDetachedFromWindow() { removeCallbacks(holdRun); super.onDetachedFromWindow() }

    private fun kick() { postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        if (active) {
            val sw = 2f * dp
            border.strokeWidth = sw; border.color = Theme.ink(255)
            r.set(sw / 2f, sw / 2f, width - sw / 2f, height - sw / 2f)
            canvas.drawRoundRect(r, height / 2f, height / 2f, border)
        }
        drawContent(canvas)
        if (playing && waveAllowed && isShown) postInvalidateDelayed(33L)
    }

    override fun drawContent(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val shadow = Color.argb(100, 0, 0, 0)
        val s = h * 0.66f; val left = h * 0.2f; val top = (h - s) / 2f
        r.set(left, top, left + s, top + s)
        val sh = artShader; val b = art
        if (sh != null && b != null) {
            val sc = maxOf(s / b.width, s / b.height)
            m.setScale(sc, sc); m.postTranslate(r.left + (s - b.width * sc) / 2f, r.top + (s - b.height * sc) / 2f)
            sh.setLocalMatrix(m); artPaint.shader = sh
            canvas.drawRoundRect(r, s * 0.2f, s * 0.2f, artPaint)
        } else {
            ph.color = Theme.ink(40)
            canvas.drawRoundRect(r, s * 0.2f, s * 0.2f, ph)
            note.color = Theme.ink(220); note.strokeWidth = maxOf(1.5f, s * 0.07f)
            val nx = r.centerX() + s * 0.08f; val ny = r.centerY()
            canvas.drawLine(nx, ny + s * 0.18f, nx, ny - s * 0.26f, note)
            canvas.drawLine(nx, ny - s * 0.26f, nx + s * 0.16f, ny - s * 0.16f, note)
            note.style = Paint.Style.FILL
            canvas.drawCircle(nx - s * 0.1f, ny + s * 0.2f, s * 0.11f, note)
            note.style = Paint.Style.STROKE
        }
        val g = h * 0.15f; val gap = h * 0.62f; val cy = h / 2f
        val cxs = floatArrayOf(w - h * 0.45f - gap * 2f, w - h * 0.45f - gap, w - h * 0.45f)
        if (active) { selPaint.color = Theme.ink(55); canvas.drawCircle(cxs[sel], cy, h * 0.3f, selPaint) }
        icon.color = Theme.ink(if (active) 255 else 230)
        icon.setShadowLayer(3f * dp, 0f, 1f * dp, shadow)
        drawSkip(canvas, cxs[0], cy, g, -1)
        val cx = cxs[1]
        if (playing) {
            canvas.drawRoundRect(cx - g * 0.75f, cy - g, cx - g * 0.2f, cy + g, g * 0.15f, g * 0.15f, icon)
            canvas.drawRoundRect(cx + g * 0.2f, cy - g, cx + g * 0.75f, cy + g, g * 0.15f, g * 0.15f, icon)
        } else {
            path.reset(); path.moveTo(cx - g * 0.6f, cy - g); path.lineTo(cx + g * 0.95f, cy); path.lineTo(cx - g * 0.6f, cy + g); path.close()
            canvas.drawPath(path, icon)
        }
        drawSkip(canvas, cxs[2], cy, g, 1)
        val tx = r.right + h * 0.2f
        val tt = tp(h * 0.27f, if (active) 255 else 240, bold = true, ink = Theme.ink)
        val at = tp(h * 0.22f, if (active) 215 else 185, ink = Theme.ink)
        for (p in arrayOf(tt, at)) p.setShadowLayer(3f * dp, 0f, 1f * dp, shadow)
        val maxW = w * 0.36f - tx
        val t = TextUtils.ellipsize(title.ifEmpty { "Nothing played yet" }, tt, maxW, TextUtils.TruncateAt.END).toString()
        val a = TextUtils.ellipsize(artist, at, maxW, TextUtils.TruncateAt.END).toString()
        if (a.isEmpty()) canvas.drawText(t, tx, h / 2f - (tt.descent() + tt.ascent()) / 2f, tt)
        else { canvas.drawText(t, tx, h * 0.46f, tt); canvas.drawText(a, tx, h * 0.76f, at) }
        val textEnd = tx + maxOf(tt.measureText(t), at.measureText(a))
        val wx0 = maxOf(textEnd + h * 0.3f, w * 0.26f)
        val wx1 = cxs[0] - h * 0.55f
        if (wx1 - wx0 > h * 0.8f) drawWave(canvas, wx0, wx1, cy, h * 0.42f)
    }

    /** Three soft strands (like Siri's wave) whose height follows the music; flat while paused. */
    private fun drawWave(canvas: Canvas, x0: Float, x1: Float, cy: Float, amp: Float) {
        val secs = (System.nanoTime() - t0) / 1e9f
        val lv = if (playing) levels?.invoke() else null
        if (!playing) env.fill(0f)
        for (i in env.indices) {
            val target = when {
                !playing -> 0f
                lv != null && lv.isNotEmpty() -> Math.sqrt(lv[minOf(lv.size - 1, i * lv.size / env.size)].toDouble()).toFloat().coerceAtMost(1f)
                else -> {   // no audio access: a believable pulse around 120 bpm
                    val beat = Math.pow(maxOf(0.0, Math.cos(secs * Math.PI * 2.0)), 6.0).toFloat()
                    (0.28f + 0.22f * beat) * (0.75f + 0.25f * Math.sin((secs * 1.7 + i * 0.9)).toFloat())
                }
            }
            env[i] = if (target > env[i]) env[i] + (target - env[i]) * 0.55f else env[i] + (target - env[i]) * 0.12f
        }
        phase = secs * 5.5f
        val n = 64
        val span = x1 - x0
        val strands = arrayOf(floatArrayOf(1.0f, 1.6f, 0f, 235f), floatArrayOf(0.7f, 2.3f, 1.7f, 140f), floatArrayOf(0.45f, 3.1f, 3.4f, 80f))
        wave.setShadowLayer(3f * dp, 0f, 1f * dp, Color.argb(90, 0, 0, 0))
        for (st in strands) {
            wave.color = Theme.ink(st[3].toInt()); wave.strokeWidth = maxOf(1.5f, amp * (if (st[3] > 200f) 0.11f else 0.07f))
            wpath.reset()
            for (k in 0..n) {
                val u = k / n.toFloat()
                val e = env[minOf(env.size - 1, (u * env.size).toInt())]
                val taper = Math.sin(u * Math.PI).toFloat()
                val y = cy - amp * st[0] * maxOf(0.04f, e) * taper * Math.sin((u * st[1] * 2.0 * Math.PI) + phase + st[2]).toFloat()
                val x = x0 + span * u
                if (k == 0) wpath.moveTo(x, y) else wpath.lineTo(x, y)
            }
            canvas.drawPath(wpath, wave)
        }
    }

    /** Previous (dir -1) or next (dir 1): two triangles and a bar. */
    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, g: Float, dir: Int) {
        val d = dir.toFloat()
        path.reset()
        path.moveTo(cx - d * g * 0.9f, cy - g * 0.8f); path.lineTo(cx + d * g * 0.05f, cy); path.lineTo(cx - d * g * 0.9f, cy + g * 0.8f); path.close()
        path.moveTo(cx - d * g * 0.05f, cy - g * 0.8f); path.lineTo(cx + d * g * 0.9f, cy); path.lineTo(cx - d * g * 0.05f, cy + g * 0.8f); path.close()
        canvas.drawPath(path, icon)
        val bx = cx + d * g * 0.9f
        canvas.drawRoundRect(minOf(bx, bx + d * g * 0.28f), cy - g * 0.8f, maxOf(bx, bx + d * g * 0.28f), cy + g * 0.8f, g * 0.08f, g * 0.08f, icon)
    }
}

/**
 * A famous line and who said it, bottom right of Home: the line in light italic, right-aligned, up to
 * three lines, "— who" underneath. [next] cross-fades to another one, in a random order where the same
 * person, or the same game or film, never comes twice in a row.
 */
class QuoteView(context: Context) : android.view.View(context) {
    data class Quote(val line: String, val who: String, val from: String)
    private val dp = resources.displayMetrics.density
    private var quotes: List<Quote> = emptyList()
    private var order = IntArray(0)
    private var idx = 0
    private var cur: Quote? = null
    private var layout: android.text.StaticLayout? = null
    private val qp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    var textPx = 26f
        set(v) { field = v; layout = null; invalidate() }

    init { isFocusable = false; isClickable = false }

    fun load(list: List<Quote>) {
        quotes = list
        order = shuffled(null)
        idx = 0
        cur = list.getOrNull(order.firstOrNull() ?: -1); layout = null; invalidate()
    }

    private fun shuffled(after: Quote?): IntArray {
        val o = quotes.indices.shuffled().toMutableList()
        fun clash(a: Quote?, b: Quote) = a != null && (a.who == b.who || (a.from.isNotEmpty() && a.from == b.from))
        for (i in o.indices) {
            val prev = if (i == 0) after else quotes[o[i - 1]]
            if (clash(prev, quotes[o[i]])) {
                val j = (i + 1 until o.size).firstOrNull { !clash(prev, quotes[o[it]]) }
                if (j != null) { val t = o[i]; o[i] = o[j]; o[j] = t }
            }
        }
        return o.toIntArray()
    }

    /** The next quote. [show] false (quote hidden): swap it without fading in. */
    fun next(show: Boolean = true) {
        if (quotes.size < 2) return
        fun advance() {
            idx++
            if (idx >= order.size) { order = shuffled(cur); idx = 0 }
            cur = quotes[order[idx]]; layout = null; invalidate()
        }
        if (!show) { advance(); return }
        animate().alpha(0f).setDuration(500).withEndAction {
            advance()
            animate().alpha(1f).setDuration(700).start()
        }.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); layout = null }

    override fun onDraw(canvas: Canvas) {
        val q = cur ?: return
        val shadow = Color.argb(120, 0, 0, 0)
        qp.color = Theme.ink(235); qp.textSize = textPx
        qp.typeface = Typeface.create(Typeface.create("sans-serif-light", Typeface.NORMAL), Typeface.ITALIC)
        qp.setShadowLayer(4f * dp, 0f, 1f * dp, shadow)
        bp.color = Theme.ink(175); bp.textSize = textPx * 0.72f; bp.textAlign = Paint.Align.RIGHT
        bp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        bp.setShadowLayer(3f * dp, 0f, 1f * dp, shadow)
        val w = width
        val text = "“" + q.line + "”"
        val l = layout ?: android.text.StaticLayout.Builder.obtain(text, 0, text.length, qp, w)
            .setAlignment(android.text.Layout.Alignment.ALIGN_OPPOSITE)
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.08f).build().also { layout = it }
        val byBase = height - bp.descent()
        val by = "— " + q.who
        canvas.drawText(TextUtils.ellipsize(by, bp, w.toFloat(), TextUtils.TruncateAt.END).toString(), w.toFloat(), byBase, bp)
        val top = byBase + bp.ascent() - textPx * 0.45f - l.height
        canvas.save(); canvas.translate(0f, top); l.draw(canvas); canvas.restore()
    }
}
