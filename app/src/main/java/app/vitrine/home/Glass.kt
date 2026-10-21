package app.vitrine.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import java.io.File
import java.io.FileOutputStream

/**
 * Pre-blurred, down-scaled copy of the on-screen wallpaper with "vibrancy" (more saturation and a
 * little extra brightness), which is what makes glass read as clear rather than grey.
 * Also measures the wallpaper's average brightness so the UI can adapt its scrims and tints.
 */
class GlassSource(screen: Bitmap, val factor: Int = 8) {
    val blurred: Bitmap
    /** Average luminance 0..1 of the wallpaper. */
    val luminance: Float
    /** Average luminance 0..1 of the top band (behind the clock and chips). */
    val topLuminance: Float

    init {
        val w = maxOf(1, screen.width / factor)
        val h = maxOf(1, screen.height / factor)
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(screen, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0f; var top = 0f; var topN = 0
        for (i in px.indices) {
            val c = px[i]
            val l = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
            sum += l
            if (i / w < h / 5) { top += l; topN++ }
        }
        luminance = sum / px.size
        topLuminance = if (topN > 0) top / topN else luminance
        val tmp = IntArray(w * h)
        repeat(3) { boxPass(px, tmp, w, h, 4, true); boxPass(tmp, px, w, h, 4, false) }
        small.setPixels(px, 0, w, 0, 0, w, h)
        val vib = ColorMatrix().apply { setSaturation(1.65f) }
        vib.postConcat(ColorMatrix(floatArrayOf(
            1.10f, 0f, 0f, 0f, 8f,
            0f, 1.10f, 0f, 0f, 8f,
            0f, 0f, 1.10f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f)))
        blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(blurred).drawBitmap(small, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(vib) })
        small.recycle()
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun idx(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            for (i in -r..r) {
                val c = src[idx(i)]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[idx(i + r + 1)]
                val rem = src[idx(i - r)]
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}

/**
 * Turns an app's own installed icon into a white glyph (like Android's themed icons): pixels that
 * differ from the icon's background become white, the background clears, and the result is cropped
 * to the glyph. Results are cached on disk per app version so Home opens instantly.
 * Returns null when the icon doesn't separate cleanly; callers then show the colour icon.
 */
object Mono {
    private var S = 160

    /** `size`: working resolution (160 for dock/tiles, 320 for the large All apps showcase). */
    /** true: keep the logo's own colours (background removed) instead of making it white. */
    private var keepColour = false

    @Synchronized
    fun make(d: Drawable, cache: File?, size: Int = 160, colour: Boolean = false): Bitmap? {
        S = size; keepColour = colour
        try {
            if (cache != null) {
                if (File(cache.path + ".none").exists()) return null
                if (cache.exists()) BitmapFactory.decodeFile(cache.path)?.let { return it }
            }
            val b = build(d)
            if (cache != null) {
                cache.parentFile?.mkdirs()
                if (b == null) File(cache.path + ".none").createNewFile()
                else FileOutputStream(cache).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            return b
        } catch (_: Throwable) { return null }
    }

    private class Ring(val samples: IntArray, val clear: Int)

    private fun ring(px: IntArray, inset: Int): Ring {
        val seen = HashSet<Int>(); val out = ArrayList<Int>(); var clear = 0
        for (k in 0 until 24) {
            val t = inset + (S - 2 * inset) * k / 23
            for (p in intArrayOf(t + inset * S, t + (S - 1 - inset) * S, inset + t * S, (S - 1 - inset) + t * S)) {
                val v = px[p]
                if ((v ushr 24) < 60) { clear++; continue }
                val q = ((v shr 19) and 0x1F shl 10) or ((v shr 11) and 0x1F shl 5) or ((v shr 3) and 0x1F)
                if (seen.add(q) && out.size < 32) out.add(v)
            }
        }
        return Ring(out.toIntArray(), clear)
    }

    private fun colourMode(px: IntArray, samples: IntArray): FloatArray {
        val alpha = FloatArray(S * S)
        val sr = IntArray(samples.size) { (samples[it] shr 16) and 0xFF }
        val sg = IntArray(samples.size) { (samples[it] shr 8) and 0xFF }
        val sb = IntArray(samples.size) { samples[it] and 0xFF }
        for (i in px.indices) {
            val v = px[i]; val a = (v ushr 24) / 255f
            if (a < 0.05f) continue
            val r = (v shr 16) and 0xFF; val g = (v shr 8) and 0xFF; val b = v and 0xFF
            var best = Int.MAX_VALUE
            for (k in sr.indices) {
                val dr = r - sr[k]; val dg = g - sg[k]; val db = b - sb[k]
                val dd = dr * dr + dg * dg + db * db
                if (dd < best) best = dd
            }
            val dist = Math.sqrt(best.toDouble()).toFloat() / 441f
            val t = ((dist - 0.16f) / 0.22f).coerceIn(0f, 1f)
            alpha[i] = a * t * t * (3 - 2 * t)
        }
        return alpha
    }

    /** Edge-rebuild radius: bigger when a small bitmap is being enlarged a lot (so its pixel steps melt away). */
    private var edgeR = 1

    private fun build(d: Drawable): Bitmap? {
        val src = minOf(d.intrinsicWidth, d.intrinsicHeight).let { if (it <= 0) S else it }
        val vector = d is android.graphics.drawable.AdaptiveIconDrawable || d is android.graphics.drawable.VectorDrawable
        val enlarge = S.toFloat() / src
        edgeR = if (vector) maxOf(1, S / 140) else (enlarge * 1.1f).toInt().coerceIn(maxOf(1, S / 110), S / 26)
        if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            val fg = d.foreground
            if (fg != null) {
                // Foreground is 108 dp with a 72 dp visible area: draw it so that area fills the canvas.
                val b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
                fg.setBounds(-S / 4, -S / 4, S + S / 4, S + S / 4); fg.draw(Canvas(b))
                val fpx = IntArray(S * S); b.getPixels(fpx, 0, S, 0, 0, S, S); b.recycle()
                crop(smooth(FloatArray(S * S) { (fpx[it] ushr 24) / 255f }), fpx)?.let { return it }
            }
        }
        val bm = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, S, S); d.draw(Canvas(bm))
        val px = IntArray(S * S); bm.getPixels(px, 0, S, 0, 0, S, S); bm.recycle()

        // Background from a ring near the edge; if that ring is mostly clear (icon drawn smaller than
        // its canvas, like a rounded tile with margins), look further in, on the tile itself.
        val outer = ring(px, S / 10)
        val inner = ring(px, S / 5)
        val attempts = ArrayList<FloatArray>()
        if (outer.clear <= 24 && outer.samples.isNotEmpty()) attempts.add(colourMode(px, outer.samples))
        if (inner.clear <= 24 && inner.samples.isNotEmpty()) attempts.add(colourMode(px, inner.samples))
        if (outer.clear > 48) attempts.add(FloatArray(S * S) { (px[it] ushr 24) / 255f })
        for (alpha in attempts) crop(smooth(alpha), px)?.let { return it }
        return null
    }

    /**
     * Rebuilds the glyph's edge: a small blur of the mask followed by a soft threshold. Upscaled bitmap
     * icons lose their stair-steps and every glyph gets the same crisp, anti-aliased outline.
     */
    private fun smooth(a: FloatArray): FloatArray {
        val r = edgeR
        val tmp = FloatArray(S * S); val out = FloatArray(S * S)
        val div = (2 * r + 1).toFloat()
        for (y in 0 until S) {               // horizontal box
            var sum = 0f
            for (k in -r..r) sum += a[y * S + k.coerceIn(0, S - 1)]
            for (x in 0 until S) {
                tmp[y * S + x] = sum / div
                sum += a[y * S + (x + r + 1).coerceIn(0, S - 1)] - a[y * S + (x - r).coerceIn(0, S - 1)]
            }
        }
        for (x in 0 until S) {               // vertical box
            var sum = 0f
            for (k in -r..r) sum += tmp[k.coerceIn(0, S - 1) * S + x]
            for (y in 0 until S) {
                out[y * S + x] = sum / div
                sum += tmp[(y + r + 1).coerceIn(0, S - 1) * S + x] - tmp[(y - r).coerceIn(0, S - 1) * S + x]
            }
        }
        // Hard threshold band keeps semi-transparent layers (e.g. a folder's tab) from turning grey.
        for (i in out.indices) {
            val t = ((out[i] - 0.40f) / 0.20f).coerceIn(0f, 1f)
            out[i] = t * t * (3 - 2 * t)
        }
        return out
    }

    /** Crops to the glyph, or null if the result is not a clean glyph (too much, too little, or a full square). */
    private fun crop(alpha: FloatArray, src: IntArray): Bitmap? {
        var minX = S; var minY = S; var maxX = -1; var maxY = -1; var on = 0
        for (y in 0 until S) for (x in 0 until S) if (alpha[y * S + x] > 0.5f) {
            on++; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val cover = on.toFloat() / (S * S)
        if (maxX < 0 || cover < 0.012f || cover > (if (keepColour) 0.70f else 0.48f)) return null
        val bw = maxX - minX + 1; val bh = maxY - minY + 1
        if (bw > S * 0.92f && bh > S * 0.92f) return null
        if (bh < S * 0.06f) return null
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val o = IntArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = (y + minY) * S + (x + minX)
            val a = (alpha[i] * 255f).toInt().coerceIn(0, 255)
            o[y * bw + x] = (a shl 24) or (if (keepColour) (src[i] and 0xFFFFFF) else 0xFFFFFF)
        }
        out.setPixels(o, 0, bw, 0, 0, bw, bh)
        return out
    }
}

/** An app's picture: the white glyph when one could be made, otherwise the colour icon. */
class IconArt(private val mono: Bitmap?, private val original: Drawable, private val themed: Boolean = false) {
    /** Themed fallback when no white glyph could be made: the icon in soft monochrome, matching the dock. */
    private val themeFilter by lazy {
        val cm = ColorMatrix().apply { setSaturation(0f) }
        cm.postConcat(ColorMatrix(floatArrayOf(
            0.55f, 0f, 0f, 0f, 112f,
            0f, 0.55f, 0f, 0f, 112f,
            0f, 0f, 0.55f, 0f, 112f,
            0f, 0f, 0f, 1f, 0f)))
        ColorMatrixColorFilter(cm)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    /**
     * Optical sizing: every glyph gets the same visual area, so a wide wordmark and a round logo look
     * equally big. `box` is the nominal glyph height.
     */
    fun draw(canvas: Canvas, cx: Float, cy: Float, box: Float, alpha: Int = 255) {
        if (mono != null) {
            val ar = mono.width.toFloat() / mono.height
            // Ink-weighted: a solid shape (YouTube) is drawn smaller, a thin outline (a line-drawn star)
            // larger, so they read as the same visual weight. Half-strength correction, clamped.
            val inkFactor = Math.sqrt((0.35f / ink).toDouble()).toFloat().coerceIn(0.78f, 1.32f)
            val area = box * box * 0.70f * inkFactor
            var h = Math.sqrt((area / ar).toDouble()).toFloat()
            var w = h * ar
            if (w > box * 2.6f) { w = box * 2.6f; h = w / ar }
            if (h > box * 1.12f) { h = box * 1.12f; w = h * ar }
            dst.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            paint.alpha = alpha
            canvas.drawBitmap(mono, null, dst, paint)
            if (ink < 0.24f) {
                // Hairline glyph: draw again slightly offset so its strokes thicken (~1 px) and brighten.
                val o = maxOf(0.6f, h * 0.012f)
                for ((dx, dy) in arrayOf(o to 0f, 0f to o, o to o)) {
                    dst.offset(dx, dy); canvas.drawBitmap(mono, null, dst, paint); dst.offset(-dx, -dy)
                }
            }
        } else {
            // Colour icon or banner at its real proportions; square bitmaps get softly rounded corners.
            val iw = original.intrinsicWidth.toFloat(); val ih = original.intrinsicHeight.toFloat()
            val ar = if (iw > 0 && ih > 0) iw / ih else 1f
            var h = box * 1.05f; var w = h * ar
            if (ar > 1.2f) { w = minOf(box * 2.6f, box * 1.05f * ar); h = w / ar }
            dst.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            val adaptive = original is android.graphics.drawable.AdaptiveIconDrawable
            canvas.save()
            if (!adaptive) {
                clip.reset(); val rr = minOf(w, h) * 0.20f
                clip.addRoundRect(dst, rr, rr, Path.Direction.CW); canvas.clipPath(clip)
            }
            original.setBounds(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt())
            original.alpha = alpha
            if (themed) original.colorFilter = themeFilter
            original.draw(canvas)
            if (themed) original.colorFilter = null
            canvas.restore()
        }
    }
    private val clip = Path()

    /** Fraction of the glyph's box that is ink (opaque), measured once. */
    private val ink: Float by lazy {
        val b = mono ?: return@lazy 0.35f
        try {
            val step = maxOf(1, maxOf(b.width, b.height) / 96)
            var n = 0; var on = 0
            var y = 0
            while (y < b.height) {
                var x = 0
                while (x < b.width) { n++; if ((b.getPixel(x, y) ushr 24) > 128) on++; x += step }
                y += step
            }
            if (n == 0) 0.35f else (on.toFloat() / n).coerceIn(0.05f, 0.95f)
        } catch (_: Exception) { 0.35f }
    }
}

/**
 * Light / dark appearance for glass surfaces (widgets, Settings panel, menus, cards). Things that sit
 * directly on the photo (clock, dock, header outlines) stay white in both modes.
 */
object Theme {
    var light = false
    /** Wallpaper brightness 0..1 (set at start) so light glass isn't glaring over a dark photo. */
    var wallLum = 0.35f
    /** Text and line colour on glass. */
    val ink: Int get() = if (light) Color.rgb(24, 26, 30) else Color.WHITE
    fun ink(a: Int): Int { val c = ink; return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)) }
    /** The opposite of ink: text on an ink-filled shape (today's date, switch knob). */
    val paper: Int get() = if (light) Color.WHITE else Color.rgb(20, 20, 24)
    val shadow: Int get() = if (light) Color.argb(0, 0, 0, 0) else Color.argb(90, 0, 0, 0)
    /** Resting glass for cards and panels. */
    fun card(darkTint: Int): Material = if (light) {
            val a = (135 + (wallLum - 0.2f) * 135f).toInt().coerceIn(135, 180)
            val g = if (wallLum < 0.3f) 236 else 246
            Material(Color.argb(a, g, g, g + 3), 200, 1.1f, 40)
        }
        else Material(darkTint, 120, 1.1f, 26)
    /** Focused row on a panel. */
    fun row(): Material = if (light) Material(Color.argb(210, 255, 255, 255), 255, 1.4f, 60)
        else Material(Color.argb(58, 255, 255, 255), 220, 1.4f, 50)
}

/** Glass material, the two levels from the spec. */
class Material(val tint: Int, val rim: Int, val rimWidthDp: Float, val sheen: Int)

/**
 * Liquid-glass slab: vibrant blurred backdrop, refracted edge band, tint, top sheen, bottom shade and a
 * light rim. On focus it lifts (scale + shadow), brightens, and a highlight sweeps across it once.
 */
open class GlassView(context: Context, private val source: GlassSource) : View(context) {
    protected val dp = resources.displayMetrics.density
    var radius = 26f * dp
    var rest = Material(Color.argb(22, 255, 255, 255), 130, 1.1f, 34)
    var focus = Material(Color.argb(56, 255, 255, 255), 255, 2.2f, 70)
    var lens = true
    var focusScale = 1.08f
    var liftDp = 22f
    /** Hairline only: no blur, tint or sheen; a thin border that brightens and thickens on focus. */
    var outlineOnly = false
        set(v) { field = v; if (v) { liftDp = 0f; outlineProvider = null }; invalidate() }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val hairRect = RectF()
    /** Draw no slab while idle (menu rows only show glass on the focused row). */
    var idleHidden = false
    var lit = false
        set(v) { field = v; styleFor(isFocused || v); invalidate() }
    protected val active get() = isFocused || lit

