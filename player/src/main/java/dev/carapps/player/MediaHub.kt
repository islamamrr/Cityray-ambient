package dev.carapps.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/** One app that currently has a media session. */
class Source(val packageName: String, val label: String, val icon: Drawable?, val isPlaying: Boolean)

/** An extra button the app itself publishes, such as Spotify's like, shuffle or repeat. */
class AppAction(val action: String, val name: String, val icon: Drawable?)

/** One song in the app's own queue, if it shares one. */
class QueueEntry(val id: Long, val title: String, val subtitle: String, val art: Bitmap?)

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
    val actions: List<AppAction>,
    val queue: List<QueueEntry>,
    /** Queue id of the current song, or -1 if unknown. */
    val queueCurrentId: Long,
    /** What the app actually sent, shown when you tap the clock. */
    val debug: String,
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
    private val actionIcons = HashMap<String, Drawable?>()
    private val appIcons = HashMap<String, Drawable?>()
    private val artLoader = ArtLoader(context) { refresh() }
    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var pinned: String? = null
    private var listening = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onQueueChanged(queue: List<MediaSession.QueueItem>?) = refresh()
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

    fun playPause() {
        val c = current
        if (c == null) {
            resumeLastPlayer()
            return
        }
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

    fun sendAction(action: String) {
        current?.transportControls?.sendCustomAction(action, null)
    }

    fun skipToQueueItem(id: Long) {
        current?.transportControls?.skipToQueueItem(id)
    }

    /**
     * With no app holding a music session, a "play" media key goes to the app that
     * played last (usually Spotify), which resumes where it stopped.
     */
    fun resumeLastPlayer() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
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

    private fun appIcon(pkg: String): Drawable? = appIcons.getOrPut(pkg) {
        try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }
    }

    /** The app's own icon for a custom action, loaded from that app's resources. */
    private fun actionIcon(pkg: String, resId: Int): Drawable? = actionIcons.getOrPut("$pkg:$resId") {
        if (resId == 0) return@getOrPut null
        try {
            context.packageManager.getResourcesForApplication(pkg).getDrawable(resId, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun refresh() {
        val sources = controllers.map { Source(it.packageName, label(it.packageName), appIcon(it.packageName), isPlaying(it)) }
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
        // Android shrinks cover bitmaps passed through a media session, so a link to the
        // full-size cover wins when the app sends one; the small bitmap shows while it loads.
        val artUri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val bitmap = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = artLoader.get(artUri) ?: bitmap
        val queue = c.queue.orEmpty()
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
                actions = state?.customActions.orEmpty().map {
                    AppAction(it.action, it.name?.toString() ?: "", actionIcon(c.packageName, it.icon))
                },
                queue = queue.map { item ->
                    val d = item.description
                    QueueEntry(item.queueId, d.title?.toString() ?: "", d.subtitle?.toString() ?: "", d.iconBitmap ?: artLoader.get(d.iconUri?.toString()))
                },
                queueCurrentId = state?.activeQueueItemId ?: -1L,
                debug = listOf(
                    "app: ${c.packageName}",
                    "cover bitmap: " + (bitmap?.let { "${it.width}x${it.height}" } ?: "none"),
                    "cover link: " + (artUri ?: "none"),
                    "showing: " + (art?.let { "${it.width}x${it.height}" } ?: "nothing"),
                    "queue: ${queue.size} songs",
                    "first queue picture: " + (queue.firstOrNull()?.description?.let { d ->
                        (d.iconBitmap?.let { "${it.width}x${it.height}" } ?: "no bitmap") + ", link " + (d.iconUri ?: "none")
                    } ?: "-"),
                    "app buttons: " + (state?.customActions.orEmpty().joinToString { it.name.toString() }.ifBlank { "none" }),
                ).joinToString("\n"),
            ),
            sources,
        )
    }
}
