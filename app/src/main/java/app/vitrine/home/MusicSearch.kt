package app.vitrine.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.audiofx.Visualizer
import android.text.TextPaint
import android.text.TextUtils
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A song suggestion: what the music app is asked to play when it is picked. */
data class Song(val title: String, val artist: String, val album: String, val artUrl: String?,
                val trackUrl: String? = null, val durationMs: Long = 0L)

/**
 * Song suggestions while typing. Apple's public iTunes search (no account or key needed) gives titles,
 * artists and artwork quickly; the chosen song is then played by name in Spotify or YouTube Music.
 */
object SongSuggest {
    fun search(term: String, country: String = "IN", limit: Int = 7): List<Song> {
        val q = term.trim()
        if (q.length < 2) return emptyList()
        val url = URL("https://itunes.apple.com/search?media=music&entity=song&limit=$limit&country=$country&term=" +
            URLEncoder.encode(q, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 6000
        return try {
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val out = ArrayList<Song>()
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.optString("trackName"); val a = o.optString("artistName")
                if (t.isEmpty() || !seen.add((t + "|" + a).lowercase())) continue
                out += Song(t, a, o.optString("collectionName"),
                    o.optString("artworkUrl100").takeIf { it.isNotEmpty() }?.replace("100x100", "400x400"),
                    o.optString("trackViewUrl").takeIf { it.isNotEmpty() }, o.optLong("trackTimeMillis"))
            }
            out
        } finally { c.disconnect() }
    }

    /**
     * The same song in another service ("spotify" or "youtubeMusic"), via song.link's free public lookup
     * (no key; about 10 a minute). Returns that service's link, or null.
     */
    fun linkFor(trackUrl: String, platform: String, country: String = "IN"): String? = try {
        val c = URL("https://api.song.link/v1-alpha.1/links?userCountry=$country&url=" + URLEncoder.encode(trackUrl, "UTF-8"))
            .openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 7000
        try {
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            o.optJSONObject("linksByPlatform")?.optJSONObject(platform)?.optString("url")?.takeIf { it.isNotEmpty() }
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    fun art(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 6000
        try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
    } catch (_: Exception) { null }
}

/** One suggestion: artwork, title, artist · album, length. Glass only on the focused row. */
class SongRow(context: Context, source: GlassSource, val song: Song, private val textPx: Float) : GlassView(context, source) {
    var art: Bitmap? = null
        set(v) { field = v; shader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }; invalidate() }
    private var shader: BitmapShader? = null
    private val ap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t1 = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val t2 = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF(); private val m = Matrix()

    init { idleHidden = true; lens = false; focusScale = 1.02f; liftDp = 0f; isLongClickable = false }

    override fun drawContent(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = h * 0.72f; val x0 = h * 0.22f; val y0 = (h - s) / 2f
        r.set(x0, y0, x0 + s, y0 + s)
        val sh = shader; val b = art
        if (sh != null && b != null) {
            val sc = maxOf(s / b.width, s / b.height)
            m.setScale(sc, sc); m.postTranslate(r.left + (s - b.width * sc) / 2f, r.top + (s - b.height * sc) / 2f)
            sh.setLocalMatrix(m); ap.shader = sh
            canvas.drawRoundRect(r, s * 0.14f, s * 0.14f, ap)
        } else { ph.color = Theme.ink(35); canvas.drawRoundRect(r, s * 0.14f, s * 0.14f, ph) }
        t1.color = Theme.ink(if (active) 255 else 230); t1.textSize = textPx; t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t2.color = Theme.ink(if (active) 200 else 160); t2.textSize = textPx * 0.78f
        val dur = if (song.durationMs > 0) "%d:%02d".format(song.durationMs / 60000, (song.durationMs / 1000) % 60) else ""
        if (dur.isNotEmpty()) canvas.drawText(dur, w - h * 0.3f - t2.measureText(dur), h / 2f - (t2.descent() + t2.ascent()) / 2f, t2)
        val tx = r.right + h * 0.28f; val maxW = w - tx - h * 0.5f - t2.measureText(dur)
        val sub = song.artist + (if (song.album.isNotEmpty()) "  ·  " + song.album else "")
        canvas.drawText(TextUtils.ellipsize(song.title, t1, maxW, TextUtils.TruncateAt.END).toString(), tx, h * 0.46f, t1)
        canvas.drawText(TextUtils.ellipsize(sub, t2, maxW, TextUtils.TruncateAt.END).toString(), tx, h * 0.76f, t2)
    }
}

/** Spotify-style "Top result": big artwork, title, artist and a small "Song" tag. */
class TopResultCard(context: Context, source: GlassSource, val song: Song, private val textPx: Float) : GlassView(context, source) {
    var art: Bitmap? = null
        set(v) { field = v; shader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }; invalidate() }
    private var shader: BitmapShader? = null
    private val ap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG)
    private val play = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t1 = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val t2 = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF(); private val m = Matrix(); private val path = android.graphics.Path()

    init { lens = true; focusScale = 1.03f; liftDp = 10f; isLongClickable = false }

