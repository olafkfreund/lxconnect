package com.lxconnect.mcp

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Programmatic layout creation
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(50, 80, 50, 80)
            setBackgroundColor(Color.parseColor("#F4F6F9"))
        }

        val titleView = TextView(this).apply {
            text = "lxconnect MCP Server"
            textSize = 24f
            setTextColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        rootLayout.addView(titleView)

        statusText = TextView(this).apply {
            text = "Status: Service not started\nPairing Key: test-key-999\nPort: 8080"
            textSize = 16f
            setTextColor(Color.parseColor("#555555"))
            setPadding(0, 0, 0, 60)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusText)

        val btnPermissions = Button(this).apply {
            text = "1. Request Permissions"
            setPadding(20, 20, 20, 20)
            setOnClickListener { requestSmsAndCallPermissions() }
        }
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 30)
        }
        rootLayout.addView(btnPermissions, layoutParams)

        val btnNotificationAccess = Button(this).apply {
            text = "2. Grant Notification Listener Access"
            setPadding(20, 20, 20, 20)
            setOnClickListener { openNotificationAccessSettings() }
        }
        rootLayout.addView(btnNotificationAccess, layoutParams)

        val btnStartService = Button(this).apply {
            text = "3. Start MCP Server Service"
            setPadding(20, 20, 20, 20)
            setOnClickListener { startMcpService() }
        }
        rootLayout.addView(btnStartService, layoutParams)

        setContentView(rootLayout)

        checkPermissionsAndStatus()
        startMcpService()
    }

    private fun checkPermissionsAndStatus() {
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val hasNotificationListener = isNotificationServiceEnabled()

        statusText.text = "Pairing Key: test-key-999\nPort: 8080\n\nSMS Read/Send Permission: ${if (hasSms) "GRANTED" else "MISSING"}\nNotification Access: ${if (hasNotificationListener) "GRANTED" else "MISSING"}"
    }

    private fun requestSmsAndCallPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun openNotificationAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startMcpService() {
        val intent = Intent(this, McpServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "MCP Server Service Started", Toast.LENGTH_SHORT).show()
        statusText.text = statusText.text.toString() + "\n\nServer Status: RUNNING"
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && TextUtils.equals(pkgName, cn.packageName)) {
                    return true
                }
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            checkPermissionsAndStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStatus()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
    }
}
