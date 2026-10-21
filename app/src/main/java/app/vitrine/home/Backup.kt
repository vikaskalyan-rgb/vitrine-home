package app.vitrine.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Your Vitrine Home setup (dock, order, hidden apps, wallpaper choice, theme, toggles) as one JSON file
 * in the app's folder on the TV: Android/data/app.vitrine.home/files/backup.json.
 * It is rewritten a few seconds after any change, so backup_tv.bat only has to copy it to the laptop.
 * restore_tv.bat puts a backup back as restore.json; it is applied on the next start.
 */
object Backup {
    private fun dir(c: Context) = c.getExternalFilesDir(null)

    fun export(c: Context) {
        try {
            val p = c.getSharedPreferences("layout", Context.MODE_PRIVATE)
            val d = JSONObject()
            for ((k, v) in p.all) {
                val e = JSONObject()
                when (v) {
                    is String -> { e.put("t", "s"); e.put("v", v) }
                    is Boolean -> { e.put("t", "b"); e.put("v", v) }
                    is Int -> { e.put("t", "i"); e.put("v", v) }
                    is Long -> { e.put("t", "l"); e.put("v", v) }
                    is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                    is Set<*> -> { e.put("t", "set"); e.put("v", JSONArray(v.map { it.toString() })) }
                    else -> continue
                }
                d.put(k, e)
            }
            val o = JSONObject().put("app", "Vitrine Home").put("version", 1).put("saved", System.currentTimeMillis()).put("prefs", d)
            val out = dir(c) ?: return
            out.mkdirs()
            val tmp = File(out, "backup.json.tmp")
            tmp.writeText(o.toString(2))
            tmp.renameTo(File(out, "backup.json"))
        } catch (_: Exception) {}
    }

    /** Applies restore.json if one was pushed; returns true if something was restored. */
    fun restoreIfPending(c: Context): Boolean {
        val d = dir(c) ?: return false
        val f = File(d, "restore.json")
        if (!f.exists()) return false
        return try {
            val prefs = JSONObject(f.readText()).getJSONObject("prefs")
            val ed = c.getSharedPreferences("layout", Context.MODE_PRIVATE).edit().clear()
            for (k in prefs.keys()) {
                val e = prefs.getJSONObject(k)
                when (e.getString("t")) {
                    "s" -> ed.putString(k, e.getString("v"))
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "set" -> { val a = e.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            ed.commit()
            f.renameTo(File(d, "restored.json"))
            true
        } catch (_: Exception) { f.renameTo(File(d, "restore_failed.json")); false }
    }
}
