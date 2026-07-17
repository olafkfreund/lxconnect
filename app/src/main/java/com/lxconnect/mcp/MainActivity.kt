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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            text = "Status: Starting…\nPort: 8080"
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

        val btnAccessibilityAccess = Button(this).apply {
            text = "Grant Accessibility Access"
            setPadding(20, 20, 20, 20)
            setOnClickListener { openAccessibilitySettings() }
        }
        rootLayout.addView(btnAccessibilityAccess, layoutParams)

        val btnStartService = Button(this).apply {
            text = "3. Start MCP Server Service"
            setPadding(20, 20, 20, 20)
            setOnClickListener { startMcpService() }
        }
        rootLayout.addView(btnStartService, layoutParams)

        setContentView(rootLayout)

        checkPermissionsAndStatus()
        startMcpService()
        handleIntent(intent)
    }

    private fun getSecureSharedKey(): String = McpApplication.getSharedKey(this)

    private fun checkPermissionsAndStatus() {
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val hasNotificationListener = isNotificationServiceEnabled()
        val key = getSecureSharedKey()

        statusText.text = "Pairing Key: $key\nPort: 8080\n\nSMS Read/Send Permission: ${if (hasSms) "GRANTED" else "MISSING"}\nNotification Access: ${if (hasNotificationListener) "GRANTED" else "MISSING"}"
    }

    private fun requestSmsAndCallPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
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

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startMcpService() {
        // minSdk 26: startForegroundService always available
        startForegroundService(Intent(this, McpServerService::class.java))
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        val data = intent.data
        if (Intent.ACTION_VIEW == action && data != null) {
            val ip = data.getQueryParameter("ip")
            val port = data.getQueryParameter("port")
            val secret = data.getQueryParameter("secret")
            if (ip != null && port != null && secret != null) {
                pairWithLinux(ip, port, secret)
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            android.util.Log.e("Pairing", "Failed to get local IP address", ex)
        }
        return null
    }

    private fun pairWithLinux(ip: String, port: String, secret: String) {
        val prefs = getSharedPreferences("lxconnect_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("secure_shared_key", secret).apply()

        // Restart service to load new key
        val serviceIntent = Intent(this, McpServerService::class.java)
        stopService(serviceIntent)
        startForegroundService(serviceIntent)

        val ipsToTry = mutableListOf<String>()
        if (ip != "127.0.0.1" && ip != "localhost") {
            ipsToTry.add("127.0.0.1")
        }
        ipsToTry.add(ip)

        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            var lastErrorMessage = ""
            for (targetIp in ipsToTry) {
                val urlString = "http://$targetIp:$port/pair"
                try {
                    val url = java.net.URL(urlString)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    
                    val localIp = getLocalIpAddress() ?: ""
                    val certFingerprint = McpApplication.getCertFingerprint(this@MainActivity)
                    val payload = "{\"secret\":\"$secret\",\"deviceName\":\"${Build.MODEL}\",\"ip\":\"$localIp\",\"certFingerprint\":\"$certFingerprint\"}"
                    conn.outputStream.use { os ->
                        val input = payload.toByteArray(charset("utf-8"))
                        os.write(input, 0, input.size)
                    }
                    
                    val code = conn.responseCode
                    if (code == 200) {
                        success = true
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Paired successfully with Linux! ($targetIp)", Toast.LENGTH_LONG).show()
                            checkPermissionsAndStatus()
                        }
                        break
                    } else {
                        lastErrorMessage = "HTTP $code"
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Pairing", "Failed to connect to pairing server at $targetIp", e)
                    lastErrorMessage = e.message ?: "Connection error"
                }
            }
            if (!success) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Pairing connection error: $lastErrorMessage", Toast.LENGTH_LONG).show()
                }
            }
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
