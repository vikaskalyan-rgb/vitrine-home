package app.vitrine.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * All-apps as a typographic wheel: the focused app's name is set large, the rest smaller and fading
 * with distance. The list glides on a critically damped spring and text size interpolates continuously,
 * so moving between apps flows rather than jumps. The focused app's logo sits in a liquid-glass plate on
 * the right (the plate is a GlassView placed behind this view at [plate]); logos cross-fade with a rise,
 * and an inner glow takes on each app's accent colour.
 */
class AppWheel(context: Context, private val px: (Float) -> Float) : View(context) {
    class Entry(val pkg: String, val label: String, val icon: IconArt, val accent: Int)

    var entries: List<Entry> = emptyList()
        set(v) { field = v; index = index.coerceIn(0, maxOf(0, v.size - 1)); pos = index.toFloat(); invalidate() }
    var onLaunch: (Int) -> Unit = {}
    var onMenu: (Int) -> Unit = {}
    var onExitTop: () -> Unit = {}
    /** Where the glass plate is, in this view's coordinates. */
    val plate = RectF()

    var index = 0
        private set
    private var pos = 0f
    private var lastFrame = 0L
    private var running = false
    private var prev = -1
    private var showT = 1f
    private var showAnim: ValueAnimator? = null
    private var glowFrom = Color.WHITE
    private var glowTo = Color.WHITE
    private var press = 0f
    private var longPressed = false

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(6f, 0f, 2f, Color.argb(100, 0, 0, 0)) }
    private val caption = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    init { isFocusable = true; isFocusableInTouchMode = true }

    fun select(pkg: String?) {
        val i = entries.indexOfFirst { it.pkg == pkg }
        if (i >= 0) { index = i; pos = i.toFloat(); prev = -1; showT = 1f; glowTo = entries[i].accent; glowFrom = glowTo; invalidate() }
    }

    fun reset() { if (entries.isNotEmpty()) { index = 0; pos = 0f; prev = -1; showT = 1f; glowTo = entries[0].accent; glowFrom = glowTo; invalidate() } }

    // ------------------------------------------------------------- motion

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            val dt = if (lastFrame == 0L) 0.016f else ((t - lastFrame) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrame = t
            pos += (index - pos) * (1f - exp(-dt * 11f))   // critically damped approach
            if (abs(index - pos) < 0.002f) { pos = index.toFloat(); running = false; lastFrame = 0L }
            else Choreographer.getInstance().postFrameCallback(this)
            invalidate()
        }
    }

    private fun go(to: Int) {
        if (to == index || to !in entries.indices) return
        prev = index; index = to
        glowFrom = currentGlow(); glowTo = entries[to].accent
        showAnim?.cancel()
        showAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460; interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { showT = it.animatedValue as Float; invalidate() }
            start()
        }
        if (!running) { running = true; lastFrame = 0L; Choreographer.getInstance().postFrameCallback(frame) }
    }

    private fun currentGlow(): Int = android.animation.ArgbEvaluator().evaluate(showT, glowFrom, glowTo) as Int

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { go(index + 1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (index == 0) onExitTop() else go(index - 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) { event.startTracking(); longPressed = false }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longPressed = true; if (index in entries.indices) onMenu(index); return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !longPressed && !event.isCanceled) {
            if (index in entries.indices) {
                ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 380; interpolator = DecelerateInterpolator()
                    addUpdateListener { press = it.animatedValue as Float; invalidate() }
                    start()
                }
                val i = index
                postDelayed({ onLaunch(i) }, 140)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        if (entries.isEmpty()) return
        val left = px(120f)
        val centerY = px(560f)
        val small = px(46f); val big = px(118f)
        val smallH = px(68f); val bigH = px(176f)
        val near = bigH / 2f + smallH / 2f

        // Caption above the first app; it scrolls with the list.
        fun yFor(i: Int): Float {
            val d = i - pos; val ad = abs(d)
            return centerY + sign(d) * (if (ad <= 1f) ad * near else near + (ad - 1f) * smallH)
        }
        val y0 = yFor(0)
        val w0 = (1f - abs(0 - pos)).coerceAtLeast(0f)
        caption.textSize = px(22f)
        caption.alpha = edgeFade(y0 - px(80f), 170)
        canvas.drawText("Apps", left + px(4f), y0 - (small + (big - small) * w0) * 0.62f - px(26f), caption)

        val from = (pos - 7).toInt().coerceAtLeast(0)
        val to = (pos + 9).toInt().coerceAtMost(entries.size - 1)
        for (i in from..to) {
            val ad = abs(i - pos)
            val w = (1f - ad).coerceAtLeast(0f)
            val wE = w * w * (3 - 2 * w)                    // smoothstep: size settles gracefully
            val size = small + (big - small) * wE
            val y = yFor(i)
            val a = if (ad < 1f) (255 - 115 * ad) else (140 - (ad - 1f) * 34f).coerceAtLeast(0f)
            text.textSize = size * (1f + 0.03f * press * w)
            text.typeface = if (w > 0.5f) medium else regular
            text.alpha = edgeFade(y, a.toInt())
            if (text.alpha == 0) continue
            canvas.drawText(entries[i].label, left, y + size * 0.36f, text)
        }

        drawShowcase(canvas)
    }

    /** Fades text out toward the top and bottom of the screen. */
    private fun edgeFade(y: Float, a: Int): Int {
        val top = px(150f); val bottom = height - px(40f); val band = px(140f)
        val k = when { y < top -> 0f; y < top + band -> (y - top) / band; y > bottom -> 0f; y > bottom - band -> (bottom - y) / band; else -> 1f }
        return (a * k).toInt().coerceIn(0, 255)
    }

    /** The focused app's icon, white and large like the dock's — no plate, border or glow. */
    private fun drawShowcase(canvas: Canvas) {
        if (plate.isEmpty) return
        val cx = plate.centerX(); val cy = plate.centerY()
        val box = plate.height() * 0.42f
        val rise = px(56f)
        if (prev in entries.indices && showT < 1f)
            drawLogo(canvas, entries[prev].icon, cx, cy - rise * showT, box, 1f - 0.06f * showT, ((1f - showT) * 255).toInt())
        if (index in entries.indices)
            drawLogo(canvas, entries[index].icon, cx, cy + rise * (1f - showT), box, (1.06f - 0.06f * showT) * (1f + 0.05f * press), (showT * 255).toInt())
    }

    private fun drawLogo(canvas: Canvas, art: IconArt, cx: Float, cy: Float, box: Float, scale: Float, alpha: Int) {
        if (alpha <= 0) return
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        art.draw(canvas, cx, cy, box, alpha.coerceIn(0, 255))
        canvas.restore()
    }
}
