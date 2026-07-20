package com.lxconnect.mcp

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.util.Base64
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

/**
 * Turns an Android notification into the rich payload the desktop renders:
 * full body text, freedesktop body-markup, extracted URLs, the app's display
 * label, and the list of actions. Binary parts (avatars, BigPicture images)
 * are advertised as booleans and fetched on demand — see [notificationImage].
 */
object NotificationRich {

    /** Longest body we forward. Some apps post enormous BigText blobs. */
    private const val MAX_BODY = 4000

    /** Bitmaps are downscaled to this max edge before base64 encoding. */
    private const val MAX_IMAGE_EDGE = 512

    fun appLabel(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    /**
     * Full description of a notification. [summary] = true trims the payload
     * down to what list_notifications needs (no markup, no action list).
     */
    fun describe(context: Context, sbn: StatusBarNotification, summary: Boolean = false): JsonObject {
        val n = sbn.notification
        val extras = n.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        // BigText is the expanded body; EXTRA_TEXT is only the collapsed preview.
        val body: CharSequence? = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: messagingBody(n)
            ?: inboxBody(extras)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)

        val urls = linkedSetOf<String>()
        collectUrls(title, urls)
        collectUrls(body, urls)

        return buildJsonObject {
            put("key", sbn.key ?: "")
            put("packageName", sbn.packageName ?: "")
            put("appLabel", appLabel(context, sbn.packageName ?: ""))
            put("title", title?.toString()?.take(MAX_BODY) ?: "")
            put("text", body?.toString()?.take(MAX_BODY) ?: "")
            put("time", sbn.postTime)
            if (!summary) {
                put("titleMarkup", toMarkup(title))
                put("textMarkup", toMarkup(body))
                put("category", n.category ?: "")
                put("priority", n.priority)
                put("isOngoing", sbn.isOngoing)
                put("hasLargeIcon", n.getLargeIcon() != null)
                put("hasPicture", picture(extras) != null)
                putJsonArray("urls") { urls.forEach { add(it) } }
                putJsonArray("actions") {
                    n.actions?.forEachIndexed { i, action ->
                        addJsonObject {
                            put("index", i)
                            put("title", action.title?.toString() ?: "")
                            put("isReply", !action.remoteInputs.isNullOrEmpty())
                        }
                    }
                }
            }
        }
    }

    /**
     * True for notifications the desktop should ignore: persistent service
     * chrome ("USB charging", "Screen recording") and the empty summary rows
     * that hold a bundled group together.
     */
    fun isNoise(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        // ponytail: ongoing == media players, downloads, foreground services. They
        // re-post constantly and are never actionable from the desktop. Drop the
        // blanket rule if someone wants download progress mirrored.
        if (sbn.isOngoing) return true
        return false
    }

    // --- body assembly -----------------------------------------------------

    /** MessagingStyle: "Sender: message" per line, oldest first. */
    private fun messagingBody(n: Notification): CharSequence? {
        val style = try {
            androidx.core.app.NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(n)
        } catch (e: Exception) {
            null
        } ?: return null
        val messages = style.messages
        if (messages.isEmpty()) return null
        return messages.joinToString("\n") { m ->
            val sender = m.person?.name?.toString()
            if (sender.isNullOrEmpty()) m.text?.toString() ?: "" else "$sender: ${m.text}"
        }
    }

