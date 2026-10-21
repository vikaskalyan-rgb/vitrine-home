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
 * Vitrine Home screensaver: your Home wallpaper with a very slow drift (off with Reduce motion), and
 * the clock in the same type as Home. The clock shifts a little every 45 s so nothing stays burned
 * into the same pixels. Dimmer at night when Evening mode is on.
 */
class GlassDream : DreamService() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var views: Array<ImageView>
    private lateinit var clockBox: View
    private var front = 0
    private val prefs by lazy { getSharedPreferences("layout", Context.MODE_PRIVATE) }
    private val reduce get() = prefs.getBoolean("reduce_motion", false)
    private var sy = 1f
    private val next = object : Runnable {
        override fun run() { drift(); ui.postDelayed(this, HOLD_MS) }
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
        // Only the wallpaper chosen for Home: no rotation.
        Thread {
            val bmp = load() ?: return@Thread
            ui.post {
                val v = views[front]
                v.setImageBitmap(bmp)
                v.animate().alpha(1f).setDuration(1500).start()
                step = 0
                drift()
                ui.postDelayed(next, HOLD_MS)
            }
        }.start()
    }

    override fun onDreamingStopped() {
        ui.removeCallbacksAndMessages(null)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    /** The Home wallpaper: the numbered photo picked in the Wallpaper sheet, else wallpaper.jpg, else the built-in one. */
    private fun currentPhoto(): File? {
        val dir = getExternalFilesDir(null) ?: return null
        val re = Regex("^(\\d{1,2})\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
        val numbered = File(dir, "wallpapers").listFiles()?.mapNotNull { f ->
            re.find(f.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to f }
        }?.sortedBy { it.first } ?: emptyList()
        if (numbered.isNotEmpty()) {
            val want = prefs.getInt("wp_n", numbered.first().first)
            return (numbered.firstOrNull { it.first == want } ?: numbered.first()).second
        }
        return File(dir, "wallpaper.jpg").takeIf { it.exists() }
    }

    private fun load(): Bitmap? {
        val dm = resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels); val h = minOf(dm.widthPixels, dm.heightPixels)
        return try {
            val f = currentPhoto() ?: return BitmapFactory.decodeResource(resources, R.drawable.wallpaper)
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            var s = 1
            while (b.outWidth / (s * 2) >= w && b.outHeight / (s * 2) >= h) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Throwable) { null }
    }

    private var step = 0

    /**
     * Every 45 s: the photo drifts slowly in, then back out (off with Reduce motion), and the clock
     * moves a few pixels, so nothing stays fixed on the same part of the panel.
     */
    private fun drift() {
        val v = views[front]
        if (!reduce) {
            val zoomIn = step % 2 == 0
            val dir = if (step % 4 == 0) 1f else -1f
            v.animate().scaleX(if (zoomIn) 1.07f else 1f).scaleY(if (zoomIn) 1.07f else 1f)
                .translationX(if (zoomIn) 24f * sy * dir else 0f)
                .setDuration(HOLD_MS).setInterpolator(LinearInterpolator()).start()
        }
        clockBox.translationX = ((step % 5) - 2) * 14f * sy
        clockBox.translationY = -((step % 3)) * 10f * sy
        step++
    }

    companion object { private const val HOLD_MS = 45_000L }
}
