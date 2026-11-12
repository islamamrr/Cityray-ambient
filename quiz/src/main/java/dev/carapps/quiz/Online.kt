package dev.carapps.quiz

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Fetches new questions from the internet and saves them next to the built-in ones.
 *
 * 1. Question packs from this project's GitHub repo (quiz/packs). These can hold Arabic and
 *    Egyptian questions, and adding a pack there updates every installed copy of the app.
 * 2. Open Trivia DB (opentdb.com), a free English question bank, for the general categories.
 */
class Online(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var running = false
        private set

    /** [progress] gets a line of status text; [done] gets the number of new questions saved. */
    fun refresh(lang: Lang, progress: (String) -> Unit, done: (Int, String?) -> Unit) {
        if (running) return
        running = true
        Thread {
            var added = 0
            var error: String? = null
            fun say(en: String, ar: String) = main.post { progress(if (lang == Lang.AR) ar else en) }
            try {
                say("Checking the project's question packs…", "بندور على باقات أسئلة جديدة…")
                added += runCatching { fetchPacks() }.getOrElse { error = it.message; 0 }
                for ((i, entry) in OPENTDB.entries.withIndex()) {
                    say("Open Trivia DB ${i + 1}/${OPENTDB.size}…", "أسئلة إنجليزي من Open Trivia DB ${i + 1}/${OPENTDB.size}…")
                    if (i > 0) Thread.sleep(5500) // the site allows one request every 5 seconds
                    added += runCatching { fetchOpenTdb(entry.key, entry.value) }.getOrElse { error = it.message; 0 }
                }
            } finally {
                val n = added
                val e = error
                main.post {
                    running = false
                    done(n, e)
                }
            }
        }.start()
    }

    private fun fetchPacks(): Int {
        val index = JSONObject(get(PACKS_URL + "index.json"))
        val packs = index.optJSONArray("packs") ?: return 0
        var added = 0
        for (i in 0 until packs.length()) {
            val p = packs.getJSONObject(i)
            val id = p.getString("id").replace(Regex("[^A-Za-z0-9_-]"), "")
            val file = p.getString("file")
            if (id.isEmpty() || file.contains("/") || file.contains("..")) continue
            val text = get(PACKS_URL + file)
            val json = JSONObject(text) // must parse before we keep it
            added += countQuestions(json) - countQuestions(File(Bank.onlineDir(context), "pack_$id.json"))
            File(Bank.onlineDir(context), "pack_$id.json").writeText(text)
        }
        return added.coerceAtLeast(0)
    }

    /** Downloads up to 50 English questions for one Open Trivia DB category and merges them in. */
    private fun fetchOpenTdb(ourCategory: String, theirIds: List<Int>): Int {
        val target = File(Bank.onlineDir(context), "opentdb_$ourCategory.json")
        val existing = runCatching { JSONObject(target.readText()).getJSONArray("questions") }.getOrNull() ?: JSONArray()
        val keys = HashSet<String>()
        for (i in 0 until existing.length()) keys.add(existing.getJSONObject(i).getJSONArray("en").getString(0))
        var added = 0
        for (id in theirIds) {
            val url = "https://opentdb.com/api.php?amount=50&type=multiple&encode=url3986&category=$id"
            val json = JSONObject(get(url))
            if (json.optInt("response_code", 1) != 0) continue
            val results = json.getJSONArray("results")
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val q = dec(r.getString("question"))
                if (q in keys) continue
                val wrong = r.getJSONArray("incorrect_answers")
                if (wrong.length() < 3) continue
                val arr = JSONArray().put(q).put(dec(r.getString("correct_answer")))
                for (w in 0 until 3) arr.put(dec(wrong.getString(w)))
                existing.put(JSONObject().put("en", arr))
                keys.add(q)
                added++
            }
            if (theirIds.size > 1 && id != theirIds.last()) Thread.sleep(5500)
        }
        val cat = CATEGORY_NAMES[ourCategory]!!
        val out = JSONObject()
            .put("category", JSONObject().put("id", ourCategory).put("en", cat.first).put("ar", cat.second).put("icon", cat.third).put("egypt", false))
            .put("questions", existing)
        target.writeText(out.toString())
        return added
    }

    private fun countQuestions(f: File): Int =
        if (!f.exists()) 0 else runCatching { countQuestions(JSONObject(f.readText())) }.getOrDefault(0)

    private fun countQuestions(json: JSONObject): Int {
        val sets = json.optJSONArray("sets") ?: return json.optJSONArray("questions")?.length() ?: 0
        var n = 0
        for (i in 0 until sets.length()) n += sets.getJSONObject(i).optJSONArray("questions")?.length() ?: 0
        return n
    }

    private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "CarQuiz/1.0")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val PACKS_URL = "https://raw.githubusercontent.com/islamamrr/Cityray-ambient/main/quiz/packs/"

        /** Our category -> Open Trivia DB category ids. */
        private val OPENTDB = linkedMapOf(
            "general" to listOf(9),
            "science" to listOf(17),
            "geography" to listOf(22),
            "sports" to listOf(21),
            "world_ent" to listOf(11, 12),
            "cars" to listOf(28),
        )

        private val CATEGORY_NAMES = mapOf(
            "general" to Triple("General Knowledge", "معلومات عامة", "💡"),
            "science" to Triple("Science & Nature", "علوم وطبيعة", "🔬"),
            "geography" to Triple("World Geography", "جغرافيا العالم", "🌍"),
            "sports" to Triple("World Sports", "رياضة عالمية", "🏆"),
            "world_ent" to Triple("World Movies & Music", "أفلام وأغاني عالمية", "🍿"),
            "cars" to Triple("Cars & Driving", "عربيات وسواقة", "🚗"),
        )
    }
}