    private val rect = RectF()
    private val rimRect = RectF()
    private val lensRect = RectF()
    private val shader = BitmapShader(source.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val lensShader = BitmapShader(source.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = this@GlassView.shader }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = this@GlassView.lensShader; style = Paint.Style.STROKE
    }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepM = Matrix()
    private var sweepT = -1f
    private var sweepAnim: ValueAnimator? = null
    private val m = Matrix()
    private val lm = Matrix()
    private val loc = IntArray(2)

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
                outline.alpha = 0.5f
            }
        }
        setOnFocusChangeListener { _, f -> onFocus(f) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        sweepPaint.shader = LinearGradient(0f, 0f, w * 0.45f, h.toFloat(),
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        restyle()
        invalidateOutline()
    }

    fun restyle() { styleFor(active); invalidate() }

    private fun styleFor(f: Boolean) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val mat = if (f) focus else rest
        tintPaint.color = mat.tint
        sheenPaint.shader = LinearGradient(0f, 0f, 0f, h * 0.5f,
            Color.argb(mat.sheen, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        shadePaint.shader = LinearGradient(0f, h * 0.6f, 0f, h,
            Color.argb(0, 0, 0, 0), Color.argb(22, 0, 0, 0), Shader.TileMode.CLAMP)
        val ra = mat.rim
        rimPaint.strokeWidth = mat.rimWidthDp * dp
        rimPaint.shader = LinearGradient(0f, 0f, w * 0.45f, h,
            intArrayOf(Color.argb(ra, 255, 255, 255), Color.argb(ra / 5, 255, 255, 255), Color.argb(ra * 3 / 5, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val i = rimPaint.strokeWidth / 2f
        rimRect.set(i, i, w - i, h - i)
        val band = minOf(9f * dp, h / 6f)
        lensPaint.strokeWidth = band
        lensRect.set(band / 2f, band / 2f, w - band / 2f, h - band / 2f)
    }

    protected open fun onFocus(f: Boolean) {
        styleFor(f || lit)
        animate().scaleX(if (f) focusScale else 1f).scaleY(if (f) focusScale else 1f)
            .translationZ(if (f) liftDp * dp else 0f)
            .setInterpolator(DecelerateInterpolator(1.5f)).setDuration(if (f) 200 else 160).start()
        sweepAnim?.cancel()
        if (f && !idleHidden) {
            sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 750; startDelay = 80
                interpolator = DecelerateInterpolator()
                addUpdateListener { sweepT = it.animatedValue as Float; invalidate() }
                start()
            }
        } else sweepT = -1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (outlineOnly) {
            val sw = (if (active) 2.0f else 1.2f) * dp
            hairPaint.strokeWidth = sw
            hairPaint.alpha = if (active) 255 else 105
            hairRect.set(sw / 2f, sw / 2f, width - sw / 2f, height - sw / 2f)
            val rr = maxOf(0f, radius - sw / 2f)
            canvas.drawRoundRect(hairRect, rr, rr, hairPaint)
            drawContent(canvas)
            return
        }
        val slab = !(idleHidden && !active)
        if (slab) {
            val p = parent as? View
            var x = left + translationX
            var y = top + translationY
            if (p != null) { p.getLocationOnScreen(loc); x += loc[0]; y += loc[1] }
            val f = source.factor.toFloat()
            m.setScale(f, f); m.postTranslate(-x, -y)
            shader.setLocalMatrix(m)
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            if (lens) {
                lm.set(m); lm.postScale(1.08f, 1.08f, width / 2f, height / 2f)
                lensShader.setLocalMatrix(lm)
                val lr = maxOf(0f, radius - lensPaint.strokeWidth / 2f)
                canvas.drawRoundRect(lensRect, lr, lr, lensPaint)
            }
            canvas.drawRoundRect(rect, radius, radius, tintPaint)
            canvas.drawRoundRect(rect, radius, radius, sheenPaint)
            canvas.drawRoundRect(rect, radius, radius, shadePaint)
            if (sweepT in 0f..1f && active) {
                sweepM.setTranslate(-width * 0.9f + width * 1.8f * sweepT, 0f)
                sweepPaint.shader?.setLocalMatrix(sweepM)
                canvas.drawRoundRect(rect, radius, radius, sweepPaint)
            }
            val ri = radius - rimPaint.strokeWidth / 2f
            canvas.drawRoundRect(rimRect, ri, ri, rimPaint)
        }
        drawContent(canvas)
    }

    protected open fun drawContent(canvas: Canvas) {}
}

fun labelPaint(sizePx: Float, dp: Float, align: Paint.Align = Paint.Align.CENTER) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = sizePx
    textAlign = align
    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    setShadowLayer(3f * dp, 0f, 1f * dp, Color.argb(100, 0, 0, 0))
}

/** App tile: glyph and name on glass. Resting content is slightly dimmed so focus stands out. */
class AppTile(context: Context, source: GlassSource, val pkg: String, private val label: String,
              private val art: IconArt, labelPx: Float) : GlassView(context, source) {
    private val tp = labelPaint(labelPx, dp)
    init { contentDescription = label }

    override fun drawContent(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val a = if (active) 255 else 222
        art.draw(canvas, w / 2f, h * 0.40f, h * 0.25f, a)
        tp.alpha = a
        val text = TextUtils.ellipsize(label, tp, w - 32f * dp, TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, w / 2f, h * 0.80f, tp)
    }
}

/** A row in a glass menu: text on the left; glass appears only on the focused row. */
class MenuRow(context: Context, source: GlassSource, private val text: String, textPx: Float) : GlassView(context, source) {
    private val tp = labelPaint(textPx, dp, Paint.Align.LEFT).apply { color = Theme.ink; setShadowLayer(3f * dp, 0f, 1f * dp, Theme.shadow) }
    init {
        idleHidden = true; lens = false; focusScale = 1.02f; liftDp = 0f
        isLongClickable = false
    }
    override fun drawContent(canvas: Canvas) {
        tp.alpha = if (active) 255 else 210
        val y = height / 2f - (tp.descent() + tp.ascent()) / 2f
        canvas.drawText(text, 28f * dp, y, tp)
    }
}

/** Header chip: a small rounded-square glass button with a thin line glyph. */
class GlassIconButton(context: Context, source: GlassSource, private val glyph: Int) : GlassView(context, source) {
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val r = RectF()

    init { lens = false; focusScale = 1.12f; liftDp = 12f; isLongClickable = false }

    /** A system-provided icon to show instead of the drawn glyph (tinted white). */
    var systemIcon: Drawable? = null
        set(v) { field = v?.mutate()?.apply { setTint(Color.WHITE) }; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = minOf(w, h) * 0.30f
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun drawContent(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val s = minOf(width, height) * 0.17f
        gp.strokeWidth = maxOf(1.5f, minOf(width, height) * 0.034f)
        gp.alpha = if (active) 255 else 220
        systemIcon?.let { ic ->
            val half = (s * 1.35f).toInt()
            ic.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            ic.alpha = gp.alpha
            ic.draw(canvas)
            return
        }
        when (glyph) {
            PICTURE -> {
                r.set(cx - s * 1.25f, cy - s, cx + s * 1.25f, cy + s)
                canvas.drawRoundRect(r, s * 0.3f, s * 0.3f, gp)
                path.reset()
                path.moveTo(cx - s * 1.05f, cy + s * 0.7f)
                path.lineTo(cx - s * 0.3f, cy - s * 0.05f)
                path.lineTo(cx + s * 0.25f, cy + s * 0.45f)
                path.lineTo(cx + s * 0.6f, cy + s * 0.15f)
                path.lineTo(cx + s * 1.05f, cy + s * 0.6f)
                canvas.drawPath(path, gp)
                canvas.drawCircle(cx + s * 0.55f, cy - s * 0.4f, s * 0.15f, gp)
            }
            WIFI -> {
                val by = cy + s * 0.85f
                for (k in 1..3) {
                    val rr = s * 0.55f * k
                    r.set(cx - rr, by - rr, cx + rr, by + rr)
                    canvas.drawArc(r, 225f, 90f, false, gp)
                }
                gp.style = Paint.Style.FILL
                canvas.drawCircle(cx, by - s * 0.05f, s * 0.17f, gp)
                gp.style = Paint.Style.STROKE
            }
            DEVICES -> { // headphones: Bluetooth accessories
                r.set(cx - s * 1.05f, cy - s * 1.0f, cx + s * 1.05f, cy + s * 1.1f)
                canvas.drawArc(r, 180f, 180f, false, gp)
                r.set(cx - s * 1.15f, cy + s * 0.05f, cx - s * 0.55f, cy + s * 1.05f)
                canvas.drawRoundRect(r, s * 0.25f, s * 0.25f, gp)
                r.set(cx + s * 0.55f, cy + s * 0.05f, cx + s * 1.15f, cy + s * 1.05f)
                canvas.drawRoundRect(r, s * 0.25f, s * 0.25f, gp)
            }
            THEME -> {
                if (Theme.light) { // sun: you're in light mode
                    canvas.drawCircle(cx, cy, s * 0.45f, gp)
                    for (k in 0 until 8) {
                        val a = Math.PI * k / 4
                        canvas.drawLine(cx + (s * 0.75f * Math.cos(a)).toFloat(), cy + (s * 0.75f * Math.sin(a)).toFloat(),
                            cx + (s * 1.05f * Math.cos(a)).toFloat(), cy + (s * 1.05f * Math.sin(a)).toFloat(), gp)
                    }
                } else { // crescent moon: you're in dark mode
                    path.reset(); path.addCircle(cx, cy, s * 0.95f, Path.Direction.CW)
                    val cut = Path(); cut.addCircle(cx + s * 0.55f, cy - s * 0.4f, s * 0.8f, Path.Direction.CW)
                    path.op(cut, Path.Op.DIFFERENCE)
                    canvas.drawPath(path, gp)
                }
            }
            INPUTS -> {
                r.set(cx - s * 1.25f, cy - s * 0.95f, cx + s * 1.25f, cy + s * 0.65f)
                canvas.drawRoundRect(r, s * 0.25f, s * 0.25f, gp)
                canvas.drawLine(cx - s * 0.55f, cy + s * 1.1f, cx + s * 0.55f, cy + s * 1.1f, gp)
            }
            else -> {
                val ro = s * 1.1f; val ri = s * 0.42f
                canvas.drawCircle(cx, cy, ri, gp)
                path.reset()
                val teeth = 8
                for (k in 0 until teeth * 4) {
                    val a = Math.PI * 2 * k / (teeth * 4)
                    val rr = if ((k / 2) % 2 == 0) ro else ro * 0.8f
                    val px = cx + (rr * Math.cos(a)).toFloat(); val py = cy + (rr * Math.sin(a)).toFloat()
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, gp)
            }
        }
    }

    companion object { const val PICTURE = 0; const val INPUTS = 1; const val SETTINGS = 2; const val WIFI = 3; const val DEVICES = 4; const val THEME = 5 }
}

/** Dock entry: glyph and name; the gliding bubble behind it is the glass. */
class DockItem(context: Context, val pkg: String, private val label: String, private val art: IconArt,
               labelPx: Float) : View(context) {
    private val dp = resources.displayMetrics.density
    private val tp = labelPaint(labelPx, dp)
    /** Icons-only dock: name shows only on the focused app, icon a little larger. */
    var bare = false
        set(v) { field = v; val sc = if (v && isFocused) 1.16f else 1f; scaleX = sc; scaleY = sc; invalidate() }

    init {
        isFocusable = true; isClickable = true; isLongClickable = true
        contentDescription = label
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (bare) {
            // Icons only: centred, no name. Focus = full white + grown (scale animates in onFocusChanged).
            art.draw(canvas, w / 2f, h * 0.44f, h * 0.30f, if (isFocused) 255 else 165)
            return
        }
        val a = if (isFocused) 255 else 215
        art.draw(canvas, w / 2f, h * 0.40f, h * 0.25f, a)
        tp.alpha = a
        val text = TextUtils.ellipsize(label, tp, w - 14f * dp, TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, w / 2f, h * 0.80f, tp)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val sc = if (bare && gainFocus) 1.16f else 1f
        animate().scaleX(sc).scaleY(sc).setInterpolator(DecelerateInterpolator(1.6f)).setDuration(200).start()
        invalidate()
    }
}

/**
 * The focus "ribbon of light" under the icons-only dock. Full-dock-size overlay that draws:
 *  - a soft pool of the app's accent colour lighting the base of the focused icon,
 *  - an elliptical accent-coloured halo, and a white-hot rounded core.
 * Motion: the leading edge moves first and the trailing edge catches up (liquid stretch), the colour
 * cross-fades between apps, the halo breathes while resting, it springs open on arrival and flares on press.
 */
class FocusDash(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    var baseLen = 64f * dp
    var coreH = 5f * dp
    /** Where the dash sits, measured from the bottom of the view. */
    var lineFromBottom = 34f * dp
    /** Vertical centre of the icons, as a fraction of height (for the light pool). */
    var iconCenterFrac = 0.44f

    /** The dock row the dash is under (multi-row dock). bandH 0 = whole view. */
    private var bandTop = 0f; private var bandH = 0f
    private var l = 0f; private var r = 0f
    private var color = Color.WHITE
    private var grow = 1f; private var flash = 0f; private var breath = 0f; private var activeA = 1f
    private var placed = false
    private val anims = HashMap<String, ValueAnimator>()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val poolPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val rr = RectF()
    // Performance: no endless "breathing" animation (it redrew the dash 60 times a second forever).
    // The glow shaders are unit-sized, built once per colour, and positioned with a matrix.
    private var shaderColor = 0
    private var poolShader: Shader? = null
    private var glowShader: Shader? = null
    private var coreShader: Shader? = null

    override fun onDetachedFromWindow() { anims.values.forEach { it.cancel() }; super.onDetachedFromWindow() }

    private fun shadersFor(c: Int) {
        if (poolShader != null && c == shaderColor) return
        shaderColor = c
        val cr = Color.red(c); val cg = Color.green(c); val cb = Color.blue(c)
        poolShader = android.graphics.RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(70, cr, cg, cb), Color.argb(0, cr, cg, cb)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        glowShader = android.graphics.RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(190, cr, cg, cb), Color.argb(70, cr, cg, cb), Color.argb(0, cr, cg, cb)),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        val endC = Color.argb(235, (cr + 255 * 2) / 3, (cg + 255 * 2) / 3, (cb + 255 * 2) / 3)
        coreShader = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(endC, Color.WHITE, endC), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        poolPaint.shader = poolShader; glowPaint.shader = glowShader; corePaint.shader = coreShader
    }

    private fun run(key: String, from: Float, to: Float, ms: Long, delay: Long = 0, interp: android.animation.TimeInterpolator, set: (Float) -> Unit) {
        anims[key]?.cancel()
        anims[key] = ValueAnimator.ofFloat(from, to).apply {
            duration = ms; startDelay = delay; interpolator = interp
            addUpdateListener { set(it.animatedValue as Float); invalidate() }
            start()
        }
    }

    /** Move under the icon centred at `cx`, taking on `accent`. */
    fun moveTo(cx: Float, accent: Int, animate: Boolean, top: Float = bandTop, h: Float = bandH) {
        val tl = cx - baseLen / 2; val tr = cx + baseLen / 2
        val rowChanged = top != bandTop || h != bandH
        bandTop = top; bandH = h
        if (!animate || !placed || rowChanged) {
            anims.values.forEach { it.cancel() }
            l = tl; r = tr; color = accent; placed = true
            appear(); return
        }
        val goingRight = tr > r
        val lead = DecelerateInterpolator(2.2f); val trail = DecelerateInterpolator(1.4f)
        if (goingRight) {
            run("r", r, tr, 230, 0, lead) { r = it }
            run("l", l, tl, 420, 50, trail) { l = it }
        } else {
            run("l", l, tl, 230, 0, lead) { l = it }
            run("r", r, tr, 420, 50, trail) { r = it }
        }
        val c0 = color
        anims["c"]?.cancel()
        anims["c"] = ValueAnimator.ofObject(android.animation.ArgbEvaluator(), c0, accent).apply {
            duration = 320; addUpdateListener { color = it.animatedValue as Int; invalidate() }; start()
        }
    }

    /** Springs open from the centre (arrival on the dock). */
    fun appear() = run("g", 0f, 1f, 420, 0, android.view.animation.OvershootInterpolator(2.2f)) { grow = it }

    /** Brief flare when an app is opened. */
    fun press() = run("f", 1f, 0f, 520, 0, DecelerateInterpolator(1.3f)) { flash = it }

    /** Dim when focus leaves the dock, brighten when it returns. */
    fun setActive(a: Boolean) {
        val to = if (a) 1f else 0.18f
        if (activeA != to) run("a", activeA, to, if (a) 200L else 260L, 0L, DecelerateInterpolator()) { activeA = it }
        if (a && activeA < 0.5f) appear()
    }

    override fun onDraw(canvas: Canvas) {
        if (!placed) return
        val bh = if (bandH > 0f) bandH else height.toFloat()
        val cy = bandTop + bh - lineFromBottom
        val mid = (l + r) / 2f
        val half = (r - l) / 2f * grow * (1f + 0.35f * flash)
        if (half <= 0.5f) return
        val a = activeA
        val glowK = 0.9f * (1f + 0.6f * flash)
        shadersFor(color)

        // 1) Light pool under the focused icon (accent, very soft).
        val poolCy = bandTop + bh * (iconCenterFrac + 0.20f)
        m.setScale(baseLen * 1.5f, bh * 0.30f); m.postTranslate(mid, poolCy)
        poolShader?.setLocalMatrix(m)
        poolPaint.alpha = (255 * a * glowK).toInt().coerceIn(0, 255)
        canvas.drawRect(mid - baseLen * 1.6f, poolCy - bh * 0.32f, mid + baseLen * 1.6f, poolCy + bh * 0.32f, poolPaint)

        // 2) Halo around the line (accent), elliptical, follows the stretched length.
        m.setScale(half + 26f * dp, 16f * dp * (1f + 0.3f * flash)); m.postTranslate(mid, cy)
        glowShader?.setLocalMatrix(m)
        glowPaint.alpha = (255 * a * glowK).toInt().coerceIn(0, 255)
        canvas.drawRect(mid - half - 40f * dp, cy - 30f * dp, mid + half + 40f * dp, cy + 30f * dp, glowPaint)

        // 3) White-hot core: brightest in the middle, tinted toward the accent at the ends.
        m.setScale(half * 2f, 1f); m.postTranslate(mid - half, 0f)
        coreShader?.setLocalMatrix(m)
        corePaint.alpha = (255 * a).toInt().coerceIn(0, 255)
        val h2 = coreH / 2f * (1f + 0.25f * flash)
        rr.set(mid - half, cy - h2, mid + half, cy + h2)
        canvas.drawRoundRect(rr, h2, h2, corePaint)
    }
}

/** Picks a vivid accent colour from an icon (its most saturated bright pixels), or a cool white. */
object Accent {
    fun of(d: Drawable): Int {
        return try {
            val n = 32
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, n, n); d.draw(Canvas(b))
            val px = IntArray(n * n); b.getPixels(px, 0, n, 0, 0, n, n); b.recycle()
            val hsv = FloatArray(3)
            var sr = 0f; var sg = 0f; var sb = 0f; var wsum = 0f
            for (c in px) {
                if ((c ushr 24) < 128) continue
                Color.colorToHSV(c, hsv)
                if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue
                val wgt = hsv[1] * hsv[2]
                sr += Color.red(c) * wgt; sg += Color.green(c) * wgt; sb += Color.blue(c) * wgt; wsum += wgt
            }
            if (wsum < 8f) return Color.rgb(190, 215, 255)
            val c = Color.rgb((sr / wsum).toInt(), (sg / wsum).toInt(), (sb / wsum).toInt())
            Color.colorToHSV(c, hsv)
            hsv[1] = (hsv[1] * 1.15f).coerceAtMost(0.9f); hsv[2] = 1f
            Color.HSVToColor(hsv)
        } catch (_: Throwable) { Color.rgb(190, 215, 255) }
    }
}

/** "All apps" hint under the dock: a small chevron and a word, gently pulsing. */
class HintView(context: Context, private val text: String, textPx: Float) : View(context) {
    private val dp = resources.displayMetrics.density
    private val tp = labelPaint(textPx, dp)
    private val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.2f * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800; repeatMode = ValueAnimator.REVERSE; repeatCount = 5   // 3 pulses, then still
        addUpdateListener { invalidate() }
    }

    /** Pulse again (each time Home appears). */
    fun replay() { pulse.cancel(); pulse.start() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); pulse.start() }
    override fun onDetachedFromWindow() { pulse.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val t = (pulse.animatedValue as? Float) ?: 0f
        val a = (120 + 60 * t).toInt()
        val cx = width / 2f
        val cy = height * 0.30f + t * 3f * dp
        val s = height * 0.14f
        path.reset(); path.moveTo(cx - s * 1.4f, cy - s * 0.5f); path.lineTo(cx, cy + s * 0.6f); path.lineTo(cx + s * 1.4f, cy - s * 0.5f)
        cp.alpha = a; canvas.drawPath(path, cp)
        tp.alpha = a
        canvas.drawText(text, cx, height * 0.88f, tp)
    }
}

