package app.vitrine.home

import android.service.notification.NotificationListenerService

/** Exists only so the launcher may read what is playing (Now Playing widget). It reads no notifications. */
class MediaListener : NotificationListenerService()
