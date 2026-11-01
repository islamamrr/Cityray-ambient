package dev.carapps.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Loads cover art that an app sends as a link instead of a picture. Spotify often does
 * this: the song's metadata and queue carry an image URI, and the bitmap field is empty.
 * Handles content://, android.resource://, file://, http(s):// and spotify:image: links.
 */
class ArtLoader(context: Context, private val onLoaded: () -> Unit) {

    private val resolver = context.applicationContext.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val cache = LruCache<String, Bitmap>(40)
    private val pending = HashSet<String>()
    /** When each link last failed; tried again after a minute, e.g. once there is internet. */
    private val failed = HashMap<String, Long>()

    /** The bitmap if it's ready; otherwise starts loading it and returns null. */
    fun get(uri: String?): Bitmap? {
        if (uri.isNullOrBlank()) return null
        cache.get(uri)?.let { return it }
        val failedAt = failed[uri]
        if (failedAt != null && SystemClock.uptimeMillis() - failedAt < 60_000) return null
        if (!pending.add(uri)) return null
        pool.execute {
            val bmp = try {
                open(uri)?.use { decode(it) }
            } catch (_: Exception) {
                null
            }
            main.post {
                pending.remove(uri)
                if (bmp != null) {
                    cache.put(uri, bmp)
                    onLoaded()
                } else {
                    failed[uri] = SystemClock.uptimeMillis()
                }
            }
        }
        return null
    }

    private fun open(uri: String): InputStream? {
        val fixed = if (uri.startsWith("spotify:image:")) "https://i.scdn.co/image/" + uri.removePrefix("spotify:image:") else uri
        return when (Uri.parse(fixed).scheme) {
            "http", "https" -> (URL(fixed).openConnection() as HttpURLConnection).run {
                connectTimeout = 8_000
                readTimeout = 15_000
                inputStream
            }
            "content", "android.resource", "file" -> resolver.openInputStream(Uri.parse(fixed))
            else -> null
        }
    }

    /** Decodes at no more than about 800 px, which is plenty for the screen. */
    private fun decode(input: InputStream): Bitmap? {
        val bytes = input.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 800) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
