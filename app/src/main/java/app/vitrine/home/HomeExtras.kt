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
