package app.vitrine.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * Depth effect, like the iOS lock screen: the wallpaper's subject (or the hills in front of the sky)
 * drawn again *above* the clock. [build] takes the on-screen wallpaper and a mask (white with alpha =
 * subject, same framing as the photo) and keeps only the subject pixels of the top band where the
 * clock lives. The mask can be made on a computer (rembg) for any photo.
 */
class DepthView(context: Context) : View(context) {
    private var fg: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** Where the subject is, at half resolution (true = subject, soft edges included), for fast placement tests. */
    private var hits: BooleanArray? = null
    private var gw = 0
    private var gh = 0
    val hasMask get() = fg != null
    /** Screen pixels per bitmap pixel: the cut-out is kept at the wallpaper's resolution and drawn scaled. */
    private var drawScale = 1f

    /** True when the subject (even its soft edge) is at this window point. */
    fun hitAt(x: Int, y: Int): Boolean {
        val g = hits ?: return false
        val gx = (x / drawScale).toInt() shr 1; val gy = (y / drawScale).toInt() shr 1
        if (gx < 0 || gy < 0 || gx >= gw || gy >= gh) return false
        return g[gy * gw + gx]
    }

    /** Returns false when there is no usable mask. The mask bitmap is recycled. */
    fun build(wall: Bitmap, mask: Bitmap?, bandH: Int, screenWidth: Int = wall.width): Boolean {
        fg?.recycle(); fg = null; hits = null
        if (mask == null) return false
        return try {
            val w = wall.width; val h = wall.height
            drawScale = screenWidth / w.toFloat()
            val band = minOf((bandH / drawScale).toInt(), h)
            val out = Bitmap.createBitmap(w, band, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(wall, Rect(0, 0, w, band), Rect(0, 0, w, band), null)
            // Same centre-crop as the wallpaper, so the mask lines up with the photo exactly.
            val s = maxOf(w / mask.width.toFloat(), h / mask.height.toFloat())
            val dw = mask.width * s; val dh = mask.height * s
            val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            c.drawBitmap(mask, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            mask.recycle()
            fg = out
            gw = maxOf(1, w / 2); gh = maxOf(1, band / 2)
            val half = Bitmap.createScaledBitmap(out, gw, gh, true)
            val px = IntArray(gw * gh); half.getPixels(px, 0, gw, 0, 0, gw, gh); half.recycle()
            hits = BooleanArray(px.size) { (px[it] ushr 24) > 40 }
            invalidate()
            true
        } catch (_: Throwable) { false }
    }

    fun clear() { fg?.recycle(); fg = null; hits = null; visibility = GONE }

    override fun onDraw(canvas: Canvas) {
        val b = fg ?: return
        canvas.save(); canvas.scale(drawScale, drawScale); canvas.drawBitmap(b, 0f, 0f, paint); canvas.restore()
    }
}

/** A small read-only status in the top bar (RAM, storage): glyph and value on the wallpaper, no box. */
class StatPill(context: Context, source: GlassSource, private val glyph: Int, textPx: Float, widest: String) : GlassView(context, source) {
    var value: String = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    private val tp = labelPaint(textPx, dp, Paint.Align.LEFT)
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val r = RectF()
    /** Fixed width from the widest expected text, so the bar never jiggles as values change. */
    val preferredWidth: Int = (tp.measureText(widest) + textPx * 3.6f).toInt()

    init { isFocusable = false; isClickable = false; isLongClickable = false; outlineOnly = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { radius = h / 2f; super.onSizeChanged(w, h, oldw, oldh) }

    /** No glass and no outline: just the glyph and the value, with a soft shadow so they read on any photo. */
    override fun onDraw(canvas: Canvas) { drawContent(canvas) }

    override fun drawContent(canvas: Canvas) {
        val h = height.toFloat()
        val s = h * 0.17f
        val cx = h * 0.55f; val cy = h / 2f
        gp.color = Theme.ink(225); gp.strokeWidth = maxOf(1.4f, h * 0.032f)
        gp.setShadowLayer(3f * dp, 0f, 1f * dp, Color.argb(100, 0, 0, 0))
        when (glyph) {
            RAM -> {                       // memory module: a bar with pins
                r.set(cx - s * 1.3f, cy - s * 0.65f, cx + s * 1.3f, cy + s * 0.65f)
                canvas.drawRoundRect(r, s * 0.2f, s * 0.2f, gp)
                for (k in -2..2) canvas.drawLine(cx + k * s * 0.5f, cy + s * 0.65f, cx + k * s * 0.5f, cy + s * 1.05f, gp)
            }
            else -> {                      // drive: rounded box with a status dot
                r.set(cx - s * 1.3f, cy - s * 0.85f, cx + s * 1.3f, cy + s * 0.85f)
                canvas.drawRoundRect(r, s * 0.3f, s * 0.3f, gp)
                canvas.drawLine(cx - s * 1.3f, cy + s * 0.15f, cx + s * 1.3f, cy + s * 0.15f, gp)
                gp.style = Paint.Style.FILL; canvas.drawCircle(cx + s * 0.8f, cy + s * 0.5f, s * 0.15f, gp); gp.style = Paint.Style.STROKE
            }
        }
        tp.color = Theme.ink(235)
        val y = h / 2f - (tp.descent() + tp.ascent()) / 2f
        canvas.drawText(value, h * 0.98f, y, tp)
    }

    companion object { const val RAM = 0; const val STORAGE = 1 }
}

/**
 * The iOS 26 lock-screen clock. Tall, narrow figures in Antonio Light (SIL Open Font License,
 * github.com/googlefonts/antonioFont; its colon already has Apple's squared dots), nothing stretched.
 * Four layers:
 *  1. fill: frosted glass, the heavily blurred wallpaper behind each digit, brightened (no white wash),
 *     so the photo's colours run smoothly through the numbers as on the iOS lock screen;
 *  2. blend: a soft diagonal gradient between the photo's two strongest colours (GlassSource.palette),
 *     the one higher in the photo at the top right, so the digits glow even over dull parts of the picture;
 *  3. sheen: a faint diagonal light at the top-left;
 *  4. rim: a thin white edge round every digit, which is what makes it read as glass rather than tinted text.
 * [scale] resizes the whole clock (depth placement, see MainActivity.decideDepth).
 */
class ElongatedClock(context: Context, private val source: GlassSource, val basePx: Float) : View(context) {
    /** Called on the main thread whenever the shown time changes. */
    var onTimeChanged: (() -> Unit)? = null
    val currentText: String get() = text
    var scale = 1f
        set(v) { field = v; applySize(); requestLayout(); invalidate() }
    /** Height of the figures at scale 1. */
    val baseDigitsHeight: Float

    private val tint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blend = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rim = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    // A second, much softer blur for the digits: a quarter of the glass bitmap, smoothed back up by the shader.
    private val soft = Bitmap.createScaledBitmap(source.blurred, maxOf(2, source.blurred.width / 4), maxOf(2, source.blurred.height / 4), true)
    private val shader = android.graphics.BitmapShader(soft, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
    private val m = android.graphics.Matrix()
    private val loc = IntArray(2)
    private val digits = Rect()
    private var text = ""
    private var sheenFor = -1f
    private val tick = object : Runnable {
        override fun run() {
            update()
            val now = System.currentTimeMillis()
            postDelayed(this, 60_000L - now % 60_000L + 50)
        }
    }

    init {
        val face = try { context.resources.getFont(R.font.clock_antonio) }
                   catch (e: Exception) { android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL) }
        for (p in arrayOf(tint, blend, sheen, rim)) { p.typeface = face; p.letterSpacing = -0.02f }
        // Measured on the iOS screenshot: the digits are the sky behind them about 1.55x brighter and a
        // little more saturated, with almost no white added.
        val cm = android.graphics.ColorMatrix().apply { setSaturation(1.15f) }
        // Darker photos still get a lift so the figures stay readable (on near-black: light grey glass).
        val lift = 6f + ((0.35f - source.luminance).coerceAtLeast(0f) * 380f)
        cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            1.55f, 0f, 0f, 0f, lift,  0f, 1.55f, 0f, 0f, lift,  0f, 0f, 1.55f, 0f, lift,  0f, 0f, 0f, 1f, 0f)))
        tint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        tint.shader = shader
        tint.alpha = 235
        rim.style = Paint.Style.STROKE
        rim.strokeJoin = Paint.Join.ROUND
        rim.color = Color.argb(120, 255, 255, 255)
        applySize()
        baseDigitsHeight = digits.height().toFloat()
        update()
    }

