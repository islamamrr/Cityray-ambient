package dev.carapps.quiz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Lang { EN, AR }

/** One way of asking a question: the text, then the right answer, then three wrong ones. */
class Phrasing(val question: String, val correct: String, val wrong: List<String>)

class Question(val categoryId: String, val en: Phrasing?, val ar: Phrasing?) {
    /** Stable id, used to remember which questions were already asked. */
    val key: String = (en?.question ?: "") + "|" + (ar?.question ?: "")
    fun inLang(lang: Lang): Phrasing? = if (lang == Lang.AR) ar else en
}

class Category(val id: String, val en: String, val ar: String, val icon: String, val egypt: Boolean) {
    fun name(lang: Lang) = if (lang == Lang.AR) ar else en
}

/**
 * Every question the app knows: the ones shipped in assets/questions, plus anything
 * downloaded into files/online. Both use the same JSON shape (see quiz/packs/README.md).
 */
class Bank(private val context: Context) {

    val categories = LinkedHashMap<String, Category>()
    private val questions = LinkedHashMap<String, Question>()

    private val prefs = context.getSharedPreferences("quiz", Context.MODE_PRIVATE)
    private val seen = HashSet<String>(prefs.getStringSet("seen", emptySet()) ?: emptySet())

    fun load() {
        categories.clear()
        questions.clear()
        val assets = context.assets
        assets.list("questions")?.sorted()?.forEach { name ->
            runCatching { parse(assets.open("questions/$name").bufferedReader().use { it.readText() }) }
        }
        onlineDir(context).listFiles()?.sortedBy { it.name }?.forEach { f ->
            runCatching { parse(f.readText()) }
        }
    }

    fun count(categoryId: String, lang: Lang): Int =
        questions.values.count { it.categoryId == categoryId && it.inLang(lang) != null }

    fun total(lang: Lang): Int = questions.values.count { it.inLang(lang) != null }

    fun available(ids: Collection<String>, lang: Lang): List<String> =
        ids.filter { count(it, lang) > 0 }

    /**
     * A question from one category that hasn't been asked yet. When a category runs out,
     * its questions become fresh again.
     */
    fun draw(categoryId: String, lang: Lang, exclude: Set<String>): Question? {
        val pool = questions.values.filter { it.categoryId == categoryId && it.inLang(lang) != null && it.key !in exclude }
        if (pool.isEmpty()) return null
        var fresh = pool.filter { it.key !in seen }
        if (fresh.isEmpty()) {
            pool.forEach { seen.remove(it.key) }
            fresh = pool
        }
        val q = fresh.random()
        seen.add(q.key)
        prefs.edit().putStringSet("seen", HashSet(seen)).apply()
        return q
    }

    private fun parse(text: String) {
        val root = JSONObject(text)
        val sets = root.optJSONArray("sets")
        if (sets != null) {
            for (i in 0 until sets.length()) parseSet(sets.getJSONObject(i))
        } else {
            parseSet(root)
        }
    }

    private fun parseSet(set: JSONObject) {
        val c = set.getJSONObject("category")
        val id = c.getString("id")
        if (id !in categories) {
            categories[id] = Category(
                id = id,
                en = c.optString("en", id),
                ar = c.optString("ar", c.optString("en", id)),
                icon = c.optString("icon", "❓"),
                egypt = c.optBoolean("egypt", false),
            )
        }
        val list = set.optJSONArray("questions") ?: return
        for (i in 0 until list.length()) {
            val q = list.getJSONObject(i)
            val question = Question(id, phrasing(q.optJSONArray("en")), phrasing(q.optJSONArray("ar")))
            if (question.en == null && question.ar == null) continue
            if (question.key !in questions) questions[question.key] = question
        }
    }

    private fun phrasing(a: JSONArray?): Phrasing? {
        if (a == null || a.length() < 3) return null
        val items = (0 until a.length()).map { a.getString(it).trim() }
        if (items.any { it.isEmpty() }) return null
        return Phrasing(items[0], items[1], items.drop(2).take(3))
    }

    companion object {
        fun onlineDir(context: Context) = File(context.filesDir, "online").apply { mkdirs() }
    }
}
