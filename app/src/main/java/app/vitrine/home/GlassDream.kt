package app.vitrine.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import java.io.File
import java.util.Calendar

/**
 * Vitrine Home screensaver: your Home wallpapers crossfading slowly, with a very slow drift (off with
 * Reduce motion), and the clock in the same type as Home. The clock shifts a little each photo so
 * nothing stays burned into the same pixels. Dimmer at night when Evening mode is on.
 */
class GlassDream : DreamService() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var views: Array<ImageView>
    private lateinit var clockBox: View
    private var front = 0
    private var idx = 0
    private var photos: List<File> = emptyList()
    private val prefs by lazy { getSharedPreferences("layout", Context.MODE_PRIVATE) }
    private val reduce get() = prefs.getBoolean("reduce_motion", false)
    private var sy = 1f
    private val next = object : Runnable {
        override fun run() { advance(); ui.postDelayed(this, HOLD_MS) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true; isScreenBright = true
        val dm = resources.displayMetrics
        sy = minOf(dm.widthPixels, dm.heightPixels) / 1080f
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        views = Array(2) {
            ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
                .also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        }, FrameLayout.LayoutParams(-1, (460 * sy).toInt(), Gravity.BOTTOM))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, 120 * sy)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); includeFontPadding = false
            setShadowLayer(10f, 0f, 2f, Color.argb(110, 0, 0, 0))
        })
        box.addView(TextClock(this).apply {
            format12Hour = "EEEE d MMMM"; format24Hour = "EEEE d MMMM"
            setTextColor(Color.argb(210, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_PX, 32 * sy)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL); includeFontPadding = false
            setShadowLayer(8f, 0f, 1f, Color.argb(110, 0, 0, 0))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (10 * sy).toInt() })
        clockBox = box
        root.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = (120 * sy).toInt(); bottomMargin = (110 * sy).toInt()
        })
        // Night: a softer screensaver when Evening mode is on.
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (prefs.getBoolean("evening", true) && (h >= 21 || h < 6)) root.alpha = 0.6f
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        photos = listPhotos()
        idx = (prefs.getInt("wp_n", 1) - 1).coerceAtLeast(0)
        advance()
        ui.postDelayed(next, HOLD_MS)
    }

    override fun onDreamingStopped() {
        ui.removeCallbacksAndMessages(null)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun listPhotos(): List<File> {
        val dir = getExternalFilesDir(null) ?: return emptyList()
        val re = Regex("^(\\d{1,2})\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
        val numbered = File(dir, "wallpapers").listFiles()?.mapNotNull { f ->
            re.find(f.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to f }
        }?.sortedBy { it.first }?.map { it.second } ?: emptyList()
        if (numbered.isNotEmpty()) return numbered
        return File(dir, "wallpaper.jpg").let { if (it.exists()) listOf(it) else emptyList() }
    }

    private fun load(i: Int): Bitmap? {
        val dm = resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels); val h = minOf(dm.widthPixels, dm.heightPixels)
        return try {
            if (photos.isEmpty()) return BitmapFactory.decodeResource(resources, R.drawable.wallpaper)
            val f = photos[i % photos.size]
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            var s = 1
            while (b.outWidth / (s * 2) >= w && b.outHeight / (s * 2) >= h) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Throwable) { null }
    }

    /** Next photo into the back view, crossfade over 2.5 s, then a slow drift until the next one. */
    private fun advance() {
        val i = idx; idx++
        Thread {
            val bmp = load(i) ?: return@Thread
            ui.post {
                val back = views[1 - front]; val old = views[front]
                back.setImageBitmap(bmp)
                back.scaleX = 1f; back.scaleY = 1f; back.translationX = 0f
                back.animate().cancel()
                back.animate().alpha(1f).setDuration(2500).start()
                old.animate().alpha(0f).setDuration(2500).start()
                if (!reduce) back.animate().scaleX(1.07f).scaleY(1.07f).translationX(if (i % 2 == 0) 24f * sy else -24f * sy)
                    .setDuration(HOLD_MS + 2500).setInterpolator(LinearInterpolator()).start()
                front = 1 - front
                // Burn-in care: nudge the clock a few pixels each photo.
                clockBox.translationX = ((i % 5) - 2) * 14f * sy
                clockBox.translationY = -((i % 3)) * 10f * sy
            }
        }.start()
    }

    companion object { private const val HOLD_MS = 45_000L }
}