    private fun applySize() {
        val s = basePx * scale
        tint.textSize = s; blend.textSize = s; sheen.textSize = s; rim.textSize = s
        rim.strokeWidth = maxOf(1.5f, s * 0.0045f)
        tint.getTextBounds("0123456789", 0, 10, digits)
        sheenFor = -1f
    }

    private fun update() {
        val f = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        val t = android.text.format.DateFormat.format(f, System.currentTimeMillis()).toString()
        if (t == text) return
        val wider = tint.measureText(t) > tint.measureText(text)
        text = t
        if (wider) requestLayout()
        invalidate()
        onTimeChanged?.invoke()
    }

    /** Width of [s] at scale 1. */
    fun baseWidth(s: String): Float { val p = android.text.TextPaint(tint); p.textSize = basePx; return p.measureText(s) }

    /** Gap between the top of this view and the top of the digits, at [s]. */
    fun digitTopOffset(s: Float) = basePx * s * 0.06f

    /**
     * Points on the ink of each digit of [t] at scale 1, relative to (centre of the text, top of the digits):
     * per digit its xs, ys and whether each point is in the digit's top half. Used to place the clock so
     * the photo's subject overlaps the digits without hiding them.
     */
    fun inkSamples(t: String = text): List<Triple<FloatArray, FloatArray, BooleanArray>> {
        val p = android.text.TextPaint(tint); p.shader = null; p.colorFilter = null; p.textSize = basePx
        val b = Rect(); p.getTextBounds("0123456789", 0, 10, b)
        val dh = b.height().toFloat(); val total = p.measureText(t)
        val step = maxOf(3f, basePx / 55f)
        val out = ArrayList<Triple<FloatArray, FloatArray, BooleanArray>>()
        val path = Path(); val reg = android.graphics.Region(); val clip = android.graphics.Region(); val rb = RectF()
        for (i in t.indices) {
            if (!t[i].isDigit()) continue
            val x0 = p.measureText(t, 0, i) - total / 2f
            path.reset(); p.getTextPath(t, i, i + 1, x0, -b.top.toFloat(), path)       // digit top at y = 0
            path.computeBounds(rb, true)
            clip.set(rb.left.toInt() - 1, rb.top.toInt() - 1, rb.right.toInt() + 1, rb.bottom.toInt() + 1)
            reg.setPath(path, clip)
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val tops = ArrayList<Boolean>()
            var y = rb.top + step / 2f
            while (y < rb.bottom) {
                var x = rb.left + step / 2f
                while (x < rb.right) {
                    if (reg.contains(x.toInt(), y.toInt())) { xs.add(x); ys.add(y); tops.add(y < dh * 0.5f) }
                    x += step
                }
                y += step
            }
            out.add(Triple(xs.toFloatArray(), ys.toFloatArray(), tops.toBooleanArray()))
        }
        return out
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(tick); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    private val h get() = digits.height().toFloat()
    private val pad get() = tint.textSize * 0.06f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = tint.measureText("00:00") + 2 * pad
        setMeasuredDimension(w.toInt(), (h + 2 * pad).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val top = pad
        val bottom = pad + h
        val baseline = bottom - digits.bottom
        val x = (width - tint.measureText(text)) / 2f
        // The glass shader is in screen space: undo this view's position.
        getLocationInWindow(loc)
        val fx = source.factor.toFloat() * source.blurred.width / soft.width
        val fy = source.factor.toFloat() * source.blurred.height / soft.height
        m.setScale(fx, fy); m.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
        shader.setLocalMatrix(m)
        if (sheenFor != width.toFloat() * 10000f + h) {
            // CSS linear-gradient(160deg): mostly top to bottom, leaning right.
            val dx = 0.342f; val dy = 0.940f
            val len = Math.abs(width * dx) + Math.abs(h * dy)
            val cx = width / 2f; val cy = (top + bottom) / 2f
            sheen.shader = android.graphics.LinearGradient(
                cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f,
                intArrayOf(Color.argb(55, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(22, 255, 255, 255)),
                floatArrayOf(0f, 0.38f, 0.70f, 1f), android.graphics.Shader.TileMode.CLAMP)
            // The colour blend runs from bottom left (lower colour) to top right (upper colour) of the digits.
            val tw = tint.measureText("00:00"); val x0 = (width - tw) / 2f
            val gx = 0.45f / tw; val gy = -0.55f / h
            val n2 = gx * gx + gy * gy
            blend.shader = android.graphics.LinearGradient(x0, bottom, x0 + gx / n2, bottom + gy / n2,
                source.paletteBottom, source.paletteTop, android.graphics.Shader.TileMode.CLAMP)
            blend.alpha = 130
            sheenFor = width.toFloat() * 10000f + h
        }
        canvas.drawText(text, x, baseline, tint)
        canvas.drawText(text, x, baseline, blend)
        canvas.drawText(text, x, baseline, sheen)
        canvas.drawText(text, x, baseline, rim)
    }

    /** The figures' bounding box in window coordinates. */
    fun glyphRectInWindow(): Rect {
        val l = IntArray(2); getLocationInWindow(l)
        val tw = tint.measureText(text)
        val x = l[0] + (width - tw) / 2f
        val top = l[1] + pad
        return Rect(x.toInt(), top.toInt(), (x + tw).toInt(), (top + h).toInt())
    }
}

/** Small weather symbol for the date line. */
class WeatherIconView(context: Context) : View(context) {
    var code = -1; var isDay = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
    override fun onDraw(canvas: Canvas) {
        if (code < 0) return
        WeatherGlyph.draw(canvas, code, isDay, width / 2f, height / 2f, minOf(width, height) * 0.9f, p)
    }
}
