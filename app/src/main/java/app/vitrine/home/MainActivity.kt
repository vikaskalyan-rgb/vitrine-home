package app.vitrine.home

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.graphics.Outline
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.io.File
import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import org.json.JSONObject
import android.app.ActivityManager
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.media.tv.TvContract.WatchNextPrograms
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Vitrine Home: a glass home screen for Android TV. Layout is designed on a 1920 x 1080 base and
 * scaled to the screen: a photo wallpaper, clock and widgets, a floating dock of white app symbols,
 * All apps one press below, and glass panels for menus and settings.
 */
class MainActivity : Activity() {

    private data class App(val pkg: String, val label: String, val art: IconArt, val intent: Intent, val system: Boolean, val accent: Int, val hiArt: IconArt)

    private lateinit var glass: GlassSource
    private lateinit var home: FrameLayout          // everything that takes focus on the home screen
    private lateinit var root: FrameLayout
    private lateinit var grid: LinearLayout
    private lateinit var scroll: ScrollView
    private val tiles = ArrayList<AppTile>()
    private lateinit var dockLayer: FrameLayout     // dock + hint (home level)
    private lateinit var dockRows: LinearLayout     // one horizontal row per dock line, centred
    /** Current dock geometry, and each item's row and centre x inside the dock frame. */
    private class DockGeo(val itemW: Int, val rowH: Int, val counts: List<Int>, val frameW: Int, val frameH: Int, val bottom: Int)
    private var geo: DockGeo? = null
    private val dockSlot = HashMap<View, Pair<Int, Float>>()
    private var pendingPop: String? = null
    private var safeMode = false
    private val reduceMotion get() = prefs.getBoolean("reduce_motion", false)
    private val eveningOn get() = prefs.getBoolean("evening", true)
    private lateinit var eveningVeil: View
    private var backEscapeFired = false
    private var coachView: View? = null
    private val backupRunnable = Runnable { Thread { Backup.export(this) }.start() }
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.removeCallbacks(backupRunnable); ui.postDelayed(backupRunnable, 3000)
    }
    private lateinit var dockFrame: FrameLayout
    private lateinit var dockPanel: GlassView
    private lateinit var bottomFade: View
    private lateinit var dash: FocusDash
    private var dashAnim: ValueAnimator? = null
    private val bareDock get() = prefs.getString("dock_style", "bare") == "bare"
    private lateinit var bubble: GlassView
    private lateinit var gridLayer: FrameLayout     // scrim + tile grid (all-apps level)
    private lateinit var wheel: AppWheel
    private lateinit var headerView: View
    private var wheelApps: List<App> = emptyList()
    private val dockItems = ArrayList<DockItem>()
    private var gridOpen = false
    private var bubbleAnim: ValueAnimator? = null
    private var bubblePlaced = false
    // Calm Home: chrome that fades when idle, the Now Playing pill, the HDMI device card.
    private lateinit var widgetsView: View
    private lateinit var hintView: View
    private val chips = ArrayList<View>()
    private lateinit var nowBar: NowBar
    private var quiet = false
    private var resumedNow = false
    private var awayFromHome = true
    private var deviceCard: DeviceCard? = null
    private var tvCallback: TvInputManager.TvInputCallback? = null
    private val lastCardAt = HashMap<String, Long>()
    private val quietRunnable = Runnable { setQuiet(true) }
    private val hideNowBar = Runnable { if (controller?.playbackState?.state != PlaybackState.STATE_PLAYING) showNowBar(false) }
    private val dismissCard = Runnable { dismissDeviceCard(true) }
    private lateinit var greeting: TextView
    private lateinit var weatherCard: WeatherCard
    private lateinit var stack: SmartStack
    private lateinit var calendarPage: CalendarPage
    private lateinit var nowPage: NowPlayingPage
    private lateinit var continuePage: ContinuePage
    private lateinit var airPage: AirPage
    private lateinit var healthPage: HealthPage
    private var controller: MediaController? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val rotate = object : Runnable {
        override fun run() { if (!gridOpen && overlay == null) { refreshHealth(); stack.next() }; ui.postDelayed(this, 10_000L) }
    }
    private val progress = object : Runnable {
        override fun run() { if (stack.current === nowPage) stack.invalidate(); ui.postDelayed(this, 1000L) }
    }
    private val controllerCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { readController() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { readController() }
        override fun onSessionDestroyed() { pickController() }
    }
    private val ui = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable {
        if (overlay == null) reloadApps(null) else appsDirty = true
        Unit
    }
    private val tick = object : Runnable {
        override fun run() {
            updateGreeting(); stack.invalidate(); updateEvening(true)
            if (System.currentTimeMillis() - prefs.getLong("wx_at", 0) > 30 * 60_000L) fetchWeather()
            // Air quality on its own timer, so it loads even when the weather is still fresh.
            if (System.currentTimeMillis() - prefs.getLong("aq_at", 0) > 30 * 60_000L) Thread { fetchAir() }.start()
            ui.postDelayed(this, 60_000L)
        }
    }
    private var apps: List<App> = emptyList()
    private var appsDirty = true
    private var overlay: View? = null               // open More sheet or menu
    private var overlayTiles = ArrayList<AppTile>()
    private var returnFocus: View? = null
    private var screenW = 1920
    private var screenH = 1080
    private var sx = 1f
    private var sy = 1f
    private var rest = Material(Color.argb(22, 255, 255, 255), 130, 1.1f, 34)
    private var focus = Material(Color.argb(58, 255, 255, 255), 255, 2.2f, 72)
    private val prefs by lazy { getSharedPreferences("layout", Context.MODE_PRIVATE) }
    private val cfg by lazy { Config.load(this) }

    private fun X(v: Float) = v * sx
    private fun Y(v: Float) = v * sy
    private val side get() = X(120f).toInt()

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.data?.schemeSpecificPart == packageName) return
            appsDirty = true
            ui.removeCallbacks(reloadRunnable); ui.postDelayed(reloadRunnable, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Crash loop: hand Home to another launcher instead of crashing again (clears on the next install).
        if (Safety.fallback(this)) {
            Safety.otherLauncher(this)?.let { try { startActivity(it) } catch (_: Exception) {} }
            finish(); return
        }
        // A backup pushed by restore_tv.bat is applied before anything reads the settings.
        val restored = Backup.restoreIfPending(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        ui.postDelayed(backupRunnable, 5000)
        safeMode = Safety.safeMode(this)
        Theme.light = prefs.getBoolean("theme_light", false)
        handleStyleIntent(intent)
        val dm = resources.displayMetrics
        screenW = maxOf(dm.widthPixels, dm.heightPixels)
        screenH = minOf(dm.widthPixels, dm.heightPixels)
        sx = screenW / 1920f; sy = screenH / 1080f

        val wall = loadWallpaper(screenW, screenH)
        glass = GlassSource(wall)
        Theme.wallLum = glass.luminance
        adaptToWallpaper()

        root = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.background = BitmapDrawable(resources, wall)

        // Scrims scale with how bright the wallpaper is, so text stays legible on any photo.
        val overall = ((glass.luminance - 0.30f) * 200f).toInt().coerceIn(0, 90)
        if (overall > 0) root.addView(View(this).apply { setBackgroundColor(Color.argb(overall, 0, 0, 0)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val topShade = (50 + (glass.topLuminance - 0.3f) * 160f).toInt().coerceIn(40, 140)
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(topShade, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Y(260f).toInt()))

        home = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.addView(home, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Header: time + date on the left, chips on the right, both on the grid's margins.
        headerView = buildHeader()
        home.addView(headerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Y(136f).toInt()).apply {
            leftMargin = side; rightMargin = side; topMargin = Y(66f).toInt()
        })

        bottomFade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(130, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        }
        home.addView(bottomFade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Y(420f).toInt(), Gravity.BOTTOM))

        // Level 1: compact floating glass dock, centred at the bottom, sized to its apps.
        dockLayer = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        home.addView(dockLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dockFrame = FrameLayout(this).apply {
            val r = Y(44f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, r); outline.alpha = 0.5f }
            }
            clipToOutline = true; elevation = Y(10f)
        }
        dockPanel = GlassView(this, glass).apply {
            isFocusable = false; isClickable = false; isLongClickable = false
            radius = Y(44f); lens = true
            rest = Material(Color.argb(62, 24, 26, 30), 120, 1.1f, 26)
        }
        dockFrame.addView(dockPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bubble = GlassView(this, glass).apply {
            isFocusable = false; isClickable = false; isLongClickable = false
            radius = Y(34f); lens = true; lit = true
            focus = Material(Color.argb(58, 255, 255, 255), 200, 1.5f, 60)
        }
        dockFrame.addView(bubble, FrameLayout.LayoutParams(X(180f).toInt(), Y(172f).toInt(), Gravity.TOP or Gravity.START))
        dash = FocusDash(this).apply {
            visibility = View.GONE
            baseLen = X(64f); coreH = Y(5f); lineFromBottom = Y(34f); iconCenterFrac = 0.44f
        }
        dockFrame.addView(dash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dockRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dockFrame.addView(dockRows, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val g0 = dockGeo(dockList().size.coerceAtMost(MAX_DOCK))
        // Start at the final width (from the saved dock list) and hidden. Before the apps load the row is
        // empty, and a WRAP_CONTENT frame would stretch its glass panel to full screen width, then snap
        // smaller once the icons arrive. buildDock() sets the exact width and fades the dock in.
        dockFrame.alpha = 0f; dockFrame.visibility = View.INVISIBLE
        dockLayer.addView(dockFrame, FrameLayout.LayoutParams(g0.frameW, g0.frameH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = g0.bottom
        })
        val px: (Float) -> Float = { Y(it) }
        weatherCard = WeatherCard(this, glass, px)
        stack = SmartStack(this, glass, px)
        calendarPage = CalendarPage(px); nowPage = NowPlayingPage(px); continuePage = ContinuePage(px)
        airPage = AirPage(px).apply { place = placeName() }; healthPage = HealthPage(px)
        stack.pages.addAll(listOf(calendarPage, nowPage, continuePage, airPage, healthPage))
        val widgets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        widgetsView = widgets
        widgets.addView(weatherCard, LinearLayout.LayoutParams(X(440f).toInt(), Y(280f).toInt()))
        widgets.addView(stack, LinearLayout.LayoutParams(X(440f).toInt(), Y(304f).toInt()).apply { topMargin = Y(18f).toInt() })
        dockLayer.addView(widgets, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            rightMargin = side; topMargin = Y(156f).toInt()
        })
        // Hidden until there is a forecast to show (configured or found location).
        if (prefs.getString("wx_json", null) == null) weatherCard.visibility = View.GONE
        loadCachedWeather()
        refreshHealth()
        hintView = HintView(this, "All apps", Y(22f))
        dockLayer.addView(hintView, FrameLayout.LayoutParams(X(240f).toInt(), Y(70f).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Y(26f).toInt()
        })
        // Now Playing pill: floats above the dock only while something plays.
        nowBar = NowBar(this, glass).apply {
            visibility = View.GONE
            outlineOnly = true
            onToggle = { controller?.transportControls?.let { t -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play() } }
            onSkip = { d -> controller?.transportControls?.let { t -> if (d > 0) t.skipToNext() else t.skipToPrevious() } }
            setOnClickListener { onToggle?.invoke() }
        }
        dockLayer.addView(nowBar, FrameLayout.LayoutParams(X(640f).toInt(), Y(90f).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Y(330f).toInt()
        })

        // Level 2: all apps. Wallpaper dims, glass tiles rise from below. Up from the top row or Back returns.
        gridLayer = FrameLayout(this).apply { visibility = View.GONE; clipChildren = false; clipToPadding = false }
        gridLayer.addView(View(this).apply { setBackgroundColor(Color.argb(120, 0, 0, 0)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        gridLayer.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(110, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        }, FrameLayout.LayoutParams((screenW * 0.55f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        // Icon area on the right; the wheel draws the focused app's white icon there.
        val plateS = Y(560f).toInt()
        val plateL = screenW - side - plateS; val plateT = (screenH - plateS) / 2
        wheel = AppWheel(this) { Y(it) }.apply {
            plate.set(plateL.toFloat(), plateT.toFloat(), (plateL + plateS).toFloat(), (plateT + plateS).toFloat())
            translationZ = Y(20f)
            onExitTop = { showGrid(false) }
            onLaunch = { i -> wheelApps.getOrNull(i)?.let { rememberDock(it.pkg); safeStart(it.intent) } }
            onMenu = { i -> wheelApps.getOrNull(i)?.let { appMenu(it, isUtility(it)) } }
        }
        gridLayer.addView(wheel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Kept for code paths that still reference the old tile grid (not shown).
        scroll = ScrollView(this); grid = LinearLayout(this)
        home.addView(gridLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Evening veil: a warm, slight dim over Home after 9 pm (above Home, below menus and cards).
        eveningVeil = View(this).apply { setBackgroundColor(Color.argb(70, 34, 16, 0)); alpha = 0f; isFocusable = false; isClickable = false }
        root.addView(eveningVeil, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (safeMode) widgetsView.visibility = View.GONE

        setContentView(root)
        if (!safeMode) watchInputs()
        if (restored) ui.postDelayed({ coach("restored_now", "Your Vitrine Home setup was restored", always = true) }, 1500)
        if (safeMode) ui.postDelayed({ coach("safe_now", "Safe mode: Vitrine Home restarted after crashes. Gear → Exit safe mode", always = true) }, 1500)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)
    }

    /** Darker wallpapers need a touch more white in the glass to read as glass; bright ones need less. */
    private fun adaptToWallpaper() {
        val l = glass.luminance
        val t = when { l < 0.22f -> 30; l > 0.55f -> 14; else -> 22 }
        rest = Material(Color.argb(t, 255, 255, 255), 130, 1.1f, 34)
        focus = Material(Color.argb(t + 36, 255, 255, 255), 255, 2.2f, 72)
    }

    override fun onDestroy() {
        controller?.unregisterCallback(controllerCb)
        sessionsListener?.let { try { getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(it) } catch (_: Exception) {} }
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Exception) {}
        tvCallback?.let { cb -> try { (getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager)?.unregisterCallback(cb) } catch (_: Exception) {} }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (appsDirty) reloadApps(null)
        loadNextEvent()
        loadContinueWatching()
        startMediaWatch()
        ui.removeCallbacks(tick); ui.post(tick)
        ui.removeCallbacks(rotate); ui.postDelayed(rotate, 10_000L)
        ui.removeCallbacks(progress); ui.post(progress)
        resumedNow = true
        if (awayFromHome && !gridOpen && overlay == null) playEntry()
        awayFromHome = false
        poke()
    }

    override fun onStop() {
        awayFromHome = true
        super.onStop()
    }

    override fun onPause() {
        ui.removeCallbacks(tick); ui.removeCallbacks(rotate); ui.removeCallbacks(progress)
        resumedNow = false
        ui.removeCallbacks(quietRunnable)
        dismissDeviceCard(false)
        super.onPause()
    }

    // ---------- Now Playing ----------

    private fun startMediaWatch() {
        if (sessionsListener != null) { pickController(); return }
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(this, MediaListener::class.java)
        try {
            val l = MediaSessionManager.OnActiveSessionsChangedListener { pickController() }
            msm.addOnActiveSessionsChangedListener(l, comp, ui)
            sessionsListener = l
            pickController()
        } catch (_: SecurityException) { /* listener not allowed yet: widget stays hidden */ }
    }

    /** Prefer a session that is playing; otherwise the most recent paused one. */
    private fun pickController() {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        val list = try { msm.getActiveSessions(ComponentName(this, MediaListener::class.java)) } catch (_: SecurityException) { return }
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED && it.metadata != null }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCb)
            controller = best
            best?.registerCallback(controllerCb, ui)
        }
        readController()
    }

    private fun readController() {
        val c = controller
        val md = c?.metadata
        val st = c?.playbackState
        val wasPlaying = nowPage.playing
        if (c == null || md == null || st == null || (st.state != PlaybackState.STATE_PLAYING && st.state != PlaybackState.STATE_PAUSED)) {
            nowPage.title = null; stack.invalidate(); showNowBar(false); return
        }
        nowPage.title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Playing"
        nowPage.artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        nowPage.art = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        nowPage.durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        nowPage.playing = st.state == PlaybackState.STATE_PLAYING
        nowPage.app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (_: Exception) { "" }
        nowPage.positionMs = {
            val s2 = controller?.playbackState
            if (s2 == null) 0L else if (s2.state == PlaybackState.STATE_PLAYING)
                s2.position + ((SystemClock.elapsedRealtime() - s2.lastPositionUpdateTime) * s2.playbackSpeed).toLong()
            else s2.position
        }
        if (nowPage.playing && !wasPlaying) stack.showPage(nowPage)   // music just started: show it
        if (nowPage.playing) {
            ui.removeCallbacks(hideNowBar)
            nowBar.set(nowPage.title ?: "", nowPage.artist, true, nowPage.art); showNowBar(true)
        } else if (nowBar.visibility == View.VISIBLE) {
            // Paused: keep the pill (so pausing doesn't make it vanish), hide after a minute.
            nowBar.set(nowPage.title ?: "", nowPage.artist, false, nowPage.art)
            ui.removeCallbacks(hideNowBar); ui.postDelayed(hideNowBar, 60_000L)
        }
        stack.invalidate()
    }

    // ---------- Continue watching (Android TV "Watch next") ----------

    private fun loadContinueWatching() {
        Thread {
            val out = ArrayList<ContinuePage.Item>()
            try {
                contentResolver.query(WatchNextPrograms.CONTENT_URI,
                    // Column names spelled out: some are declared on a non-public base interface.
                    arrayOf("title", "episode_title", "package_name", "last_playback_position_millis", "duration_millis"),
                    null, null, "last_engagement_time_utc_millis DESC")?.use { c ->
                    while (c.moveToNext() && out.size < 3) {
                        val title = c.getString(0) ?: continue
                        val pkg = c.getString(2) ?: ""
                        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { "" }
                        val pos = c.getLong(3); val dur = c.getLong(4)
                        out.add(ContinuePage.Item(title, c.getString(1) ?: "", app, if (dur > 0) pos.toFloat() / dur else 0f))
                    }
                }
            } catch (_: Exception) { /* this TV doesn't let launchers read Watch next */ }
            runOnUiThread { continuePage.items = out; stack.invalidate() }
        }.start()
    }

    // ---------- TV health ----------

    private fun refreshHealth() {
        try {
            val mi = ActivityManager.MemoryInfo()
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            healthPage.memFree = mi.availMem; healthPage.memTotal = mi.totalMem
            val fs = StatFs(Environment.getDataDirectory().path)
            healthPage.diskFree = fs.availableBytes; healthPage.diskTotal = fs.totalBytes
            // Connection type and signal from ConnectivityManager: unlike WifiInfo it is not blanked
            // out when location access is restricted, so "connected" is always reported correctly.
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val nc = cm?.getNetworkCapabilities(cm.activeNetwork)
            val onWifi = nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            val onLan = nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true
            @Suppress("DEPRECATION")
            val info = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            var rssi = nc?.signalStrength ?: Int.MIN_VALUE
            if (rssi == Int.MIN_VALUE || rssi <= -127) rssi = info?.rssi ?: -127
            @Suppress("DEPRECATION")
            val ssid = info?.ssid?.trim('"') ?: ""
            when {
                onWifi -> {
                    healthPage.wifi = if (ssid.isBlank() || ssid == "<unknown ssid>") "Wi-Fi" else ssid
                    healthPage.signal = when {
                        rssi <= -127 -> 3          // connected but strength hidden: say Good, not "No signal"
                        rssi >= -55 -> 4; rssi >= -66 -> 3; rssi >= -77 -> 2; rssi >= -88 -> 1; else -> 1
                    }
                }
                onLan -> { healthPage.wifi = "Ethernet"; healthPage.signal = 4 }
                else -> { healthPage.wifi = "Offline"; healthPage.signal = 0 }
            }
            val up = SystemClock.elapsedRealtime() / 60000
            healthPage.uptime = if (up >= 60) "${up / 60} h ${up % 60} min" else "$up min"
        } catch (_: Exception) {}
        if (::stack.isInitialized) stack.invalidate()
    }

    // ---------- greeting, weather, calendar ----------

    private fun updateGreeting() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..20 -> "Good evening"; else -> "Good night" }
        greeting.text = if (cfg.name.isBlank()) part else "$part, ${cfg.name}"
    }

    private fun loadCachedWeather() {
        prefs.getString("wx_json", null)?.let { try { weatherCard.weather = parseWeather(JSONObject(it)) } catch (_: Exception) {} }
        prefs.getString("aq_json", null)?.let { applyAir(it) }
    }

    /**
     * Weather location: config.json, else an approximate one found from the internet connection
     * (ipwho.is, at most once a day, cached), else none. Returns latitude, longitude, place name.
     */
    private fun location(): Triple<Double, Double, String>? {
        val la = cfg.latitude; val lo = cfg.longitude
        if (la != null && lo != null) return Triple(la, lo, cfg.place)
        if (!cfg.autoLocation) return null
        val cached = prefs.getString("auto_loc", null)?.split('|')
        val fresh = System.currentTimeMillis() - prefs.getLong("auto_loc_at", 0) < 24 * 3600_000L
        if (cached != null && cached.size == 3 && fresh) return Triple(cached[0].toDouble(), cached[1].toDouble(), cached[2])
        return try {
            val c = URL("https://ipwho.is/?fields=success,city,latitude,longitude").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
            if (!o.optBoolean("success", false)) return cached?.takeIf { it.size == 3 }?.let { Triple(it[0].toDouble(), it[1].toDouble(), it[2]) }
            val t = Triple(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city", ""))
            prefs.edit().putString("auto_loc", "${t.first}|${t.second}|${t.third}").putLong("auto_loc_at", System.currentTimeMillis()).apply()
            t
        } catch (_: Exception) { cached?.takeIf { it.size == 3 }?.let { Triple(it[0].toDouble(), it[1].toDouble(), it[2]) } }
    }

    private fun placeName(): String = cfg.place.ifEmpty { prefs.getString("auto_loc", null)?.split('|')?.getOrNull(2) ?: "" }

    private fun fetchWeather() {
        Thread {
            try {
                val (lat, lon, place) = location() ?: return@Thread
                runOnUiThread { airPage.place = cfg.place.ifEmpty { place } }
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=5")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val w = parseWeather(JSONObject(body))
                prefs.edit().putString("wx_json", body).putLong("wx_at", System.currentTimeMillis()).apply()
                runOnUiThread { weatherCard.weather = w; weatherCard.visibility = View.VISIBLE }
            } catch (_: Exception) { /* offline: keep the last forecast */ }
        }.start()
    }

    private fun fetchAir() {
        try {
            val (lat, lon, _) = location() ?: return
            val url = URL("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5,pm10")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            prefs.edit().putString("aq_json", body).putLong("aq_at", System.currentTimeMillis()).apply()
            runOnUiThread { applyAir(body) }
        } catch (_: Exception) {}
    }

    private fun applyAir(body: String) {
        try {
            val cur = JSONObject(body).getJSONObject("current")
            airPage.aqi = Math.round(cur.getDouble("us_aqi")).toInt()
            airPage.pm25 = cur.optDouble("pm2_5", 0.0); airPage.pm10 = cur.optDouble("pm10", 0.0)
            stack.invalidate()
        } catch (_: Exception) {}
    }

    private fun parseWeather(j: JSONObject): Weather {
        val cur = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        val codes = d.getJSONArray("weather_code"); val mx = d.getJSONArray("temperature_2m_max"); val mn = d.getJSONArray("temperature_2m_min")
        val dates = d.getJSONArray("time")
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US); val outFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val days = ArrayList<Triple<String, Int, Pair<Int, Int>>>()
        for (i in 1 until dates.length()) {
            val name = try { outFmt.format(inFmt.parse(dates.getString(i)) ?: Date()) } catch (_: Exception) { "" }
            days.add(Triple(name, codes.getInt(i), Pair(Math.round(mx.getDouble(i)).toInt(), Math.round(mn.getDouble(i)).toInt())))
        }
        return Weather(placeName().ifEmpty { "Weather" }, Math.round(cur.getDouble("temperature_2m")).toInt(), cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1,
            Math.round(mx.getDouble(0)).toInt(), Math.round(mn.getDouble(0)).toInt(), days)
    }

    /** Next calendar event in the coming week, from the TV's synced Google calendar (if any). */
    private fun loadNextEvent() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        Thread {
            var text: String? = null
            try {
                val now = System.currentTimeMillis()
                val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(b, now); ContentUris.appendId(b, now + 7L * 24 * 3600_000L)
                contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY),
                    null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
                    while (cur.moveToNext()) {
                        val begin = cur.getLong(0); val title = cur.getString(1) ?: continue; val allDay = cur.getInt(2) == 1
                        if (begin < now && !allDay) continue
                        val today = Calendar.getInstance(); val ev = Calendar.getInstance().apply { timeInMillis = begin }
                        val day = if (today.get(Calendar.DAY_OF_YEAR) == ev.get(Calendar.DAY_OF_YEAR)) "Today"
                                  else SimpleDateFormat("EEE", Locale.getDefault()).format(Date(begin))
                        val time = if (allDay) "All day" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(begin))
                        text = "$day $time · $title"; break
                    }
                }
            } catch (_: Exception) {}
            val t = text ?: "No upcoming events"
            runOnUiThread { calendarPage.nextEvent = t; stack.invalidate() }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handleStyleIntent(intent)) { applyDockStyle(); return }
        // `--es theme light|dark` (used by the build script for screenshots of both modes).
        intent.getStringExtra("theme")?.let { t ->
            intent.removeExtra("theme")
            val light = t == "light"
            if (light != Theme.light) { prefs.edit().putBoolean("theme_light", light).apply(); recreate() }
            return
        }
        closeOverlay(false)
        goTop()
        // `am start ... --es open settings` opens the glass Settings sheet (used for build screenshots).
        if (intent.getStringExtra("open") == "settings") { intent.removeExtra("open"); ui.postDelayed({ showSettings() }, 300) }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            val wasQuiet = quiet
            poke()
            // The first navigation press only wakes the screen; volume, media and number keys act at once.
            val nav = e.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)
            if (wasQuiet && nav) return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) backEscapeFired = false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                // Escape hatch: hold Back for 5 s to open the device's other home screen.
                if (!backEscapeFired && e.eventTime - e.downTime >= 5000L) {
                    backEscapeFired = true
                    val other = Safety.otherLauncher(this)
                    if (other != null) safeStart(other) else safeStart(Intent(Settings.ACTION_SETTINGS))
                }
                return true
            }
            if (e.action == KeyEvent.ACTION_DOWN) {
                when { deviceCard != null -> dismissDeviceCard(true); overlay != null -> closeOverlay(true); gridOpen -> showGrid(false); else -> goTop() }
            }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && overlay == null && !gridOpen && deviceCard == null &&
            e.keyCode >= KeyEvent.KEYCODE_1 && e.keyCode <= KeyEvent.KEYCODE_9) {
            // Number keys open dock apps: 1 = first app.
            dockItems.getOrNull(e.keyCode - KeyEvent.KEYCODE_1)?.let { d -> d.requestFocus(); d.performClick(); return true }
        }
        if (e.action == KeyEvent.ACTION_DOWN && overlay == null) {
            val f = currentFocus
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !gridOpen && f is DockItem &&
                (dockSlot[f]?.first ?: 0) == (geo?.counts?.size ?: 1) - 1) { showGrid(true); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun goTop() {
        if (gridOpen) showGrid(false)
        preferredDock()?.requestFocus()
    }

    private fun showGrid(open: Boolean) {
        gridOpen = open
        if (open) ui.postDelayed({ if (gridOpen) coach("coach_grid", "Hold OK on any app to add it to the dock") }, 900)
        val rise = Y(90f)
        if (open) {
            dockLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            headerView.animate().alpha(0f).setDuration(200).start()
            dockLayer.animate().alpha(0f).translationY(-Y(30f)).setDuration(220).start()
            wheel.reset()
            gridLayer.visibility = View.VISIBLE; gridLayer.alpha = 0f; wheel.translationY = rise
            gridLayer.animate().alpha(1f).setDuration(260).start()
            wheel.animate().translationY(0f).setInterpolator(DecelerateInterpolator(2f)).setDuration(360).start()
            wheel.post { wheel.requestFocus() }
        } else {
            dockLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            headerView.animate().alpha(1f).setDuration(240).start()
            dockLayer.animate().alpha(1f).translationY(0f).setDuration(240).start()
            wheel.animate().translationY(rise).setDuration(220).start()
            gridLayer.animate().alpha(0f).setDuration(200).withEndAction { if (!gridOpen) gridLayer.visibility = View.GONE }.start()
            preferredDock()?.let { d -> d.post { d.requestFocus() } }
            popPending()
        }
    }

    // ---------- dock ----------

    private fun dockList(): MutableList<String> {
        val s = prefs.getString("dock_v2", null) ?: return cfg.dock.ifEmpty { DEFAULT_DOCK }.toMutableList()
        return s.split(',').filter { it.isNotBlank() }.toMutableList()
    }

    private fun saveDock(list: List<String>, focus: String?) {
        prefs.edit().putString("dock_v2", list.joinToString(",")).apply()
        buildDock(focus)
    }

    /**
     * Dock geometry: up to 10 apps on one row; more than 10 split into two balanced, centred rows
     * (11 = 6 + 5, 14 = 7 + 7). Icons shrink only when a row would not fit the screen.
     */
    private fun dockGeo(n: Int): DockGeo {
        val rows = if (n <= DOCK_PER_ROW) 1 else 2
        val counts = if (rows == 1) listOf(n) else listOf((n + 1) / 2, n / 2)
        val maxC = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
        val pad = 2 * X(14f).toInt()
        val itemW = minOf(X(196f).toInt(), (screenW - X(200f).toInt() - pad) / maxC)
        val rowH = if (rows == 1) Y(200f).toInt() else Y(140f).toInt()
        val frameW = maxOf(pad + maxC * itemW, X(400f).toInt())
        // Two rows sit lower (no "All apps" hint) so they clear the widgets on the right.
        val bottom = if (rows == 1) Y(104f).toInt() else Y(40f).toInt()
        return DockGeo(itemW, rowH, counts, frameW, rows * rowH, bottom)
    }

    private fun buildDock(focusPkg: String?) {
        val keep = focusPkg ?: (currentFocus as? DockItem)?.pkg
        dockRows.removeAllViews(); dockItems.clear(); dockSlot.clear()
        val byPkg = apps.associateBy { it.pkg }
        // Nothing from the saved/default list installed? Start with the first few apps instead.
        val list = dockList().mapNotNull { byPkg[it] }.ifEmpty { homeApps().take(6) }.take(MAX_DOCK)
        val g = dockGeo(list.size); geo = g
        bubble.visibility = if (list.isEmpty()) View.INVISIBLE else View.VISIBLE
        var i = 0
        g.counts.forEachIndexed { r, count ->
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            dockRows.addView(rowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, g.rowH))
            val left = (g.frameW - count * g.itemW) / 2f
            for (k in 0 until count) {
                val app = list[i++]
                val item = DockItem(this, app.pkg, app.label, app.art, Y(24f))
                item.setOnClickListener {
                    rememberDock(app.pkg)
                    if (bareDock) { dash.press(); ui.postDelayed({ safeStart(app.intent) }, 160) } else safeStart(app.intent)
                }
                item.setOnLongClickListener { dockMenu(app); true }
                item.setOnFocusChangeListener { v, has ->
                    if (has) { if (bareDock) moveDashTo(v) else moveBubbleTo(v) }
                    else dockRows.post { if (currentFocus !is DockItem) { bubbleIdle(); dash.setActive(false) } }
                }
                rowView.addView(item, LinearLayout.LayoutParams(g.itemW, ViewGroup.LayoutParams.MATCH_PARENT))
                dockItems.add(item)
                dockSlot[item] = r to (left + k * g.itemW + g.itemW / 2f)
            }
        }
        // Frame sized exactly to its rows and centred; the bubble, dash, hint and Now Playing pill follow it.
        val lp = dockFrame.layoutParams as FrameLayout.LayoutParams
        if (lp.width != g.frameW || lp.height != g.frameH || lp.bottomMargin != g.bottom)
            dockFrame.layoutParams = lp.apply { width = g.frameW; height = g.frameH; bottomMargin = g.bottom }
        (bubble.layoutParams as FrameLayout.LayoutParams).let { b ->
            val bw = g.itemW - X(16f).toInt(); val bh = g.rowH - Y(28f).toInt()
            if (b.width != bw || b.height != bh) bubble.layoutParams = b.apply { width = bw; height = bh }
        }
        dash.baseLen = minOf(X(64f), g.itemW * 0.36f); dash.lineFromBottom = g.rowH * 0.17f
        hintView.visibility = if (g.counts.size == 1) View.VISIBLE else View.GONE
        (nowBar.layoutParams as FrameLayout.LayoutParams).let { nb ->
            val m = g.bottom + g.frameH + Y(26f).toInt()
            if (nb.bottomMargin != m) nowBar.layoutParams = nb.apply { bottomMargin = m }
        }
        applyDockStyle()
        if (list.isEmpty()) dockFrame.visibility = View.INVISIBLE
        else if (dockFrame.visibility != View.VISIBLE) {
            // First appearance: wait for the correctly sized layout, then fade in. No size change is ever visible.
            dockFrame.visibility = View.VISIBLE
            dockFrame.post { dockFrame.animate().alpha(1f).setDuration(220).start() }
        }
        if (!gridOpen && overlay == null) {
            val target = dockItems.firstOrNull { it.pkg == keep } ?: preferredDock()
            if (prefs.getBoolean("focus_theme_chip", false)) {
                prefs.edit().putBoolean("focus_theme_chip", false).apply()
                chips.firstOrNull()?.let { c -> c.post { c.requestFocus() } }
            } else target?.post { target.requestFocus() }
            popPending()
        }
    }

    /** A just-added app springs into its place in the dock (runs once the dock is visible). */
    private fun popPending() {
        val pkg = pendingPop ?: return
        val item = dockItems.firstOrNull { it.pkg == pkg } ?: run { pendingPop = null; return }
        pendingPop = null
        if (reduceMotion) return
        item.alpha = 0f; item.scaleX = 0.5f; item.scaleY = 0.5f
        item.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(260).setDuration(420)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
    }

    /** "glass": smoky bar with all names. "bare": icons float on the photo, only the bubble is glass. */
    private fun applyDockStyle() {
        val bare = bareDock
        dockPanel.visibility = if (bare) View.INVISIBLE else View.VISIBLE
        dockFrame.elevation = if (bare) 0f else Y(10f)
        bottomFade.visibility = if (bare) View.VISIBLE else View.GONE
        for (d in dockItems) d.bare = bare
        dockFrame.clipToOutline = !bare
        bubble.visibility = if (bare || dockItems.isEmpty()) View.INVISIBLE else View.VISIBLE
        dash.visibility = if (bare && dockItems.isNotEmpty()) View.VISIBLE else View.GONE
        (currentFocus as? DockItem)?.let { moveDashTo(it, false) }
    }

    /** Moves the light ribbon under the focused icon, in that app's accent colour. */
    private fun moveDashTo(v: View, animate: Boolean = true) {
        val g = geo ?: return
        val (row, cx) = dockSlot[v] ?: return
        val accent = apps.firstOrNull { it.pkg == (v as? DockItem)?.pkg }?.accent ?: Color.WHITE
        dash.setActive(true)
        dash.moveTo(cx, accent, animate && !reduceMotion, (row * g.rowH).toFloat(), g.rowH.toFloat())
        ui.removeCallbacks(dockCoach); ui.postDelayed(dockCoach, 4000)
    }

    /** `adb shell am start -n app.vitrine.home/.MainActivity --es dock bare|glass` switches the dock style. */
    private fun handleStyleIntent(i: Intent?): Boolean {
        if (i == null) return false
        val v = i.getStringExtra("dock") ?: return false
        if (v != "bare" && v != "glass") return false
        prefs.edit().putString("dock_style", v).apply()
        i.removeExtra("dock")
        return true
    }

    /** The bubble glides to the focused app and stretches mid-flight like a drop of liquid. */
    private fun moveBubbleTo(v: View) {
        bubble.lit = true
        bubble.animate().alpha(1f).setDuration(120).start()
        // Position from the item's slot, so it is right even before the first layout pass.
        val g = geo ?: return
        val (row, cx) = dockSlot[v] ?: return
        val bw = g.itemW - X(16f).toInt(); val bh = g.rowH - Y(28f).toInt()
        val to = cx - bw / 2f
        val toY = row * g.rowH + (g.rowH - bh) / 2f
        bubbleAnim?.cancel()
        if (!bubblePlaced || bubble.translationY != toY) { bubble.translationX = to; bubble.translationY = toY; bubblePlaced = true; bubble.invalidate(); return }
        val from = bubble.translationX
        bubbleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val t = it.animatedValue as Float
                bubble.translationX = from + (to - from) * t
                val stretch = (sin(PI * t) * 0.14).toFloat()
                bubble.scaleX = 1f + stretch
                bubble.scaleY = 1f - stretch * 0.3f
                bubble.invalidate()
            }
            start()
        }
    }

    private fun bubbleIdle() {
        bubble.post {
            if (currentFocus !is DockItem) { bubble.lit = false; bubble.animate().alpha(0.4f).setDuration(150).start() }
        }
    }

    private fun dockMenu(app: App) {
        val dock = dockList()
        val idx = dock.indexOf(app.pkg)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Move left" to { if (idx > 0) { java.util.Collections.swap(dock, idx, idx - 1); saveDock(dock, app.pkg) } }
        items += "Move right" to { if (idx >= 0 && idx < dock.size - 1) { java.util.Collections.swap(dock, idx, idx + 1); saveDock(dock, app.pkg) } }
        items += "Remove from dock" to { dock.remove(app.pkg); saveDock(dock, null) }
        items += "Change wallpaper" to { ui.post { showWallpapers() } }
        items += (if (bareDock) "Dock style: glass bar" else "Dock style: icons only") to {
            prefs.edit().putString("dock_style", if (bareDock) "glass" else "bare").apply(); applyDockStyle()
        }
        items += "App info" to { safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))); Unit }
        showMenu(app.label, items)
    }

    // ---------- header ----------

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            clipChildren = false; clipToPadding = false
        }
        val time = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = true }
        time.addView(TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, Y(54f))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 1f, Color.argb(90, 0, 0, 0)); includeFontPadding = false
        })
        time.addView(TextClock(this).apply {
            format12Hour = "EEE d MMM"; format24Hour = "EEE d MMM"
            isSingleLine = true; maxLines = 1
            setTextColor(Color.argb(200, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_PX, Y(24f))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(6f, 0f, 1f, Color.argb(90, 0, 0, 0)); includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = X(22f).toInt() })
        greeting = TextView(this).apply {
            setTextColor(Color.argb(215, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_PX, Y(28f))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(6f, 0f, 1f, Color.argb(90, 0, 0, 0)); includeFontPadding = false
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // WRAP_CONTENT, not the default MATCH_PARENT: otherwise the row is clamped to the greeting's width and the date gets cut.
        left.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        left.addView(greeting, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Y(12f).toInt() })
        updateGreeting()
        row.addView(left)
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        addChip(row, GlassIconButton.THEME) { toggleTheme() }
        addChip(row, GlassIconButton.INPUTS) { showInputs() }
        addChip(row, GlassIconButton.WIFI) { openSettings(Settings.ACTION_WIFI_SETTINGS) }
        addChip(row, GlassIconButton.DEVICES) { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
            ?.systemIcon = systemDrawable(BLUETOOTH_ICONS)
        addChip(row, GlassIconButton.SETTINGS) { showSettings() }
            ?.apply { isLongClickable = true; setOnLongClickListener { showWallpapers(); true } }
        return row
    }

    /** Looks up an icon that ships with the TV's own system apps; null if none of them has it. */
    private fun systemDrawable(candidates: List<Pair<String, String>>): android.graphics.drawable.Drawable? {
        for ((pkg, name) in candidates) {
            try {
                val res = packageManager.getResourcesForApplication(pkg)
                val id = res.getIdentifier(name, "drawable", pkg)
                if (id != 0) {
                    val d = res.getDrawable(id, null)
                    // Some system icons need their app's theme; make sure this one can draw on its own.
                    val n = 48
                    val test = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, n, n); d.draw(Canvas(test))
                    val px = IntArray(n * n); test.getPixels(px, 0, n, 0, 0, n, n); test.recycle()
                    val cover = px.count { (it ushr 24) > 128 }.toFloat() / (n * n)
                    // A line icon covers roughly 8-40% of its box; a solid disc or square covers far more.
                    if (cover in 0.05f..0.40f) return d
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun addChip(row: LinearLayout, glyph: Int, action: () -> Unit): GlassIconButton? {
        val b = GlassIconButton(this, glass, glyph).apply {
            rest = Material(Color.argb(((this@MainActivity.rest.tint ushr 24) + 14).coerceAtMost(255), 255, 255, 255), 130, 1.1f, 30)
            focus = this@MainActivity.focus
            outlineOnly = true
            setOnClickListener { action() }
        }
        val s = Y(64f).toInt()
        row.addView(b, LinearLayout.LayoutParams(s, s).apply { marginStart = X(16f).toInt() })
        chips.add(b)
        return b
    }

    // ---------- apps ----------

    private fun reloadApps(focusPkg: String?) {
        appsDirty = false
        val firstLoad = dockItems.isEmpty()
        val dockPkgs = dockList().toSet()
        Thread {
            // First launch: load just the dock's apps and show the dock straight away;
            // the rest of All apps fills in behind it.
            if (firstLoad) {
                val quick = queryApps(dockPkgs)
                runOnUiThread { if (dockItems.isEmpty()) { apps = quick; buildDock(focusPkg) } }
            }
            val loaded = queryApps()
            runOnUiThread {
                apps = loaded
                val want = dockList().filter { p -> loaded.any { it.pkg == p } }.take(MAX_DOCK)
                if (focusPkg != null || dockItems.map { it.pkg } != want) buildDock(focusPkg)
                buildGrid(null)
            }
        }.start()
    }

    /** `only`: restrict to these packages (the dock's fast first pass). */
    private fun queryApps(only: Set<String>? = null): List<App> {
        val pm = packageManager
        val map = LinkedHashMap<String, App>()
        val cacheDir = File(cacheDir, "mono")
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            @Suppress("DEPRECATION")
            val found = pm.queryIntentActivities(q, 0)
            for (ri in found) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || pkg in EXCLUDE || pkg in cfg.hideApps || pkg == cfg.liveTvPackage || map.containsKey(pkg)) continue
                if (only != null && pkg !in only) continue
                val launch = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val icon = ri.loadIcon(pm)
                val stamp = try { @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0).lastUpdateTime } catch (_: Exception) { 0L }
                val mono = Mono.make(icon, File(cacheDir, "${pkg}_${stamp}_${ri.activityInfo.name.hashCode()}_v3.png"), 192)
                val system = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // Large logo for the All apps showcase: the same icon loaded at the highest density the app ships.
                val hi = (try {
                    val resId = if (ri.iconResource != 0) ri.iconResource else ri.activityInfo.applicationInfo.icon
                    pm.getResourcesForApplication(ri.activityInfo.applicationInfo)
                        .getDrawableForDensity(resId, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)?.mutate()
                } catch (_: Exception) { null }) ?: icon.constantState?.newDrawable()?.mutate() ?: icon
                val hiSrc = if (hi is android.graphics.drawable.AdaptiveIconDrawable || hi.intrinsicWidth >= 288) hi else try {
                    val bRes = if (ri.activityInfo.bannerResource != 0) ri.activityInfo.bannerResource else ri.activityInfo.applicationInfo.banner
                    val banner = if (bRes != 0) pm.getResourcesForApplication(ri.activityInfo.applicationInfo)
                        .getDrawableForDensity(bRes, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)?.mutate() else null
                    if (banner != null && banner.intrinsicWidth >= 320 && banner.intrinsicWidth > hi.intrinsicWidth * 1.5f) banner else hi
                } catch (_: Exception) { hi }
                // Dock: always the automatic white glyph (falls back to the large one if the small one failed).
                // All apps: your logo file (icons/<package>.png) if you supplied one; otherwise the app's own
                // icon in colour with its tile removed; otherwise the plain icon with rounded corners.
                val custom = customIcon(pkg)
                val needWhite = mono == null
                val hiMono = if (needWhite) Mono.make(hiSrc, File(cacheDir, "${pkg}_${stamp}_${ri.activityInfo.name.hashCode()}_hi3.png"), 384) else null
                val bigArt = if (custom != null) IconArt(custom, hi) else {
                    val cut = Mono.make(hiSrc, File(cacheDir, "${pkg}_${stamp}_${ri.activityInfo.name.hashCode()}_cut2.png"), 384, colour = true)
                    IconArt(cut, hiSrc)
                }
                map[pkg] = App(pkg, ri.loadLabel(pm).toString(), IconArt(mono ?: hiMono, icon, themed = true), launch, system, Accent.of(icon), bigArt)
            }
        }
        return map.values.toList()
    }

    /** Loads icons/<pkg>.png if you supplied one, trimmed to its visible pixels (any padding removed). */
    private fun customIcon(pkg: String): Bitmap? {
        val f = File(getExternalFilesDir(null), "icons/$pkg.png")
        if (!f.exists()) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 512) sample *= 2
            val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val w = b.width; val h = b.height
            val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 16) {
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            if (maxX < 0) null else Bitmap.createBitmap(b, minX, minY, maxX - minX + 1, maxY - minY + 1)
        } catch (_: Exception) { null }
    }

    private fun order(): MutableList<String> {
        val s = prefs.getString("order", null) ?: return cfg.appOrder.ifEmpty { DEFAULT_ORDER }.toMutableList()
        return s.split(',').filter { it.isNotBlank() }.toMutableList()
    }

    private fun set(key: String): Set<String> = prefs.getStringSet(key, emptySet()) ?: emptySet()

    /** Home shows what you watch; preinstalled utilities go to More. Either can be overridden. */
    private fun isUtility(a: App): Boolean {
        if (a.pkg in set("toHome")) return false
        if (a.pkg in set("toMore")) return true
        if (a.pkg in UTILITY || a.pkg in cfg.moreApps) return true
        return a.system && a.pkg !in ENTERTAINMENT && a.pkg !in cfg.homeApps
    }

    private fun sorted(list: List<App>): List<App> {
        val byPkg = list.associateBy { it.pkg }
        val out = ArrayList<App>()
        for (p in order()) byPkg[p]?.let { out.add(it) }
        val seen = out.map { it.pkg }.toHashSet()
        out += list.filter { it.pkg !in seen }.sortedBy { it.label.lowercase() }
        return out
    }

    private fun homeApps() = sorted(apps.filter { !isUtility(it) && it.pkg !in set("hidden") })
    private fun moreApps() = sorted(apps.filter { isUtility(it) && it.pkg !in set("hidden") })

    private fun styleTile(t: AppTile) {
        t.radius = Y(36f); t.rest = rest; t.focus = focus
        t.lens = true; t.focusScale = 1.08f; t.liftDp = 22f
    }

    private fun buildGrid(focusPkg: String?) {
        val keep = focusPkg ?: wheelApps.getOrNull(wheel.index)?.pkg
        wheelApps = homeApps() + moreApps()
        wheel.entries = wheelApps.map { AppWheel.Entry(it.pkg, it.label, it.hiArt, it.accent) }
        wheel.select(keep)
    }

    /** Lays entries out three to a row. A null App means a built-in tile (More). */
    private fun layoutTiles(into: LinearLayout, entries: List<Triple<String, String, App?>>, out: ArrayList<AppTile>,
                            wire: (String, App?, AppTile) -> Unit) {
        val cols = 3
        val gapX = X(48f).toInt(); val gapY = Y(48f).toInt()
        val tileW = (screenW - 2 * side - gapX * (cols - 1)) / cols
        val tileH = Y(220f).toInt()
        val moreArt = IconArt(null, getDrawable(R.drawable.ic_more)!!)
        var row: LinearLayout? = null
        entries.forEachIndexed { i, (pkg, label, app) ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                into.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileH).apply { bottomMargin = gapY })
            }
            val tile = AppTile(this, glass, pkg, label, app?.art ?: moreArt, Y(28f))
            styleTile(tile)
            wire(pkg, app, tile)
            row!!.addView(tile, LinearLayout.LayoutParams(tileW, tileH).apply { if (i % cols != cols - 1) marginEnd = gapX })
            out.add(tile)
        }
    }

    // ---------- overlays: More sheet and glass menus ----------

    private fun openOverlay(v: View, firstFocus: View?) {
        closeOverlay(false)
        returnFocus = currentFocus
        home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay = v
        v.alpha = 0f
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.animate().alpha(1f).setDuration(180).start()
        firstFocus?.post { firstFocus.requestFocus() }
    }

    private fun closeOverlay(restore: Boolean) {
        val v = overlay ?: return
        overlay = null; overlayTiles.clear()
        root.removeView(v)
        if (Build.VERSION.SDK_INT >= 31) home.setRenderEffect(null)
        home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (appsDirty) reloadApps(null)
        if (restore) (returnFocus?.takeIf { it.isAttachedToWindow } ?: (if (gridOpen) wheel else preferredDock()))?.requestFocus()
    }

    private fun showMore() {
        val sheet = FrameLayout(this).apply { setBackgroundColor(Color.argb(120, 0, 0, 0)); isClickable = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(TextView(this).apply {
            text = "More"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, Y(44f))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        val lift = X(26f).toInt()
        val sc = object : ScrollView(this) {
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt); for (g in overlayTiles) g.invalidate()
            }
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFocusable = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(Y(36f).toInt()) }
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(lift, Y(24f).toInt(), lift, Y(44f).toInt()) }
        sc.addView(g, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { leftMargin = -lift; rightMargin = -lift })
        sheet.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = side; rightMargin = side; topMargin = Y(80f).toInt()
        })
        val list = ArrayList<AppTile>()
        val entries = moreApps().map { Triple<String, String, App?>(it.pkg, it.label, it) }
        layoutTiles(g, entries, list) { _, app, tile ->
            if (app != null) {
                tile.setOnClickListener { closeOverlay(false); safeStart(app.intent) }
                tile.setOnLongClickListener { appMenu(app, true); true }
            }
        }
        overlayTiles = list
        openOverlay(sheet, list.firstOrNull())
    }

    /** Glass menu: dimmed screen, a glass panel, one glass row per action. Back closes it. */
    private fun showMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        val dim = FrameLayout(this).apply { setBackgroundColor(Color.argb(110, 0, 0, 0)); isClickable = true }
        val panel = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val slab = GlassView(this, glass).apply {
            isFocusable = false; isClickable = false; isLongClickable = false; lens = true
            radius = Y(34f)
            rest = Theme.card(Color.argb(70, 30, 30, 34))
        }
        panel.addView(slab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            val p = Y(18f).toInt(); setPadding(p, p, p, p)
        }
        col.addView(TextView(this).apply {
            text = title; setTextColor(Theme.ink(170)); setTextSize(TypedValue.COMPLEX_UNIT_PX, Y(24f))
            setPadding(X(28f).toInt(), Y(10f).toInt(), X(28f).toInt(), Y(12f).toInt())
        })
        var first: View? = null
        for ((label, action) in items) {
            val row = MenuRow(this, glass, label, Y(30f)).apply {
                radius = Y(22f); focus = Theme.row()
                setOnClickListener { closeOverlay(true); action() }
            }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Y(74f).toInt()))
            if (first == null) first = row
        }
        panel.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dim.addView(panel, FrameLayout.LayoutParams(X(620f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        openOverlay(dim, first)
    }

    private fun appMenu(app: App, inMore: Boolean) {
        val cur = (if (inMore) moreApps() else homeApps()).map { it.pkg }.toMutableList()
        val idx = cur.indexOf(app.pkg)
        val items = ArrayList<Pair<String, () -> Unit>>()
        val dock = dockList()
        if (app.pkg in dock) items += "Remove from dock" to { dock.remove(app.pkg); saveDock(dock, null) }
        else if (dock.size < MAX_DOCK) items += "Add to dock" to { dock.add(app.pkg); pendingPop = app.pkg; saveDock(dock, null) }
        if (!inMore) {
            items += "Move up" to { move(cur, idx, idx - 1, app.pkg) }
            items += "Move down" to { move(cur, idx, idx + 1, app.pkg) }
            items += "Move to More" to { flip(app.pkg, toMore = true) }
        } else items += "Move to Home" to { flip(app.pkg, toMore = false) }
        items += "Hide" to { prefs.edit().putStringSet("hidden", set("hidden") + app.pkg).apply(); buildGrid(null) }
        if (set("hidden").isNotEmpty()) items += "Show hidden apps (${set("hidden").size})" to {
            prefs.edit().putStringSet("hidden", emptySet()).apply(); buildGrid(app.pkg)
        }
        items += "App info" to { safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))); Unit }
        showMenu(app.label, items)
    }

    private fun flip(pkg: String, toMore: Boolean) {
        val home = set("toHome").toMutableSet(); val more = set("toMore").toMutableSet()
        if (toMore) { home.remove(pkg); more.add(pkg) } else { more.remove(pkg); home.add(pkg) }
        prefs.edit().putStringSet("toHome", home).putStringSet("toMore", more).apply()
        buildGrid(null)
    }

    private fun move(cur: MutableList<String>, from: Int, to: Int, pkg: String) {
        if (from < 0 || to < 0 || to >= cur.size) return
        val p = cur.removeAt(from); cur.add(to, p)
        prefs.edit().putString("order", cur.joinToString(",")).apply()
        buildGrid(pkg)
    }

    // ---------- calm Home: idle fade, entry motion, focus memory ----------

    private fun preferredDock(): DockItem? {
        val last = prefs.getString("last_dock", null)
        return dockItems.firstOrNull { it.pkg == last } ?: dockItems.firstOrNull()
    }

    private fun rememberDock(pkg: String) {
        if (dockItems.any { it.pkg == pkg }) prefs.edit().putString("last_dock", pkg).apply()
    }

    /** Any key: wake the chrome and restart the 20 s idle timer. */
    private fun poke() {
        ui.removeCallbacks(quietRunnable)
        if (quiet) setQuiet(false)
        if (resumedNow) ui.postDelayed(quietRunnable, 20_000L)
    }

    /** Idle: only the photo, the clock and a dimmed dock remain. */
    private fun setQuiet(q: Boolean) {
        if (q && (gridOpen || overlay != null || deviceCard != null || !resumedNow)) { poke(); return }
        if (q == quiet) return
        quiet = q
        val d = if (q) 1200L else 260L
        val fades = ArrayList<View>(chips); fades.add(widgetsView); fades.add(hintView); fades.add(greeting)
        for (v in fades) v.animate().alpha(if (q) 0f else 1f).setStartDelay(0).setDuration(d).start()
        if (dockFrame.visibility == View.VISIBLE) dockFrame.animate().alpha(if (q) 0.35f else 1f).setStartDelay(0).setDuration(d).start()
        if (nowBar.visibility == View.VISIBLE) nowBar.animate().alpha(if (q) 0.6f else 1f).setStartDelay(0).setDuration(d).start()
    }

    /** Returning to Home: header, widgets, dock and hint rise in one after another. */
    private fun playEntry() {
        quiet = false
        for (v in chips) { v.animate().cancel(); v.alpha = 1f }
        greeting.alpha = 1f
        (hintView as? HintView)?.replay()
        if (reduceMotion || safeMode) {
            for (v in listOf(headerView, widgetsView, nowBar, dockFrame, hintView)) { v.animate().cancel(); v.translationY = 0f; if (v.visibility == View.VISIBLE) v.alpha = 1f }
            return
        }
        val seq = listOf(headerView, widgetsView, nowBar, dockFrame, hintView)
        var i = 0
        for (v in seq) {
            if (v.visibility != View.VISIBLE) continue
            v.animate().cancel()
            v.alpha = 0f; v.translationY = Y(14f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(40L + i * 55L).setDuration(420)
                .setInterpolator(DecelerateInterpolator(2f)).start()
            i++
        }
    }

    private fun showNowBar(show: Boolean) {
        if (safeMode && show) return
        val visible = nowBar.visibility == View.VISIBLE
        if (show && !visible) {
            ui.postDelayed({ if (nowBar.visibility == View.VISIBLE) coach("coach_now", "Press Up from the dock to control what's playing") }, 1200)
            nowBar.visibility = View.VISIBLE; nowBar.alpha = 0f; nowBar.translationY = if (reduceMotion) 0f else Y(16f)
            nowBar.animate().alpha(if (quiet) 0.6f else 1f).translationY(0f).setStartDelay(0).setDuration(320)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        } else if (!show && visible) {
            if (nowBar.hasFocus()) preferredDock()?.requestFocus()
            nowBar.animate().alpha(0f).translationY(Y(16f)).setStartDelay(0).setDuration(220)
                .withEndAction { if (nowBar.alpha == 0f) nowBar.visibility = View.GONE }.start()
        }
    }

    // ---------- theme, evening, coach hints ----------

    private fun toggleTheme() {
        prefs.edit().putBoolean("theme_light", !Theme.light).putBoolean("focus_theme_chip", true).apply()
        recreate()
    }

    /** 9 pm to 6 am: fade the warm veil in (over 2 s), otherwise out. */
    private fun updateEvening(animate: Boolean) {
        if (!::eveningVeil.isInitialized) return
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val on = eveningOn && !safeMode && (h >= 21 || h < 6)
        val to = if (on) 1f else 0f
        if (eveningVeil.alpha == to) return
        if (animate) eveningVeil.animate().alpha(to).setDuration(2000).start() else eveningVeil.alpha = to
    }

    private val dockCoach = Runnable {
        if (currentFocus is DockItem && overlay == null && !gridOpen) coach("coach_dock", "Hold OK on a dock app to move or remove it")
    }

    /**
     * A one-time hint: a small dark pill under the header, 6 s, never shown again for that key.
     * Only one at a time; a hint that can't show now will show next time.
     */
    private fun coach(key: String, text: String, always: Boolean = false) {
        if (!always && prefs.getBoolean(key, false)) return
        if (coachView != null || !resumedNow) return
        prefs.edit().putBoolean(key, true).apply()
        val tv = TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, Y(24f))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            val ph = X(30f).toInt(); val pv = Y(14f).toInt(); setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply { cornerRadius = Y(40f); setColor(Color.argb(170, 12, 12, 14)); setStroke(maxOf(1, Y(1.2f).toInt()), Color.argb(70, 255, 255, 255)) }
            isFocusable = false; alpha = 0f
        }
        coachView = tv
        root.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = Y(160f).toInt()
        })
        tv.animate().alpha(1f).setDuration(300).start()
        ui.postDelayed({
            tv.animate().alpha(0f).setDuration(400).withEndAction { root.removeView(tv); if (coachView === tv) coachView = null }.start()
        }, 6000)
    }

    // ---------- HDMI device card ----------

    private fun watchInputs() {
        if (tvCallback != null) return
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return
        val cb = object : TvInputManager.TvInputCallback() {
            override fun onInputStateChanged(inputId: String, state: Int) {
                if (state != TvInputManager.INPUT_STATE_CONNECTED) return
                val info = try { tim.getTvInputInfo(inputId) } catch (_: Exception) { null } ?: return
                if (!info.isPassthroughInput || info.isHidden(this@MainActivity)) return
                showDeviceCard(inputLabel(info), inputId)
            }
        }
        try { tim.registerCallback(cb, ui); tvCallback = cb } catch (_: Exception) {}
    }

    private fun showDeviceCard(label: String, inputId: String) {
        if (!resumedNow || overlay != null || gridOpen) return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastCardAt[inputId] ?: -60_000L) < 60_000L) return   // one card per device per minute
        lastCardAt[inputId] = now
        dismissDeviceCard(false)
        poke()
        val card = DeviceCard(this, glass, label, inputKind(inputId)).apply {
            rest = Theme.card(Color.argb(70, 30, 30, 34))
            focus = Theme.row()
            setOnClickListener {
                dismissDeviceCard(false)
                if (!safeStart(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId)))) openLiveTv()
            }
        }
        deviceCard = card
        card.alpha = 0f; card.translationY = -Y(24f)
        root.addView(card, FrameLayout.LayoutParams(X(720f).toInt(), Y(118f).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = Y(56f).toInt()
        })
        card.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
        card.post { card.requestFocus(); card.onBlur = { ui.post { if (deviceCard === card) dismissDeviceCard(false) } } }
        ui.removeCallbacks(dismissCard); ui.postDelayed(dismissCard, 10_000L)
    }

    private fun dismissDeviceCard(restoreFocus: Boolean) {
        val c = deviceCard ?: return
        deviceCard = null
        ui.removeCallbacks(dismissCard)
        c.onBlur = null
        c.animate().alpha(0f).translationY(-Y(24f)).setDuration(200).withEndAction { root.removeView(c) }.start()
        if (restoreFocus) preferredDock()?.requestFocus()
    }

    // ---------- glass Settings sheet ----------

    /** Right-side glass panel: live Volume / Wi-Fi / Bluetooth status and shortcuts into each system page. */
    private fun showSettings() {
        val sheet = FrameLayout(this).apply {
            isClickable = true
            background = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(Color.argb(190, 0, 0, 0), Color.argb(120, 0, 0, 0)))
        }
        val panel = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        panel.addView(GlassView(this, glass).apply {
            isFocusable = false; isClickable = false; isLongClickable = false; lens = true
            radius = Y(40f); rest = Theme.card(Color.argb(80, 26, 28, 32))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(TextView(this).apply {
            text = "Settings"; setTextColor(Theme.ink); setTextSize(TypedValue.COMPLEX_UNIT_PX, Y(40f))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(X(40f).toInt(), Y(34f).toInt(), X(40f).toInt(), Y(10f).toInt())
        })
        val rows = ArrayList<SettingRow>()
        val sc = object : ScrollView(this) {
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt); for (r in rows) r.invalidate()
            }
        }.apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFocusable = false
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(Y(40f).toInt())
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(X(14f).toInt(), Y(4f).toInt(), X(14f).toInt(), Y(30f).toInt())
        }
        sc.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val rowH = Y(92f)
        fun section(t: String) {
            list.addView(TextView(this).apply {
                text = t.uppercase(Locale.getDefault()); letterSpacing = 0.08f
                setTextColor(Theme.ink(140)); setTextSize(TypedValue.COMPLEX_UNIT_PX, Y(19f))
                setPadding(X(26f).toInt(), Y(22f).toInt(), 0, Y(8f).toInt())
            })
        }
        fun row(glyph: Int, title: String, sub: String?, action: (() -> Unit)?): SettingRow {
            val r = SettingRow(this, glass, glyph, title, rowH).apply {
                radius = Y(24f); focus = Theme.row()
                subtitle = sub
                if (action != null) setOnClickListener { closeOverlay(true); action() }
            }
            list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH.toInt()))
            rows.add(r)
            return r
        }

        if (safeMode) {
            section("Safe mode")
            row(SettingRow.RESTART, "Exit safe mode", "Vitrine Home restarted after crashes. Turn everything back on") {
                Safety.clear(this); recreate()
            }
        }
        section("Controls")
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val vol = row(SettingRow.VOLUME, "Volume", null, null)
        if (am.isVolumeFixed) {
            vol.subtitle = "Use the remote's volume buttons"
            vol.setOnClickListener { closeOverlay(true); openSettings(Settings.ACTION_SOUND_SETTINGS) }
        } else {
            fun readVol() {
                val st = android.media.AudioManager.STREAM_MUSIC
                val max = am.getStreamMaxVolume(st).coerceAtLeast(1)
                vol.muted = try { am.isStreamMute(st) } catch (_: Exception) { false }
                vol.level = am.getStreamVolume(st) / max.toFloat()
            }
            readVol()
            vol.onAdjust = { d ->
                try {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        if (d > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, 0)
                } catch (_: Exception) {}
                readVol()
            }
            vol.setOnClickListener {
                try { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0) } catch (_: Exception) {}
                readVol()
            }
        }
        row(SettingRow.WIFI, "Wi-Fi", wifiStatus()) { openSettings(Settings.ACTION_WIFI_SETTINGS) }
        val bt = row(SettingRow.BLUETOOTH, "Bluetooth", "Checking…") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        bluetoothStatus { bt.subtitle = it }
        row(SettingRow.INPUTS, "Inputs", "HDMI and Live TV") { showInputs() }
        cfg.sleepTimerAction?.let { action ->
            row(SettingRow.SLEEP, "Sleep timer", "Turn the TV off after a set time") { openFirst(action, Settings.ACTION_SETTINGS) }
        }

        section("Picture & sound")
        row(SettingRow.PICTURE, "Picture", "Picture mode, brightness, colour") { openFirst("com.android.settings.action.PICTURE_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS) }
        row(SettingRow.SOUND, "Sound", "Sound mode, speakers, Dolby Atmos") { openFirst("com.android.settings.action.SOUND_SETTINGS", Settings.ACTION_SOUND_SETTINGS) }
        row(SettingRow.WALLPAPER, "Wallpaper", "Choose the Home photo") { showWallpapers() }
        row(SettingRow.SLEEP, "Screensaver", "Pick \"Vitrine Home\": your photos and clock") {
            openFirst(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)
        }

        section("System")
        row(SettingRow.APPS, "Apps", if (apps.isNotEmpty()) "${apps.size} apps installed" else null) { openSettings(Settings.ACTION_APPLICATION_SETTINGS) }
        row(SettingRow.STORAGE, "Storage", storageStatus()) { openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) }
        val bf = File(getExternalFilesDir(null), "backup.json")
        val bsub = if (bf.exists()) "Saved automatically · last at " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(bf.lastModified()))
                   else "Saved automatically after each change"
        val brow = row(SettingRow.APPS, "Backup of this setup", bsub, null)
        brow.setOnClickListener { Thread { Backup.export(this); runOnUiThread { brow.subtitle = "Saved just now · copy it to the laptop with backup_tv.bat" } }.start() }
        val auto = try { Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME, 1) == 1 } catch (_: Exception) { true }
        row(SettingRow.CLOCK, "Date & time", if (auto) "Set automatically" else "Set manually") { openSettings(Settings.ACTION_DATE_SETTINGS) }
        row(SettingRow.INFO, "About", "Android ${Build.VERSION.RELEASE}") { openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS) }
        row(SettingRow.RESTART, "Restart", "Full restart, shows the start-up logos") {
            if (!safeStart(Intent("com.android.tv.settings.REBOOT_DEVICE"))) openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
        }
        section("Display")
        fun toggleRow(glyph: Int, title: String, sub: String, key: String, def: Boolean, after: () -> Unit) {
            val r = row(glyph, title, sub, null)
            r.toggle = prefs.getBoolean(key, def)
            r.setOnClickListener {
                val v = !prefs.getBoolean(key, def)
                prefs.edit().putBoolean(key, v).apply(); r.toggle = v; after()
            }
        }
        toggleRow(SettingRow.SLEEP, "Evening mode", "Dims and warms Home after 9 pm", "evening", true) { updateEvening(true) }
        toggleRow(SettingRow.PICTURE, "Reduce motion", "Fades only: no slides, stretch or bounce", "reduce_motion", false) {}
        row(SettingRow.GEAR, "All settings", null) { openSettings(Settings.ACTION_SETTINGS) }

        sheet.addView(panel, FrameLayout.LayoutParams(X(660f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            topMargin = Y(48f).toInt(); bottomMargin = Y(48f).toInt(); rightMargin = Y(48f).toInt()
        })
        panel.translationX = if (reduceMotion) 0f else X(90f)
        openOverlay(sheet, rows.firstOrNull())
        // Blur Home (dock, widgets, header) behind the panel so nothing competes with it.
        if (Build.VERSION.SDK_INT >= 31 && !safeMode) try {
            home.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(Y(22f), Y(22f), android.graphics.Shader.TileMode.CLAMP))
        } catch (_: Exception) {}
        panel.animate().translationX(0f).setInterpolator(DecelerateInterpolator(2f)).setDuration(320)
            .setUpdateListener { for (r in rows) r.invalidate() }.start()
    }

    private fun wifiStatus(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Not connected"
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> {
                    // The network name is hidden while Location is off (Android privacy rule), so show signal strength.
                    @Suppress("DEPRECATION")
                    val info = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                    val ssid = info?.ssid?.trim('"')
                    @Suppress("DEPRECATION")
                    val bars = if (info != null) WifiManager.calculateSignalLevel(info.rssi, 4) else -1
                    val signal = when (bars) { 3 -> "Excellent signal"; 2 -> "Good signal"; 1 -> "Fair signal"; 0 -> "Weak signal"; else -> null }
                    val name = if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") "Connected" else ssid
                    if (signal != null) "$name · $signal" else name
                }
                else -> "Connected"
            }
        } catch (_: Exception) { "Open Wi-Fi settings" }
    }

    /** "Off", "On", or "On · <connected audio device>" (A2DP: soundbars, headphones, speakers). */
    private fun bluetoothStatus(done: (String) -> Unit) {
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val ad = bm?.adapter
            if (ad == null) { done("Not available"); return }
            if (!ad.isEnabled) { done("Off"); return }
            done("On")
            ad.getProfileProxy(this, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    val names = try { proxy.connectedDevices.mapNotNull { it.name } } catch (_: SecurityException) { emptyList() }
                    runOnUiThread { if (names.isNotEmpty()) done("On · " + names.joinToString(", ")) }
                    try { ad.closeProfileProxy(profile, proxy) } catch (_: Exception) {}
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, android.bluetooth.BluetoothProfile.A2DP)
        } catch (_: Exception) { done("Open Bluetooth settings") }
    }

    private fun storageStatus(): String? {
        return try {
            val st = StatFs(Environment.getDataDirectory().path)
            val gb = 1_000_000_000.0
            String.format(Locale.US, "%.1f GB free of %.0f GB", st.availableBytes / gb, st.totalBytes / gb)
        } catch (_: Exception) { null }
    }

    // ---------- inputs ----------

    private fun passthroughInputs(): List<TvInputInfo> {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        return try { tim?.tvInputList?.filter { it.isPassthroughInput && !it.isHidden(this) } ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    /** Your name for an input ("PS5"), else the TV's custom name, else its port name ("HDMI 1"). */
    private fun inputLabel(info: TvInputInfo): String =
        prefs.getString("input_name_" + info.id, null)
            ?: (try { info.loadCustomLabel(this) } catch (_: Exception) { null } ?: info.loadLabel(this)).toString()

    private fun inputKind(id: String): String = prefs.getString("input_kind_" + id, null) ?: "tv"

    private fun showInputs() {
        val list = passthroughInputs()
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (inp in list) items += inputLabel(inp) to {
            if (!safeStart(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inp.id)))) openLiveTv()
        }
        items += "Live TV" to { openLiveTv() }
        if (list.isNotEmpty()) items += "Rename inputs…" to { ui.post { showRenameInputs() }; Unit }
        showMenu("Inputs", items)
    }

    private fun showRenameInputs() {
        val items = passthroughInputs().map { inp ->
            val port = inp.loadLabel(this).toString()
            val cur = inputLabel(inp)
            (if (cur != port) "$port  ·  $cur" else port) to { ui.post { showNamePresets(inp) }; Unit }
        }
        showMenu("Rename which input?", items)
    }

    private fun showNamePresets(inp: TvInputInfo) {
        val presets = listOf(
            "PS5" to "console", "Xbox" to "console", "Nintendo Switch" to "console",
            "Set-top box" to "box", "Cable box" to "box", "Fire TV Stick" to "stick", "Apple TV" to "stick",
            "Laptop" to "laptop", "Soundbar" to "speaker"
        )
        val items = ArrayList<Pair<String, () -> Unit>>()
        for ((name, kind) in presets) items += name to {
            prefs.edit().putString("input_name_" + inp.id, name).putString("input_kind_" + inp.id, kind).apply()
        }
        items += "Original name (${inp.loadLabel(this)})" to {
            prefs.edit().remove("input_name_" + inp.id).remove("input_kind_" + inp.id).apply()
        }
        showMenu("Name for ${inp.loadLabel(this)}", items)
    }

    /** Opens the first of these settings pages this TV has; otherwise Settings itself. */
    private fun openFirst(vararg actions: String) {
        for (a in actions) if (safeStart(Intent(a))) return
        safeStart(Intent(Settings.ACTION_SETTINGS))
    }

    /** Opens a settings page; if this TV's Settings app doesn't have it, opens Settings itself. */
    private fun openSettings(action: String) {
        if (!safeStart(Intent(action))) safeStart(Intent(Settings.ACTION_SETTINGS))
    }

    /** Your configured Live TV app, else the standard Android TV channel viewer, else a known Live TV app. */
    private fun openLiveTv() {
        val pkgs = listOfNotNull(cfg.liveTvPackage) + LIVE_TV_APPS
        for (p in pkgs) {
            val i = packageManager.getLeanbackLaunchIntentForPackage(p) ?: packageManager.getLaunchIntentForPackage(p)
            if (i != null && safeStart(i)) return
        }
        if (!safeStart(Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI))) safeStart(Intent(Settings.ACTION_SETTINGS))
    }

    private fun safeStart(i: Intent): Boolean {
        return try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) {
            val pkg = i.component?.packageName ?: i.`package`
            val alt = pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it) }
            if (alt != null) try { startActivity(alt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false } else false
        }
    }

    // ---------- wallpaper ----------

    /** Up to 10 wallpapers, files/wallpapers/1.jpg … 10.jpg (sent by set_wallpaper.bat); else wallpaper.jpg. */
    private fun wallpaperFiles(): List<Pair<Int, File>> {
        val dir = getExternalFilesDir(null) ?: return emptyList()
        val re = Regex("^(\\d{1,2})\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
        val numbered = File(dir, "wallpapers").listFiles()?.mapNotNull { f ->
            re.find(f.name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..10 }?.let { it to f }
        }?.sortedBy { it.first }?.distinctBy { it.first } ?: emptyList()
        if (numbered.isNotEmpty()) return numbered
        return File(dir, "wallpaper.jpg").let { if (it.exists()) listOf(1 to it) else emptyList() }
    }

    private fun decodeScaled(path: String, w: Int, h: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        var sample = 1
        while (b.outWidth / (sample * 2) >= w && b.outHeight / (sample * 2) >= h) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    /** Glass sheet with the wallpapers as numbered thumbnails; the current one is ringed. */
    private fun showWallpapers() {
        val files = wallpaperFiles()
        if (files.isEmpty()) { showMenu("Wallpapers", listOf("Add photos with set_wallpaper.bat on your PC" to {})); return }
        val cur = prefs.getInt("wp_n", files.first().first)
        val sheet = FrameLayout(this).apply { setBackgroundColor(Color.argb(130, 0, 0, 0)); isClickable = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(TextView(this).apply {
            text = "Wallpaper"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, Y(44f))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        val cols = 5; val gap = X(32f).toInt()
        val tw = (screenW - 2 * side - gap * (cols - 1)) / cols; val th = tw * 9 / 16
        var row: LinearLayout? = null
        var first: View? = null
        val thumbs = ArrayList<GlassView>()
        files.forEachIndexed { i, (n, f) ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, th + Y(56f).toInt()).apply { topMargin = Y(28f).toInt() })
            }
            val bmp = decodeScaled(f.path, tw, th)
            val t = WallpaperThumb(this, glass, bmp, n, n == cur, Y(22f)).apply {
                radius = Y(22f); rest = this@MainActivity.rest; focus = this@MainActivity.focus; focusScale = 1.07f
                setOnClickListener { prefs.edit().putInt("wp_n", n).apply(); closeOverlay(false); recreate() }
            }
            row!!.addView(t, LinearLayout.LayoutParams(tw, th + Y(56f).toInt()).apply { if (i % cols != cols - 1) marginEnd = gap })
            thumbs.add(t)
            if (n == cur || first == null) first = t
        }
        sheet.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = side; rightMargin = side; topMargin = Y(150f).toInt()
        })
        overlayTiles.clear()
        openOverlay(sheet, first)
    }

    private fun loadWallpaper(w: Int, h: Int): Bitmap {
        val files = wallpaperFiles()
        val want = prefs.getInt("wp_n", files.firstOrNull()?.first ?: 1)
        val chosen = files.firstOrNull { it.first == want } ?: files.firstOrNull()
        var src: Bitmap? = chosen?.let { decodeScaled(it.second.path, w, h) }
        if (src == null) {
            src = BitmapFactory.decodeResource(resources, R.drawable.wallpaper,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inScaled = false })
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val s = maxOf(w / src!!.width.toFloat(), h / src.height.toFloat())
        val dw = src.width * s; val dh = src.height * s
        val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        Canvas(out).drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    companion object {
        /** Common Live TV apps (AOSP Live Channels, Google's Live TV). Add yours in config.json. */
        private val LIVE_TV_APPS = listOf("com.google.android.tv", "com.android.tv")
        /** Where Android TV builds keep their Bluetooth icon (Settings first, then System UI). */
        private val BLUETOOTH_ICONS = listOf(
            "com.android.tv.settings" to "ic_bluetooth",
            "com.android.tv.settings" to "ic_bluetooth_raw",
            "com.android.tv.settings" to "ic_settings_bluetooth",
            "com.android.tv.settings" to "ic_bt_connected",
            "com.android.systemui" to "ic_qs_bluetooth_on",
            "com.android.systemui" to "stat_sys_data_bluetooth",
            "com.android.systemui" to "ic_bluetooth_connected",
            "com.android.settings" to "ic_settings_bluetooth"
        )
        private val EXCLUDE = setOf(
            "com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith",
            "com.android.tv.settings", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher"
        )
        private const val MAX_DOCK = 20
        private const val DOCK_PER_ROW = 10
        /** First-run dock: whichever of these are installed, in this order (then edit it with hold OK). */
        private val DEFAULT_DOCK = listOf(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.wbd.stream", "com.plexapp.android", "com.spotify.tv.android"
        )
        private val DEFAULT_ORDER = listOf(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.wbd.stream", "com.hulu.livingroomplus", "com.plexapp.android",
            "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "org.xbmc.kodi"
        )
        /** Preinstalled apps that are entertainment, so they stay on Home. */
        private val ENTERTAINMENT = setOf(
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.google.android.youtube.tvmusic",
            "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.wbd.stream", "com.hulu.livingroomplus",
            "com.plexapp.android", "com.spotify.tv.android", "com.mxtech.videoplayer.television", "org.videolan.vlc",
            "org.xbmc.kodi"
        )
        private val UTILITY = setOf(
            "com.android.vending", "com.google.android.play.games"
        )
    }
}