/** Wallpaper picker thumbnail: the photo in a glass frame, its number below, a ring on the current one. */
class WallpaperThumb(context: Context, source: GlassSource, private val photo: Bitmap?, private val number: Int,
                     private val current: Boolean, labelPx: Float) : GlassView(context, source) {
    private val tp = labelPaint(labelPx, dp)
    private val pp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val r = RectF(); private val mm = Matrix()
    init { lens = false; isLongClickable = false }

    override fun drawContent(canvas: Canvas) {
        val inset = 8f * dp
        val labelH = 44f * dp
        r.set(inset, inset, width - inset, height - labelH)
        val b = photo
        if (b != null) {
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = maxOf(r.width() / b.width, r.height() / b.height)
            mm.setScale(s, s); mm.postTranslate(r.left + (r.width() - b.width * s) / 2, r.top + (r.height() - b.height * s) / 2)
            sh.setLocalMatrix(mm); pp.shader = sh
            canvas.drawRoundRect(r, radius - inset, radius - inset, pp)
        }
        if (current) { ring.strokeWidth = 3f * dp; canvas.drawRoundRect(r, radius - inset, radius - inset, ring) }
        tp.alpha = if (active) 255 else 210
        canvas.drawText(if (current) "Wallpaper $number · Current" else "Wallpaper $number", width / 2f, height - 14f * dp, tp)
    }
}
