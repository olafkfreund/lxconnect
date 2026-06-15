package com.lxconnect.mcp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class McpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "lxconnect Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running the local MCP server background service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "mcp_service_channel"
    }
}
