package app.vitrine.home

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Crash safety net for the home screen.
 *  - Every crash is time-stamped (synchronously, before the process dies).
 *  - 2 crashes within 60 s  -> SAFE MODE on next start: optional features (widgets, Now Playing, HDMI card,
 *    blur, evening veil, animations) are skipped.
 *  - 4 crashes within 120 s -> FALLBACK: Vitrine Home hands Home over to another launcher instead of starting.
 * Both clear automatically when a new build is installed, or from Settings ("Exit safe mode").
 */
class GlassApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { Safety.recordCrash(this) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        Safety.clearIfNewBuild(this)
    }
}

object Safety {
    private fun prefs(c: Context) = c.getSharedPreferences("safety", Context.MODE_PRIVATE)

    private fun buildStamp(c: Context): Long = try {
        @Suppress("DEPRECATION") c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime
    } catch (_: Exception) { 0L }

    fun recordCrash(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val times = (p.getString("crashes", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it < 120_000L } + now
        val inMinute = times.count { now - it < 60_000L }
        val e = p.edit().putString("crashes", times.joinToString(","))
        if (inMinute >= 2) e.putBoolean("safe", true).putLong("stamp", buildStamp(c))
        if (times.size >= 4) e.putBoolean("fallback", true).putLong("stamp", buildStamp(c))
        e.commit()
    }

    fun clearIfNewBuild(c: Context) {
        val p = prefs(c)
        if ((p.getBoolean("safe", false) || p.getBoolean("fallback", false)) && p.getLong("stamp", 0L) != buildStamp(c)) clear(c)
    }

    fun safeMode(c: Context) = prefs(c).getBoolean("safe", false)
    fun fallback(c: Context) = prefs(c).getBoolean("fallback", false)
    fun clear(c: Context) { prefs(c).edit().clear().commit() }

    /** Another installed home screen (the stock Google TV / Android TV one first), or null. */
    fun otherLauncher(c: Context): Intent? {
        val pm = c.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(home, PackageManager.MATCH_ALL).filter { it.activityInfo.packageName != c.packageName }
        val preferred = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher")
        val pick = preferred.firstNotNullOfOrNull { p -> list.firstOrNull { it.activityInfo.packageName == p } }
            ?: list.firstOrNull { it.activityInfo.packageName.contains("launcher") } ?: list.firstOrNull() ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(pick.activityInfo.packageName, pick.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
