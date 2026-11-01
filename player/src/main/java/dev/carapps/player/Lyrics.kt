package dev.carapps.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

sealed class Lyrics {
    object Loading : Lyrics()
    object NotFound : Lyrics()
    object Offline : Lyrics()
    object Instrumental : Lyrics()
    /** Lines with their start time, for highlighting along with the song. */
    class Synced(val lines: List<Pair<Long, String>>) : Lyrics()
    class Plain(val lines: List<String>) : Lyrics()
}

/**
 * Lyrics from LRCLIB (lrclib.net), a free lyrics service with no account or key.
 * Every answer is saved on the device, so a song looked up once works offline.
 */
class LyricsStore(context: Context) {

    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private var requestKey = ""

    /** Looks up lyrics for a song; [onResult] runs on the main thread, only for the latest request. */
    fun load(title: String, artist: String, album: String, durationMs: Long, onResult: (Lyrics) -> Unit) {
        val key = hash("$artist\n$title")
        requestKey = key
        Thread {
            val result = fromCache(key) ?: fetch(title, artist, album, durationMs, key)
            main.post { if (requestKey == key) onResult(result) }
        }.start()
    }

    private fun fromCache(key: String): Lyrics? {
        val f = File(dir, "$key.json")
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText())
            // A "not found" answer is retried after a week, in case someone adds the song.
            if (j.optBoolean("none") && System.currentTimeMillis() - f.lastModified() > WEEK) return null
            parse(j)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetch(title: String, artist: String, album: String, durationMs: Long, key: String): Lyrics {
        val json = try {
            exact(title, artist, album, durationMs) ?: search(title, artist)
        } catch (_: Exception) {
            return Lyrics.Offline
        }
        val toSave = json ?: JSONObject().put("none", true)
        runCatching { File(dir, "$key.json").writeText(toSave.toString()) }
        return parse(toSave)
    }

    /** Exact match by title, artist, album and length. Null when LRCLIB doesn't have it. */
    private fun exact(title: String, artist: String, album: String, durationMs: Long): JSONObject? {
        var q = "track_name=${enc(title)}&artist_name=${enc(artist)}"
        if (album.isNotBlank()) q += "&album_name=${enc(album)}"
        if (durationMs > 0) q += "&duration=${durationMs / 1000}"
        return try {
            JSONObject(get("$API/get?$q"))
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /** Looser search, for when the album or length doesn't match exactly. */
    private fun search(title: String, artist: String): JSONObject? {
        val arr = JSONArray(get("$API/search?track_name=${enc(title)}&artist_name=${enc(artist)}"))
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }
        return all.firstOrNull { !it.isNull("syncedLyrics") } ?: all.firstOrNull { !it.isNull("plainLyrics") }
    }

    private fun parse(j: JSONObject): Lyrics {
        if (j.optBoolean("none")) return Lyrics.NotFound
        if (j.optBoolean("instrumental")) return Lyrics.Instrumental
        val synced = if (j.isNull("syncedLyrics")) "" else j.optString("syncedLyrics")
        if (synced.isNotBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics.Synced(lines)
        }
        val plain = if (j.isNull("plainLyrics")) "" else j.optString("plainLyrics")
        if (plain.isNotBlank()) return Lyrics.Plain(plain.lines())
        return Lyrics.NotFound
    }

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 8_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "CarPlayer (https://github.com/islamamrr/Cityray-ambient)")
        inputStream.bufferedReader().use { it.readText() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val API = "https://lrclib.net/api"
        private const val WEEK = 7L * 24 * 3600 * 1000
        private val STAMP = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]")

        /** "[01:02.50] words" lines, sorted by time. A line can carry several timestamps. */
        fun parseLrc(lrc: String): List<Pair<Long, String>> {
            val out = ArrayList<Pair<Long, String>>()
            for (raw in lrc.lines()) {
                val stamps = STAMP.findAll(raw).toList()
                if (stamps.isEmpty()) continue
                val words = raw.substring(stamps.last().range.last + 1).trim()
                for (m in stamps) {
                    val ms = m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong()
                    out.add(ms to words)
                }
            }
            return out.sortedBy { it.first }
        }
    }
}