    /** InboxStyle: one line per bundled item. */
    private fun inboxBody(extras: android.os.Bundle): CharSequence? {
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) ?: return null
        if (lines.isEmpty()) return null
        return lines.joinToString("\n") { it.toString() }
    }

    // --- markup ------------------------------------------------------------

    private fun collectUrls(cs: CharSequence?, into: MutableSet<String>) {
        if (cs !is Spanned) return
        cs.getSpans(0, cs.length, URLSpan::class.java).forEach { span ->
            span.url?.let { into.add(it) }
        }
    }

    /**
     * Converts styled text to the freedesktop notification body-markup subset
     * (`<b> <i> <u> <a href>`). Anything else is dropped; text is escaped.
     */
    fun toMarkup(cs: CharSequence?): String {
        if (cs == null) return ""
        val text = cs.toString().take(MAX_BODY)
        if (cs !is Spanned) return escape(text)

        // Collect the spans we can express, longest first so an enclosing span is
        // considered before anything it contains.
        val candidates = mutableListOf<Triple<IntRange, String, String>>()
        fun candidate(start: Int, end: Int, open: String, close: String) {
            if (start < 0 || end > text.length || start >= end) return
            candidates.add(Triple(start until end, open, close))
        }

        cs.getSpans(0, cs.length, Any::class.java).forEach { span ->
            val s = cs.getSpanStart(span)
            val e = cs.getSpanEnd(span)
            when (span) {
                is URLSpan -> span.url?.let { candidate(s, e, "<a href=\"${escape(it)}\">", "</a>") }
                is UnderlineSpan -> candidate(s, e, "<u>", "</u>")
                is StyleSpan -> when (span.style) {
                    android.graphics.Typeface.BOLD -> candidate(s, e, "<b>", "</b>")
                    android.graphics.Typeface.ITALIC -> candidate(s, e, "<i>", "</i>")
                    android.graphics.Typeface.BOLD_ITALIC -> candidate(s, e, "<b><i>", "</i></b>")
                }
            }
        }
        candidates.sortWith(compareBy({ it.first.first }, { -it.first.last }))

        // Android spans may partially overlap (bold [0,5) crossing a link [3,8)),
        // which would emit <b>..<a>..</b>..</a>. Strict markup parsers reject the
        // whole body for that, so keep only spans that nest cleanly.
        val opens = HashMap<Int, MutableList<String>>()
        val closes = HashMap<Int, MutableList<String>>()
        val accepted = mutableListOf<IntRange>()
        candidates.forEach { (range, open, close) ->
            val nests = accepted.all { prior ->
                val disjoint = range.first > prior.last || range.last < prior.first
                val inside = range.first >= prior.first && range.last <= prior.last
                disjoint || inside
            }
            if (!nests) return@forEach
            accepted.add(range)
            opens.getOrPut(range.first) { mutableListOf() }.add(open)
            // Innermost closes first: later (inner) spans prepend.
            closes.getOrPut(range.last + 1) { mutableListOf() }.add(0, close)
        }

        val out = StringBuilder(text.length)
        for (i in text.indices) {
            closes[i]?.forEach { out.append(it) }
            opens[i]?.forEach { out.append(it) }
            out.append(escapeChar(text[i]))
        }
        closes[text.length]?.forEach { out.append(it) }
        return out.toString()
    }

    private fun escape(s: String): String = buildString(s.length) {
        s.forEach { append(escapeChar(it)) }
    }

    private fun escapeChar(c: Char): String = when (c) {
        '&' -> "&amp;"
        '<' -> "&lt;"
        '>' -> "&gt;"
        else -> c.toString()
    }

    // --- images ------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun picture(extras: android.os.Bundle): Bitmap? =
        extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap

    /**
     * Base64 PNG of a notification's avatar ("largeIcon"), its BigPicture
     * image ("picture"), or the posting app's launcher icon ("appIcon").
     */
    fun notificationImage(context: Context, sbn: StatusBarNotification, which: String): String? {
        val n = sbn.notification
        val bitmap = when (which) {
            "largeIcon" -> n.getLargeIcon()?.let { toBitmap(it.loadDrawable(context)) }
            "picture" -> picture(n.extras)
            "appIcon" -> appIconBitmap(context, sbn.packageName ?: return null)
            else -> null
        } ?: return null
        return encodePng(bitmap)
    }

    fun appIcon(context: Context, packageName: String): String? =
        appIconBitmap(context, packageName)?.let { encodePng(it) }

    private fun appIconBitmap(context: Context, packageName: String): Bitmap? = try {
        toBitmap(context.packageManager.getApplicationIcon(packageName))
    } catch (e: Exception) {
        null
    }

    private fun toBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) drawable.bitmap?.let { return it }
        val w = drawable.intrinsicWidth.coerceIn(1, MAX_IMAGE_EDGE)
        val h = drawable.intrinsicHeight.coerceIn(1, MAX_IMAGE_EDGE)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }

    private fun encodePng(source: Bitmap): String {
        val scaled = downscale(source)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_IMAGE_EDGE) return bitmap
        val scale = MAX_IMAGE_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
