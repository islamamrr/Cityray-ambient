package dev.carapps.ambient

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings

data class NowPlaying(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val appLabel: String,
)

/** Follows whichever app is playing media and reports title, artist, art and play state. */
class MediaWatcher(
    private val context: Context,
    private val onChange: (NowPlaying?) -> Unit,
) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
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
            onChange(null)
            return
        }
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            listening = true
            setControllers(sessions.getActiveSessions(component))
        } catch (e: SecurityException) {
            onChange(null)
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

    private fun rebind() {
        try {
            setControllers(sessions.getActiveSessions(component))
        } catch (_: SecurityException) {
            onChange(null)
        }
    }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = list
        list.forEach { it.registerCallback(controllerCallback, handler) }
        refresh()
    }

    private fun refresh() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val c = playing ?: controllers.firstOrNull { it.metadata != null }
        current = c
        val md = c?.metadata
        if (c == null || md == null) {
            onChange(null)
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        if (title.isBlank() && artist.isBlank()) {
            onChange(null)
            return
        }
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val label = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        } catch (_: Exception) {
            c.packageName
        }
        onChange(
            NowPlaying(
                title = title,
                artist = artist,
                art = art,
                isPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                appLabel = label,
            )
        )
    }
}
