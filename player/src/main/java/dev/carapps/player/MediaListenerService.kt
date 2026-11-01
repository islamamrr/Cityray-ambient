package dev.carapps.player

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose. Android only lets an app read other apps' media sessions
 * if it owns an enabled notification listener, so this just has to exist.
 */
class MediaListenerService : NotificationListenerService()
