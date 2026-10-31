package dev.carapps.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/** One app that currently has a media session. */
data class Source(val packageName: String, val label: String, val isPlaying: Boolean)

data class Track(
    val title: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val packageName: String,
    val appLabel: String,
    val durationMs: Long,
    val positionMs: Long,
    /** elapsedRealtime of positionMs, so the view can move the bar between updates. */
    val positionAt: Long,
    val speed: Float,
    val canSeek: Boolean,
) {
    fun positionNow(): Long {
        if (!isPlaying) return positionMs
        val p = positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

/**
 * Follows every app with a media session. Shows the one that's playing,
 * unless the user pinned another app with the source chips.
 */
class MediaHub(
    private val context: Context,
    private val onChange: (track: Track?, sources: List<Source>) -> Unit,
) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val labels = HashMap<String, String>()
    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var pinned: String? = null
    private var listening = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = rebind()
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> setControllers(list ?: emptyList()) }

    fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it) == component }
    }

    fun start() {
        if (!hasAccess()) {
            onChange(null, emptyList())
            return
        }
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            listening = true
            setControllers(sessions.getActiveSessions(component))
        } catch (e: SecurityException) {
            onChange(null, emptyList())
        }
    }

    fun stop() {
        if (listening) {
            try {
                sessions.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (_: Exception) {
            }
            listening = false
        }
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = emptyList()
        current = null
    }

    fun select(packageName: String) {
        pinned = packageName
        refresh()
    }

    fun playPause() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun next() {
        current?.transportControls?.skipToNext()
    }

    fun previous() {
        current?.transportControls?.skipToPrevious()
    }

    fun seekTo(ms: Long) {
        current?.transportControls?.seekTo(ms)
    }

    private fun rebind() {
        try {
            setControllers(sessions.getActiveSessions(component))
        } catch (_: SecurityException) {
            onChange(null, emptyList())
        }
    }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        // Some apps register more than one session; keep the first per app.
        controllers = list.distinctBy { it.packageName }
        controllers.forEach { it.registerCallback(controllerCallback, handler) }
        refresh()
    }

    private fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun pick(): MediaController? {
        val pin = controllers.firstOrNull { it.packageName == pinned }
        val playing = controllers.firstOrNull { isPlaying(it) }
        // A pinned app wins unless it's stopped and another app has started playing.
        if (pin != null && (isPlaying(pin) || playing == null)) return pin
        if (playing != null) {
            if (pin != null) pinned = null
            return playing
        }
        return controllers.firstOrNull { it.metadata != null } ?: controllers.firstOrNull()
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun refresh() {
        val sources = controllers.map { Source(it.packageName, label(it.packageName), isPlaying(it)) }
        val c = pick()
        current = c
        val md = c?.metadata
        if (c == null || md == null) {
            onChange(null, sources)
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val state = c.playbackState
        onChange(
            Track(
                title = title,
                artist = artist,
                album = album,
                art = art,
                isPlaying = isPlaying(c),
                packageName = c.packageName,
                appLabel = label(c.packageName),
                durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                positionMs = state?.position ?: 0L,
                positionAt = state?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
                speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                canSeek = (state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L,
            ),
            sources,
        )
    }
}
