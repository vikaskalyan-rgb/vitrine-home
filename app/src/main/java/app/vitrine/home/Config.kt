package app.vitrine.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Optional personal settings, read from `config.json` in the app's folder on the device
 * (Android/data/app.vitrine.home/files/config.json). Every field is optional:
 *
 *   name            greeting: "Good evening, Sam" (blank = "Good evening")
 *   place           label on the weather card
 *   latitude,
 *   longitude       weather + air quality location. Without them, Vitrine finds an approximate
 *                   location from your internet connection (see autoLocation)
 *   autoLocation    false = never look up a location; the weather card stays hidden without one
 *   liveTvPackage   your TV's Live TV app, if Vitrine can't find it by itself
 *   sleepTimerAction  an intent action that opens your TV's sleep-timer screen (adds a Sleep timer row)
 *   dock            first-run dock apps, in order (package names)
 *   appOrder        order of apps in All apps
 *   hideApps        apps never shown (e.g. the TV's own launcher)
 *   homeApps        preinstalled apps that belong on Home rather than in More
 *   moreApps        apps that go in the More sheet
 *
 * Push it with:  scripts/vitrine.sh config <tv-ip> config.json
 */
class Config(
    val name: String = "",
    val place: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val autoLocation: Boolean = true,
    val liveTvPackage: String? = null,
    val sleepTimerAction: String? = null,
    val dock: List<String> = emptyList(),
    val appOrder: List<String> = emptyList(),
    val hideApps: Set<String> = emptySet(),
    val homeApps: Set<String> = emptySet(),
    val moreApps: Set<String> = emptySet()
) {
    val hasLocation get() = latitude != null && longitude != null

    companion object {
        private fun list(o: JSONObject, k: String): List<String> {
            val a: JSONArray = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it, "").trim().ifEmpty { null } }
        }

        fun load(c: Context): Config {
            return try {
                val f = File(c.getExternalFilesDir(null), "config.json")
                if (!f.exists()) return Config()
                val o = JSONObject(f.readText())
                fun num(k: String) = if (o.has(k)) o.optDouble(k).takeIf { !it.isNaN() } else null
                Config(
                    name = o.optString("name", "").trim(),
                    place = o.optString("place", "").trim(),
                    latitude = num("latitude"),
                    longitude = num("longitude"),
                    autoLocation = o.optBoolean("autoLocation", true),
                    liveTvPackage = o.optString("liveTvPackage", "").trim().ifEmpty { null },
                    sleepTimerAction = o.optString("sleepTimerAction", "").trim().ifEmpty { null },
                    dock = list(o, "dock"),
                    appOrder = list(o, "appOrder"),
                    hideApps = list(o, "hideApps").toSet(),
                    homeApps = list(o, "homeApps").toSet(),
                    moreApps = list(o, "moreApps").toSet()
                )
            } catch (_: Exception) { Config() }
        }
    }
}
