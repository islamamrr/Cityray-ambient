package dev.carapps.guestbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.carapps.guestbook.car.CarReading
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One signature in the book. The drawing lives next to it as <id>.png. */
data class Entry(
    val id: String,
    val number: Int,
    val name: String,
    val note: String,
    val createdAt: Long,
    val ink: Int,
    val car: List<CarReading>,
)

/** Keeps entries in the app's private storage: entries/<id>.json plus entries/<id>.png. */
class EntryStore(context: Context) {

    private val dir = File(context.filesDir, "entries").apply { mkdirs() }

    /** Newest first. */
    fun all(): List<Entry> = dir.listFiles { f -> f.name.endsWith(".json") }
        .orEmpty()
        .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.createdAt }

    fun nextNumber(): Int = (all().maxOfOrNull { it.number } ?: 0) + 1

    fun add(name: String, note: String, ink: Int, signature: Bitmap?, car: List<CarReading>): Entry {
        val now = System.currentTimeMillis()
        val entry = Entry(now.toString(), nextNumber(), name.trim(), note.trim(), now, ink, car)
        signature?.let { bmp -> imageFile(entry).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        // Write the JSON last, so a half-saved entry never shows up.
        val tmp = File(dir, "${entry.id}.json.tmp")
        tmp.writeText(toJson(entry).toString(2))
        tmp.renameTo(File(dir, "${entry.id}.json"))
        return entry
    }

    fun delete(entry: Entry) {
        File(dir, "${entry.id}.json").delete()
        imageFile(entry).delete()
    }

    fun imageFile(entry: Entry) = File(dir, "${entry.id}.png")

    /** Loads the signature, shrunk so its longest side is at most [maxSide] pixels. */
    fun loadSignature(entry: Entry, maxSide: Int): Bitmap? {
        val file = imageFile(entry)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun toJson(e: Entry) = JSONObject().apply {
        put("id", e.id)
        put("number", e.number)
        put("name", e.name)
        put("note", e.note)
        put("createdAt", e.createdAt)
        put("ink", e.ink)
        put("car", JSONArray().apply {
            e.car.forEach { put(JSONObject().put("key", it.key).put("label", it.label).put("value", it.value)) }
        })
    }

    private fun fromJson(o: JSONObject): Entry {
        val car = ArrayList<CarReading>()
        val arr = o.optJSONArray("car") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            car.add(CarReading(c.getString("key"), c.getString("label"), c.getString("value")))
        }
        return Entry(
            id = o.getString("id"),
            number = o.optInt("number", 0),
            name = o.optString("name"),
            note = o.optString("note"),
            createdAt = o.getLong("createdAt"),
            ink = o.optInt("ink", Ink.colors[0]),
            car = car,
        )
    }
}