    override fun drawContent(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = w * 0.08f; val s = minOf(w - pad * 2, h * 0.56f)
        r.set(pad, pad, pad + s, pad + s)
        val sh = shader; val b = art
        if (sh != null && b != null) {
            val sc = maxOf(s / b.width, s / b.height)
            m.setScale(sc, sc); m.postTranslate(r.left + (s - b.width * sc) / 2f, r.top + (s - b.height * sc) / 2f)
            sh.setLocalMatrix(m); ap.shader = sh
            canvas.drawRoundRect(r, s * 0.06f, s * 0.06f, ap)
        } else { ph.color = Theme.ink(35); canvas.drawRoundRect(r, s * 0.06f, s * 0.06f, ph) }
        val maxW = w - pad * 2
        t1.color = Theme.ink(255); t1.textSize = textPx * 1.25f; t1.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t2.color = Theme.ink(if (active) 210 else 170); t2.textSize = textPx * 0.85f
        var y = r.bottom + textPx * 1.7f
        canvas.drawText(TextUtils.ellipsize(song.title, t1, maxW, TextUtils.TruncateAt.END).toString(), pad, y, t1)
        y += textPx * 1.3f
        canvas.drawText(TextUtils.ellipsize(song.artist, t2, maxW, TextUtils.TruncateAt.END).toString(), pad, y, t2)
        y += textPx * 0.8f
        val tw = t2.measureText("Song") + textPx * 1.2f
        tag.color = Theme.ink(40); r.set(pad, y, pad + tw, y + textPx * 1.3f)
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, tag)
        canvas.drawText("Song", pad + textPx * 0.6f, r.centerY() - (t2.descent() + t2.ascent()) / 2f, t2)
        if (active) {
            val pr = textPx * 1.3f; val cx = w - pad - pr; val cy = h - pad - pr
            play.color = Theme.ink(255); canvas.drawCircle(cx, cy, pr, play)
            play.color = Theme.paper
            val g = pr * 0.42f
            path.reset(); path.moveTo(cx - g * 0.55f, cy - g); path.lineTo(cx + g * 0.95f, cy); path.lineTo(cx - g * 0.55f, cy + g); path.close()
            canvas.drawPath(path, play)
        }
    }
}

/** A magnifier, drawn at the start of the search field. */
class SearchGlyph(context: Context) : android.view.View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) * 0.5f; val cx = width / 2f; val cy = height / 2f
        p.color = Theme.ink(200); p.strokeWidth = maxOf(1.5f, s * 0.14f)
        canvas.drawCircle(cx - s * 0.12f, cy - s * 0.12f, s * 0.42f, p)
        canvas.drawLine(cx + s * 0.18f, cy + s * 0.18f, cx + s * 0.5f, cy + s * 0.5f, p)
    }
}

/** Round glass button with a microphone. */
class MicButton(context: Context, source: GlassSource) : GlassView(context, source) {
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    init { lens = false; focusScale = 1.1f; liftDp = 8f; isLongClickable = false }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { radius = minOf(w, h) / 2f; super.onSizeChanged(w, h, oldw, oldh) }
    override fun drawContent(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val s = minOf(width, height) * 0.16f
        gp.color = Theme.ink(if (active) 255 else 225); gp.strokeWidth = maxOf(1.6f, s * 0.28f)
        r.set(cx - s * 0.62f, cy - s * 1.45f, cx + s * 0.62f, cy + s * 0.35f)
        canvas.drawRoundRect(r, s * 0.62f, s * 0.62f, gp)
        r.set(cx - s * 1.15f, cy - s * 0.85f, cx + s * 1.15f, cy + s * 0.95f)
        canvas.drawArc(r, 20f, 140f, false, gp)
        canvas.drawLine(cx, cy + s * 0.95f, cx, cy + s * 1.5f, gp)
    }
}

/**
 * Loudness of the TV's sound output in a few bands, for the wave on the player. Uses Android's
 * Visualizer on the whole output mix (needs the microphone permission; nothing is recorded or kept).
 * If this TV doesn't allow it, [levels] stays null and the wave falls back to a gentle pulse.
 */
class BeatMeter(private val bands: Int = 16) {
    private var vis: Visualizer? = null
    private val out = FloatArray(bands)
    private var peak = 1f
    @Volatile private var signal = false
    private var failed = false

    fun start() {
        if (vis != null || failed) return
        try {
            val v = Visualizer(0)
            v.enabled = false
            val range = Visualizer.getCaptureSizeRange()
            v.captureSize = 512.coerceIn(range[0], range[1])
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, w: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) take(fft) }
            }, minOf(Visualizer.getMaxCaptureRate(), 20_000), false, true)
            v.enabled = true
            vis = v
            android.util.Log.d("Vitrine", "beat: listening to the sound output")
        } catch (e: Throwable) {
            failed = true
            android.util.Log.d("Vitrine", "beat: not allowed on this TV (${e.message}); using a pulse")
        }
    }

    private fun take(fft: ByteArray) {
        val n = fft.size / 2
        var any = false
        for (b in 0 until bands) {
            val lo = Math.pow(n.toDouble(), b / bands.toDouble()).toInt().coerceIn(1, n - 1)
            val hi = Math.pow(n.toDouble(), (b + 1) / bands.toDouble()).toInt().coerceIn(lo + 1, n)
            var sum = 0.0
            for (k in lo until hi) { val re = fft[2 * k].toDouble(); val im = fft[2 * k + 1].toDouble(); sum += Math.hypot(re, im) }
            val mag = (sum / (hi - lo)).toFloat()
            out[b] = mag
            if (mag > 0.8f) any = true
        }
        var mx = 0f; for (v in out) if (v > mx) mx = v
        peak = maxOf(mx, peak * 0.992f, 2f)
        for (b in 0 until bands) out[b] = (out[b] / peak).coerceIn(0f, 1f)
        signal = any
    }

    /** Band levels 0..1 (low to high), or null when there's no access or only silence. */
    fun levels(): FloatArray? = if (vis != null && signal) out.copyOf() else null

    fun stop() {
        try { vis?.enabled = false; vis?.release() } catch (_: Throwable) {}
        vis = null; signal = false
    }
}
