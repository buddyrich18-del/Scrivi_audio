package it.example.transcriber

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Entry(val time: Long, val text: String)

/** Cronologia locale (file JSON nella memoria privata dell'app) e impostazioni. */
class Store(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Modello Whisper scelto (default: small, il più preciso). */
    var model: WModel
        get() = Models.byId(prefs.getString("model", "small") ?: "small")
        set(v) = prefs.edit().putString("model", v.id).apply()

    /** Lingua scelta (default: rilevamento automatico). */
    var language: Lang
        get() = Languages.byCode(prefs.getString("lang", "auto") ?: "auto")
        set(v) = prefs.edit().putString("lang", v.code).apply()

    fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getLong("time"), o.getString("text"))
        }
    }

    fun add(text: String) = save(listOf(Entry(System.currentTimeMillis(), text)) + load())

    fun delete(e: Entry) = save(load().filter { it.time != e.time })

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("time", it.time).put("text", it.text)) }
        file.writeText(arr.toString())
    }
}
