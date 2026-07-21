package com.lxconnect.mcp

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import android.os.Environment
import android.util.Base64

fun JsonElement?.asString(name: String): String = this?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing $name")

class McpServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var serverEngine: EmbeddedServer<*, *>? = null
    private val mcpServers = java.util.concurrent.CopyOnWriteArrayList<Server>()
    private var tlsProxy: TlsProxy? = null

    private var secureSharedKey = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        secureSharedKey = McpApplication.getSharedKey(this)
        startForegroundService()
        startMcpServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        tlsProxy?.close()
        serverEngine?.stop(1000, 2000)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, McpApplication.CHANNEL_ID)
            .setContentTitle("lxconnect MCP Server Running")
            .setContentText("Listening on port 8080")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMcpServer() {
        // Netty serves PLAINTEXT bound to localhost only; a Conscrypt SSLServerSocket
        // (TlsProxy) terminates TLS on :8080 and pipes to it. Netty's own SslHandler is
        // unreliable on Android/Conscrypt (throws AssertionError), so we keep TLS out of it.
        val keyStore = McpApplication.getTlsKeyStore(this)
        val keyStorePassword = McpApplication.getTlsKeystorePasswordChars(this)

        serverEngine = embeddedServer(Netty, port = 8081, host = "127.0.0.1") {
            // Install Ktor SSE Plugin (required by MCP SDK for SSE transport routing)
            install(io.ktor.server.sse.SSE)

            // Install simple auth interceptor
            intercept(ApplicationCallPipeline.Plugins) {
                val provided = (call.request.headers["Authorization"] ?: "").toByteArray()
                val expected = "Bearer $secureSharedKey".toByteArray()
                if (!java.security.MessageDigest.isEqual(provided, expected)) {
                    call.respond(HttpStatusCode.Unauthorized, "Unauthorized: Invalid or missing shared key.")
                    finish()
                }
            }

            routing {
                // Ktor 3.x mcp builder takes a block returning the Server instance
                mcp {
                    // Runs per connection: every client gets its own Server. Keeping
                    // only the latest meant a second client stole the push channel,
                    // and its disconnect killed mirroring for everyone still attached.
                    createMcpServerInstance().also { mcpServers.add(it) }
                }
            }
        }.start(wait = false)

        tlsProxy = TlsProxy(keyStore, keyStorePassword).also { it.start() }
        Log.i(TAG, "Server started: TLS :8080 -> plaintext 127.0.0.1:8081")
    }

    private fun createMcpServerInstance(): Server {
        val server = Server(
            serverInfo = Implementation(name = "lxconnect-android-mcp", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        // Tool 1: list_notifications
        server.addTool(
            name = "list_notifications",
            description = "Retrieve all active status bar notifications from the phone.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) {
            val notifications = CustomNotificationListener.getActiveNotifications()
            val text = if (notifications.isEmpty()) {
                "No active notifications found."
            } else {
                notifications.joinToString("\n\n") { n ->
                    "Key: ${n["key"]}\nApp: ${n["appLabel"]}\nPackage: ${n["packageName"]}\n" +
                        "Title: ${n["title"]}\nText: ${n["text"]}"
                }
            }
            CallToolResult(content = listOf(TextContent(text = text)))
        }

        // Tool 2: reply_to_notification
        server.addTool(
            name = "reply_to_notification",
            description = "Send a text reply to an active chat/message notification.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("notificationKey") {
                        put("type", "string")
                        put("description", "The unique notification key returned by list_notifications.")
                    }
                    putJsonObject("replyText") {
                        put("type", "string")
                        put("description", "Text to send as the reply.")
                    }
                },
                required = listOf("notificationKey", "replyText")
            )
        ) { request ->
            val key = request.params.arguments?.get("notificationKey").asString("notificationKey")
            val text = request.params.arguments?.get("replyText").asString("replyText")

            val success = CustomNotificationListener.reply(key, text)
            if (success) {
                CallToolResult(content = listOf(TextContent(text = "Reply sent successfully.")))
            } else {
                CallToolResult(content = listOf(TextContent(text = "Failed to reply. Notification might be gone, or doesn't support direct replies.")), isError = true)
            }
        }

        // Tool 2b: activate_notification — resume the app where the notification points
        server.addTool(
            name = "activate_notification",
            description = "Open the app behind a notification, exactly where tapping it on the phone would land. Resumes the conversation, article or screen it refers to.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("notificationKey") {
                        put("type", "string")
                        put("description", "The unique notification key returned by list_notifications.")
                    }
                },
                required = listOf("notificationKey")
            )
        ) { request ->
            val key = request.params.arguments?.get("notificationKey").asString("notificationKey")
            if (CustomNotificationListener.activate(key)) {
                CallToolResult(content = listOf(TextContent(text = "Opened notification on device.")))
            } else {
                CallToolResult(content = listOf(TextContent(text = "Failed to open. The notification is gone or carries no content intent.")), isError = true)
            }
        }

        // Tool 2c: invoke_notification_action — press one of its buttons
        server.addTool(
            name = "invoke_notification_action",
            description = "Press one of a notification's action buttons (e.g. 'Mark as read', 'Archive') by its index. Use reply_to_notification for reply actions.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("notificationKey") {
                        put("type", "string")
                        put("description", "The unique notification key returned by list_notifications.")
                    }
                    putJsonObject("actionIndex") {
                        put("type", "integer")
                        put("description", "Zero-based index of the action button.")
                    }
                },
                required = listOf("notificationKey", "actionIndex")
            )
        ) { request ->
            val key = request.params.arguments?.get("notificationKey").asString("notificationKey")
            val index = request.params.arguments?.get("actionIndex")?.jsonPrimitive?.int
                ?: throw IllegalArgumentException("Missing actionIndex")
            if (CustomNotificationListener.invokeAction(key, index)) {
                CallToolResult(content = listOf(TextContent(text = "Action $index invoked.")))
            } else {
                CallToolResult(content = listOf(TextContent(text = "Failed to invoke action. The notification is gone or has no action at that index.")), isError = true)
            }
        }

        // Tool 2d: dismiss_notification
        server.addTool(
            name = "dismiss_notification",
            description = "Dismiss a notification on the phone, as swiping it away would.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("notificationKey") {
                        put("type", "string")
                        put("description", "The unique notification key returned by list_notifications.")
                    }
                },
                required = listOf("notificationKey")
            )
        ) { request ->
            val key = request.params.arguments?.get("notificationKey").asString("notificationKey")
            if (CustomNotificationListener.dismiss(key)) {
                CallToolResult(content = listOf(TextContent(text = "Notification dismissed.")))
            } else {
                CallToolResult(content = listOf(TextContent(text = "Failed to dismiss. The notification is no longer active.")), isError = true)
            }
        }

        // Tool 2e: get_notification_image — avatar / inline picture, fetched on demand
        server.addTool(
            name = "get_notification_image",
            description = "Fetch an image belonging to a notification: 'largeIcon' (sender avatar), 'picture' (inline BigPicture image) or 'appIcon'. Returned as a PNG.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("notificationKey") {
                        put("type", "string")
                        put("description", "The unique notification key returned by list_notifications.")
                    }
                    putJsonObject("which") {
                        put("type", "string")
                        put("description", "One of 'largeIcon', 'picture', 'appIcon'. Defaults to 'largeIcon'.")
                    }
                },
                required = listOf("notificationKey")
            )
        ) { request ->
            val key = request.params.arguments?.get("notificationKey").asString("notificationKey")
            val which = request.params.arguments?.get("which")?.jsonPrimitive?.content ?: "largeIcon"
            val b64 = CustomNotificationListener.image(key, which)
            if (b64 != null) {
                CallToolResult(content = listOf(ImageContent(data = b64, mimeType = "image/png")))
            } else {
                CallToolResult(content = listOf(TextContent(text = "No '$which' image for that notification.")), isError = true)
            }
        }

        // Tool 2f: get_app_icon — cached client-side, so this is called once per app
        server.addTool(
            name = "get_app_icon",
            description = "Fetch an installed app's launcher icon as a PNG, for rendering its notifications on the desktop.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "The app's package name, e.g. com.whatsapp.")
                    }
                },
                required = listOf("packageName")
            )
        ) { request ->
            val packageName = request.params.arguments?.get("packageName").asString("packageName")
            val b64 = CustomNotificationListener.appIcon(packageName)
            if (b64 != null) {
                CallToolResult(content = listOf(ImageContent(data = b64, mimeType = "image/png")))
            } else {
                CallToolResult(content = listOf(TextContent(text = "No icon found for package '$packageName'.")), isError = true)
            }
        }

        // Tool 3: send_sms
        server.addTool(
            name = "send_sms",
            description = "Send an SMS text message to a phone number.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("phoneNumber") {
                        put("type", "string")
                        put("description", "The recipient's phone number.")
                    }
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "The body of the text message.")
                    }
                },
                required = listOf("phoneNumber", "message")
            )
        ) { request ->
            val phoneNumber = request.params.arguments?.get("phoneNumber").asString("phoneNumber")
            val message = request.params.arguments?.get("message").asString("message")

            val smsManager = getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            CallToolResult(content = listOf(TextContent(text = "SMS sent successfully to $phoneNumber.")))
        }

        // Tool 4: get_sms_history
        server.addTool(
            name = "get_sms_history",
            description = "Read the history of SMS messages on the device.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Max number of messages to fetch (default: 10).")
                    }
                },
                required = emptyList()
            )
        ) { request ->
            val limit = (request.params.arguments?.get("limit")?.jsonPrimitive?.int ?: 10).coerceIn(0, 1000)
            val list = mutableListOf<String>()

            try {
                val cursor = contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf("_id", "address", "body", "date", "type"),
                    null, null, "date DESC LIMIT $limit"
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        val address = it.getString(it.getColumnIndexOrThrow("address")) ?: "Unknown"
                        val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                        val type = it.getString(it.getColumnIndexOrThrow("type")) ?: "1"
                        val folder = if (type == "1") "Inbox" else "Sent"
                        list.add("From/To: $address ($folder)\nContent: $body")
                    }
                }
                val resultText = if (list.isEmpty()) {
                    "No SMS history found."
                } else {
                    list.joinToString("\n\n---\n\n")
                }
                CallToolResult(content = listOf(TextContent(text = resultText)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent(text = "Failed to read SMS database: ${e.message}")), isError = true)
            }
        }

        // Tool 5: get_device_info
        server.addTool(
            name = "get_device_info",
            description = "Get battery and simple device status.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            
            // Screen geometry: tap/swipe take absolute pixels, so a client that cannot query the
            // coordinate space is tapping blind.
            // getRealMetrics honours the current rotation; WindowManager.currentWindowMetrics
            // from a Service context reports the display's *natural* bounds, which would hand
            // clients transposed axes on a landscape device.
            val display = (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") display.getRealMetrics(dm)

            val info = "Model: ${Build.MODEL}\nManufacturer: ${Build.MANUFACTURER}\n" +
                "Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Battery: $pct%\n" +
                "Screen: ${dm.widthPixels}x${dm.heightPixels} px, density ${dm.density}, " +
                (if (dm.widthPixels > dm.heightPixels) "landscape" else "portrait")
            CallToolResult(content = listOf(TextContent(text = info)))
        }

        // Tool 6: get_clipboard
        server.addTool(
            name = "get_clipboard",
            description = "Get the current text from the device clipboard.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) {
            var clipboardText = ""
            withContext(Dispatchers.Main) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clipboardText = clip.getItemAt(0).text?.toString() ?: ""
                }
            }
            CallToolResult(content = listOf(TextContent(text = clipboardText.ifEmpty { "Clipboard is empty or contains non-text content." })))
        }

        // Tool 7: set_clipboard
        server.addTool(
            name = "set_clipboard",
            description = "Set the text on the device clipboard.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "The text to set on the clipboard.")
                    }
                },
                required = listOf("text")
            )
        ) { request ->
            val text = request.params.arguments?.get("text").asString("text")
            withContext(Dispatchers.Main) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("lxconnect", text)
                clipboard.setPrimaryClip(clip)
            }
            CallToolResult(content = listOf(TextContent(text = "Clipboard updated successfully.")))
        }

        // Tool 8: get_media_status
        server.addTool(
            name = "get_media_status",
            description = "Get status of active media players (e.g. playing track, artist, album).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) {
            val list = CustomNotificationListener.getMediaStatus()
            val resultText = if (list.isEmpty()) {
                "No active media sessions found."
            } else {
                list.joinToString("\n\n---\n\n") { m ->
                    "Player: ${m["packageName"]}\nStatus: ${m["state"]}\nTitle: ${m["title"]}\nArtist: ${m["artist"]}\nAlbum: ${m["album"]}"
                }
            }
            CallToolResult(content = listOf(TextContent(text = resultText)))
        }

        // Tool 9: control_media
        server.addTool(
            name = "control_media",
            description = "Control media playback on the device.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "Playback command: 'play', 'pause', 'skip', or 'previous'.")
                    }
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "Optional package name of the media player to target.")
                    }
                },
                required = listOf("command")
            )
        ) { request ->
            val command = request.params.arguments?.get("command").asString("command")
            val pkg = request.params.arguments?.get("packageName")?.jsonPrimitive?.content

            val success = CustomNotificationListener.controlMedia(pkg, command)
            if (success) {
                CallToolResult(content = listOf(TextContent(text = "Command '$command' sent successfully.")))
            } else {
                CallToolResult(content = listOf(TextContent(text = "Failed to send command. Ensure a media player is active.")), isError = true)
            }
        }

        // Tool 10: ring_device
        server.addTool(
            name = "ring_device",
            description = "Make the device ring at max volume to locate it, or stop ringing.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "The action to perform: 'start' to ring, 'stop' to silence it.")
                    }
                },
                required = listOf("action")
            )
        ) { request ->
            val action = request.params.arguments?.get("action")?.jsonPrimitive?.content ?: "start"
            val text = ringDevice(action)
            CallToolResult(content = listOf(TextContent(text = text)))
        }

        // Tool 11: get_detailed_status
        server.addTool(
            name = "get_detailed_status",
            description = "Get detailed battery, Wi-Fi, storage, RAM, and telephony stats.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) {
            // 1. Battery status
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val tempC = if (temp != -1) temp / 10.0 else 0.0
            val statusVal = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = statusVal == BatteryManager.BATTERY_STATUS_CHARGING || statusVal == BatteryManager.BATTERY_STATUS_FULL

            // 2. RAM Info
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalMemGB = String.format("%.2f", memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
            val availMemGB = String.format("%.2f", memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0))

            // 3. Storage Info
            val path = android.os.Environment.getDataDirectory()
            val stat = android.os.StatFs(path.path)
            val totalStorageGB = String.format("%.2f", (stat.blockCountLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0))
            val freeStorageGB = String.format("%.2f", (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0))

            // 4. Wi-Fi SSID
            var ssid = "N/A"
            var rssi = -127
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val wifiInfo = wifiManager.connectionInfo
                if (wifiInfo != null) {
                    ssid = wifiInfo.ssid ?: "N/A"
                    rssi = wifiInfo.rssi
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Wi-Fi info", e)
            }

            // 5. Telephony / Carrier
            var carrierName = "Unknown"
            try {
                val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                carrierName = telephonyManager.networkOperatorName ?: "Unknown"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get carrier name", e)
            }

            val details = """
                Device: ${Build.MANUFACTURER} ${Build.MODEL}
                Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                
                Battery: $pct% (${if (isCharging) "Charging" else "Discharging"})
                Battery Temperature: $tempC°C
                
                RAM: $availMemGB GB free / $totalMemGB GB total
                Storage: $freeStorageGB GB free / $totalStorageGB GB total
                
                Wi-Fi SSID: $ssid (RSSI: $rssi dBm)
                Carrier: $carrierName
            """.trimIndent()
            CallToolResult(content = listOf(TextContent(text = details)))
        }

        // Tool 12: take_picture
        server.addTool(
            name = "take_picture",
            description = "Capture a photo from the back camera of the device and return the image data.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) {
            val result = CameraActivity.capturePhoto(this@McpServerService)
            if (result.base64 != null) {
                CallToolResult(
                    content = listOf(
                        ImageContent(
                            data = result.base64,
                            mimeType = "image/jpeg"
                        )
                    )
                )
            } else {
                CallToolResult(
                    content = listOf(
                        TextContent(
                            text = "Failed to take picture: ${result.error}"
                        )
                    ),
                    isError = true
                )
            }
        }

        // Tool 13: start_app
        server.addTool(
            name = "start_app",
            description = "Launch an application on the device by its package name.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "The Android package name of the app to launch (e.g. 'com.android.chrome').")
                    }
                },
                required = listOf("packageName")
            )
        ) { request ->
            val packageName = request.params.arguments?.get("packageName").asString("packageName")
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                CallToolResult(content = listOf(TextContent(text = "Successfully launched app: $packageName")))
            } else {
                CallToolResult(content = listOf(TextContent(text = "Failed to launch app. Package '$packageName' not found or cannot be opened.")), isError = true)
            }
        }

        // Tool 20: list_installed_apps
        server.addTool(
            name = "list_installed_apps",
            description = "List all installed applications on the device with their package names and labels.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) {
            val pm = packageManager
            val packages = pm.getInstalledPackages(0)
            val list = packages.mapNotNull { pkg ->
                try {
                    val appInfo = pkg.applicationInfo
                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val packageName = pkg.packageName
                    "$label ($packageName)${if (isSystem) " [SYSTEM]" else ""}"
                } catch (e: Exception) {
                    null
                }
            }.sorted()
            val text = if (list.isEmpty()) {
                "No apps found."
            } else {
                list.joinToString("\n")
            }
            CallToolResult(content = listOf(TextContent(text = text)))
        }

        // Tool 21: search_contacts
        server.addTool(
            name = "search_contacts",
            description = "Search contacts by name to retrieve their phone numbers.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Name or partial name of the contact to search for.")
                    }
                },
                required = listOf("query")
            )
        ) { request ->
            val query = request.params.arguments?.get("query").asString("query")
            val list = mutableListOf<String>()
            try {
                val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("%$query%")
                val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
                cursor?.use {
                    while (it.moveToNext()) {
                        val name = it.getString(0) ?: "Unknown"
                        val number = it.getString(1) ?: "No Number"
                        list.add("$name: $number")
                    }
                }
                val resultText = if (list.isEmpty()) {
                    "No contacts found matching '$query'."
                } else {
                    list.distinct().joinToString("\n")
                }
                CallToolResult(content = listOf(TextContent(text = resultText)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent(text = "Failed to search contacts: ${e.message}")), isError = true)
            }
        }

        val baseDownloadDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "lxconnect")
        if (baseDownloadDir != null && !baseDownloadDir.exists()) {
            baseDownloadDir.mkdirs()
        }

        // Tool 15: list_files
        server.addTool(
            name = "list_files",
            description = "List files in the device's Download/lxconnect directory.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) {
            val files = baseDownloadDir.listFiles()
            val text = if (files == null || files.isEmpty()) {
                "No files found in ${baseDownloadDir.absolutePath}"
            } else {
                files.joinToString("\n") {
                    "${if (it.isDirectory) "[DIR]" else "[FILE]"} ${it.name} (${it.length()} bytes)"
                }
            }
            CallToolResult(content = listOf(TextContent(text = text)))
        }

        // Tool 16: send_file
        server.addTool(
            name = "send_file",
            description = "Send a file from Linux to the Android device.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("fileName") {
                        put("type", "string")
                        put("description", "Target filename.")
                    }
                    putJsonObject("base64Data") {
                        put("type", "string")
                        put("description", "Base64 encoded file data.")
                    }
                },
                required = listOf("fileName", "base64Data")
            )
        ) { request ->
            val fileName = request.params.arguments?.get("fileName").asString("fileName")
            val base64Data = request.params.arguments?.get("base64Data").asString("base64Data")

            // basic traversal prevention
            if (fileName.contains("/") || fileName.contains("..")) {
                throw IllegalArgumentException("Invalid filename")
            }

            try {
                val targetFile = File(baseDownloadDir, fileName)
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                targetFile.writeBytes(bytes)
                CallToolResult(content = listOf(TextContent(text = "File successfully written to ${targetFile.absolutePath}")))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent(text = "Failed to write file: ${e.message}")), isError = true)
            }
        }

        // Tool 17: get_file
        server.addTool(
            name = "get_file",
            description = "Retrieve a file from the Android device.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("fileName") {
                        put("type", "string")
                        put("description", "Filename to retrieve from the Download/lxconnect directory.")
                    }
                },
                required = listOf("fileName")
            )
        ) { request ->
            val fileName = request.params.arguments?.get("fileName").asString("fileName")

            if (fileName.contains("/") || fileName.contains("..")) {
                throw IllegalArgumentException("Invalid filename")
            }

            val targetFile = File(baseDownloadDir, fileName)
            if (!targetFile.exists() || !targetFile.isFile) {
                throw IllegalArgumentException("File not found: ${targetFile.absolutePath}")
            }
            
            val bytes = targetFile.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            CallToolResult(content = listOf(TextContent(text = b64)))
        }

        // Tool 14: stop_app
        server.addTool(
            name = "stop_app",
            description = "Kill background processes for a specific package. (Note: Due to Android security, this only kills background services and processes, not foreground activities).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "The package name of the app to stop (e.g. 'com.spotify.music').")
                    }
                },
                required = listOf("packageName")
            )
        ) { request ->
            val packageName = request.params.arguments?.get("packageName").asString("packageName")
            
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.killBackgroundProcesses(packageName)
            CallToolResult(content = listOf(TextContent(text = "Requested system to stop background processes for package: $packageName")))
        }

        // Tool 18: open_deep_link
        server.addTool(
            name = "open_deep_link",
            description = "Open an Android deep link URI to navigate within specific apps (e.g. 'spotify:search:myquery' or 'geo:0,0?q=my+location').",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("uri") {
                        put("type", "string")
                        put("description", "The deep link URI to open.")
                    }
                },
                required = listOf("uri")
            )
        ) { request ->
            val uriString = request.params.arguments?.get("uri").asString("uri")
            val uri = android.net.Uri.parse(uriString)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            try {
                startActivity(intent)
                CallToolResult(content = listOf(TextContent(text = "Successfully opened deep link: $uriString")))
            } catch (e: android.content.ActivityNotFoundException) {
                throw IllegalArgumentException("No application found to handle the URI: $uriString")
            }
        }

        server.addTool(
            name = "http_request",
            description = "Make an HTTP request from the device's own network (its Wi-Fi or mobile data, its TLS stack, its egress IP). " +
                "Use to test how a service behaves as seen from this device rather than from the desktop.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Absolute http:// or https:// URL.")
                    }
                    putJsonObject("method") {
                        put("type", "string")
                        put("description", "GET (default), POST, PUT, DELETE, HEAD or OPTIONS.")
                    }
                    putJsonObject("headers") {
                        put("type", "object")
                        put("description", "Request headers as a flat name/value object.")
                    }
                    putJsonObject("body") {
                        put("type", "string")
                        put("description", "Request body, for POST and PUT.")
                    }
                },
                required = listOf("url")
            )
        ) { request ->
            val args = request.params.arguments
            val text = withContext(Dispatchers.IO) {
                httpRequest(
                    url = args?.get("url").asString("url"),
                    method = args?.get("method")?.jsonPrimitive?.content?.uppercase() ?: "GET",
                    headers = args?.get("headers") as? JsonObject,
                    body = args?.get("body")?.jsonPrimitive?.content,
                )
            }
            CallToolResult(content = listOf(TextContent(text = text)))
        }

        server.registerAccessibilityTools(this@McpServerService)

        return server
    }

    /** HttpURLConnection has no PATCH support, so the allowed set is what it can actually send. */
    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")

    private fun httpRequest(url: String, method: String, headers: JsonObject?, body: String?): String {
        if (method !in HTTP_METHODS) return "Unsupported method '$method'. Expected one of ${HTTP_METHODS.joinToString("/")}."
        val parsed = try { java.net.URL(url) } catch (e: Exception) { return "Invalid URL: ${e.message}" }
        if (parsed.protocol !in setOf("http", "https")) return "Only http and https URLs are allowed."

        val started = System.currentTimeMillis()
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (parsed.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 15_000
                headers?.forEach { (k, v) -> setRequestProperty(k, v.jsonPrimitive.content) }
            }
            if (body != null && (method == "POST" || method == "PUT")) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            // A 4xx/5xx body arrives on errorStream; reading inputStream would just throw.
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val elapsed = System.currentTimeMillis() - started
            val headerText = conn.headerFields.entries
                .filter { it.key != null }
                .joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }
            val shown = payload.take(MAX_HTTP_BODY)
            val truncated = if (payload.length > MAX_HTTP_BODY) "\n… truncated (${payload.length} bytes total)" else ""
            "HTTP $code in ${elapsed}ms\n$headerText\n\n$shown$truncated"
        } catch (e: Exception) {
            "Request failed after ${System.currentTimeMillis() - started}ms: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    private fun ringDevice(action: String): String {
        if (action.lowercase() == "stop") {
            activeRingtone?.stop()
            activeRingtone = null
            return "Ringtone stopped."
        }

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            // Set ringer mode to normal and volume to max
            audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_RING, maxVolume, 0)
            
            val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            val ringtone = android.media.RingtoneManager.getRingtone(this, ringtoneUri)
            ringtone?.play()
            activeRingtone = ringtone
            return "Device is ringing at maximum volume."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ring device", e)
            return "Error playing ringtone: ${e.message}"
        }
    }

    // Uses the SDK's public CustomNotification type (notifications/* extension mechanism) and
    // ServerSession.notification(), a public method inherited from Protocol. No reflection needed.
    fun broadcastNotificationRemoved(key: String) {
        push("notifications/phone_notification_removed", buildJsonObject { put("key", key) })
    }

    fun broadcastNotification(meta: JsonObject) {
        push("notifications/phone_notification", meta)
    }

    private fun push(method: String, meta: JsonObject) {
        serviceScope.launch {
            try {
                val notification = CustomNotification(
                    method = Method.Custom(method),
                    params = BaseNotificationParams(meta = meta)
                )
                mcpServers.forEach { server ->
                    val sessions = server.sessions
                    if (sessions.isEmpty()) return@forEach // still handshaking
                    var delivered = 0
                    sessions.values.forEach { session ->
                        try {
                            session.notification(notification)
                            delivered++
                        } catch (e: Exception) {
                            Log.e(TAG, "Push failed for a session", e)
                        }
                    }
                    // Every session on this server failed, so that client is gone.
                    // Its Server would otherwise throw on every future notification.
                    if (delivered == 0) {
                        Log.d(TAG, "Dropping disconnected client")
                        mcpServers.remove(server)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in broadcastNotification", e)
            }
        }
    }

    companion object {
        private const val TAG = "McpServerService"
        private const val NOTIFICATION_ID = 42
        private const val MAX_HTTP_BODY = 20_000
        private var activeRingtone: android.media.Ringtone? = null

        @Volatile
        var instance: McpServerService? = null
    }
}
