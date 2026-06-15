package com.lxconnect.mcp

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class CustomNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        Log.d(TAG, "Notification posted: ${sbn.packageName}")
        try {
            val extras = sbn.notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val packageName = sbn.packageName ?: ""
            val key = sbn.key ?: ""
            if (title.isNotEmpty() || text.isNotEmpty()) {
                McpServerService.instance?.broadcastNotification(key, packageName, title, text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        private const val TAG = "McpNotificationListener"
        private var instance: CustomNotificationListener? = null

        fun getActiveNotifications(): List<Map<String, String>> {
            val notifications = instance?.activeNotifications ?: return emptyList()
            return notifications.map { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getString(Notification.EXTRA_TITLE) ?: "No Title"
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "No Text"
                
                mapOf(
                    "key" to sbn.key,
                    "packageName" to sbn.packageName,
                    "title" to title,
                    "text" to text,
                    "time" to sbn.postTime.toString()
                )
            }
        }

        fun reply(key: String, replyText: String): Boolean {
            val context = instance ?: return false
            val sbn = context.activeNotifications?.find { it.key == key } ?: return false
            val actions = sbn.notification.actions ?: return false
            
            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                for (remoteInput in remoteInputs) {
                    val intent = Intent()
                    val bundle = Bundle().apply {
                        putCharSequence(remoteInput.resultKey, replyText)
                    }
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                    
                    try {
                        action.actionIntent.send(context, 0, intent)
                        Log.d(TAG, "Successfully sent direct reply to notification: $key")
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send pending intent", e)
                    }
                }
            }
            return false
        }

        fun getMediaStatus(): List<Map<String, String>> {
            val context = instance ?: return emptyList()
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val component = android.content.ComponentName(context, CustomNotificationListener::class.java)
            val controllers = try {
                manager.getActiveSessions(component)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get active sessions", e)
                return emptyList()
            }
            
            return controllers.map { controller ->
                val metadata = controller.metadata
                val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
                val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                val album = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: "Unknown Album"
                val state = when (controller.playbackState?.state) {
                    android.media.session.PlaybackState.STATE_PLAYING -> "playing"
                    android.media.session.PlaybackState.STATE_PAUSED -> "paused"
                    android.media.session.PlaybackState.STATE_STOPPED -> "stopped"
                    else -> "unknown"
                }
                mapOf(
                    "packageName" to controller.packageName,
                    "title" to title,
                    "artist" to artist,
                    "album" to album,
                    "state" to state
                )
            }
        }

        fun controlMedia(packageName: String?, command: String): Boolean {
            val context = instance ?: return false
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val component = android.content.ComponentName(context, CustomNotificationListener::class.java)
            val controllers = try {
                manager.getActiveSessions(component)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get active sessions for control", e)
                return false
            }
            
            val controller = if (packageName != null && packageName.isNotEmpty()) {
                controllers.find { it.packageName == packageName }
            } else {
                controllers.firstOrNull()
            } ?: return false
            
            val transportControls = controller.transportControls
            when (command.lowercase()) {
                "play" -> transportControls.play()
                "pause" -> transportControls.pause()
                "skip" -> transportControls.skipToNext()
                "previous" -> transportControls.skipToPrevious()
                else -> return false
            }
            return true
        }
    }
}
