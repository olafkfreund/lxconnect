package com.lxconnect.mcp

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.serialization.json.jsonPrimitive

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
            if (NotificationRich.isNoise(sbn)) return
            val rich = NotificationRich.describe(this, sbn)
            val hasContent = rich["title"]?.jsonPrimitive?.content.isNullOrEmpty().not() ||
                rich["text"]?.jsonPrimitive?.content.isNullOrEmpty().not()
            if (hasContent) {
                McpServerService.instance?.broadcastNotification(rich)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        try {
            McpServerService.instance?.broadcastNotificationRemoved(sbn.key ?: return)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting removal", e)
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
            val context = instance ?: return emptyList()
            val notifications = context.activeNotifications ?: return emptyList()
            return notifications.map { sbn ->
                val rich = NotificationRich.describe(context, sbn, summary = true)
                fun field(name: String) = rich[name]?.jsonPrimitive?.content ?: ""
                mapOf(
                    "key" to field("key"),
                    "packageName" to field("packageName"),
                    "appLabel" to field("appLabel"),
                    "title" to field("title").ifEmpty { "No Title" },
                    "text" to field("text").ifEmpty { "No Text" },
                    "time" to field("time")
                )
            }
        }

        private fun find(key: String): StatusBarNotification? =
            instance?.activeNotifications?.find { it.key == key }

        /**
         * Fires the notification's contentIntent — the same thing tapping it on
         * the phone does, so the app resumes exactly where the notification points.
         */
        fun activate(key: String): Boolean {
            val context = instance ?: return false
            val sbn = find(key) ?: return false
            val intent = sbn.notification.contentIntent ?: return false
            return try {
                intent.send()
                // Tapping a notification normally dismisses it if the app said so.
                if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) {
                    context.cancelNotification(key)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to activate notification $key", e)
                false
            }
        }

        /** Invokes a non-reply action button by its index in list_notifications. */
        fun invokeAction(key: String, index: Int): Boolean {
            val context = instance ?: return false
            val sbn = find(key) ?: return false
            val action = sbn.notification.actions?.getOrNull(index) ?: return false
            return try {
                action.actionIntent.send(context, 0, Intent())
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to invoke action $index on $key", e)
                false
            }
        }

        fun dismiss(key: String): Boolean {
            val context = instance ?: return false
            if (find(key) == null) return false
            context.cancelNotification(key)
            return true
        }

        /** Base64 PNG for "largeIcon" | "picture" | "appIcon" of a notification. */
        fun image(key: String, which: String): String? {
            val context = instance ?: return null
            val sbn = find(key) ?: return null
            return NotificationRich.notificationImage(context, sbn, which)
        }

        fun appIcon(packageName: String): String? {
            val context = instance ?: return null
            return NotificationRich.appIcon(context, packageName)
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
