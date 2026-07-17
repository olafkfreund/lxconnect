package com.lxconnect.mcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Lets an MCP tool call drive arbitrary app UIs: read the screen's node tree, tap, swipe,
 * type into the focused field, and grab a screenshot. Mirrors the `instance` singleton
 * pattern used by [CustomNotificationListener].
 */
class LxAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: tools pull state on-demand via rootInActiveWindow instead of tracking events.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    companion object {
        private const val TAG = "LxAccessibilityService"

        @Volatile
        var instance: LxAccessibilityService? = null

        private const val NOT_CONNECTED = "Accessibility service not connected. Enable it in Settings first."

        fun readScreen(): String {
            val svc = instance ?: return NOT_CONNECTED
            val root = svc.rootInActiveWindow ?: return "No active window content available."
            val sb = StringBuilder()
            dumpNode(root, 0, sb)
            return if (sb.isEmpty()) "No content found in the active window." else sb.toString()
        }

        private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                sb.append("  ".repeat(depth))
                    .append(node.className ?: "?")
                    .append(" text=\"$text\" desc=\"$desc\" bounds=$bounds clickable=${node.isClickable}\n")
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { dumpNode(it, depth + 1, sb) }
            }
        }

        fun tap(x: Int, y: Int): String {
            val svc = instance ?: return NOT_CONNECTED
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            return dispatchGestureSync(svc, gesture, "Tap")
        }

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String {
            val svc = instance ?: return NOT_CONNECTED
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            return dispatchGestureSync(svc, gesture, "Swipe")
        }

        // ponytail: blocking latch instead of a suspend/callback bridge — gestures are quick
        // (<=300ms) and tool handlers already run off the main thread.
        private fun dispatchGestureSync(svc: LxAccessibilityService, gesture: GestureDescription, label: String): String {
            val latch = CountDownLatch(1)
            var completed = false
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }
            if (!svc.dispatchGesture(gesture, callback, null)) {
                return "Failed to dispatch $label gesture."
            }
            latch.await(3, TimeUnit.SECONDS)
            return if (completed) "$label completed." else "$label did not complete (timed out or was cancelled)."
        }

        fun inputText(text: String): String {
            val svc = instance ?: return NOT_CONNECTED
            val target = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: svc.rootInActiveWindow?.let { findFocusedEditable(it) }
                ?: return "No focused editable field found."
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            return if (ok) "Text set successfully." else "Failed to set text on the focused field."
        }

        private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isFocused && node.isEditable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findFocusedEditable(child)?.let { return it }
            }
            return null
        }

        suspend fun screenshot(): Pair<String?, String?> {
            val svc = instance ?: return null to NOT_CONNECTED
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return null to "Screenshot requires Android 11+ (API 30)."
            }
            return suspendCancellableCoroutine { cont ->
                svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            result.hardwareBuffer.close()
                            if (bitmap == null) {
                                cont.resume(null to "Failed to decode screenshot buffer.")
                                return
                            }
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                            cont.resume(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP) to null)
                        } catch (e: Exception) {
                            cont.resume(null to "Error processing screenshot: ${e.message}")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(null to "Screenshot failed with error code $errorCode")
                    }
                })
            }
        }
    }
}
