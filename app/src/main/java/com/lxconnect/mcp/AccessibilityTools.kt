package com.lxconnect.mcp

import android.content.Context
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private fun JsonElement?.asInt(name: String): Int = this?.jsonPrimitive?.int ?: throw IllegalArgumentException("Missing $name")

/**
 * Registers tools that drive arbitrary app UIs via [LxAccessibilityService], for actions the
 * fixed tool set in McpServerService can't reach (tapping into a third-party app's UI).
 * `context` is accepted to match the rest of this file's tool-registration style; the tools
 * themselves talk to the accessibility service singleton directly.
 */
fun Server.registerAccessibilityTools(context: Context) {

    addTool(
        name = "read_screen",
        description = "Read the current screen's UI elements (class, text, contentDescription, bounds, clickable) via the Accessibility service.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
    ) {
        CallToolResult(content = listOf(TextContent(text = LxAccessibilityService.readScreen())))
    }

    addTool(
        name = "tap",
        description = "Tap the screen at the given coordinates via the Accessibility service.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("x") {
                    put("type", "integer")
                    put("description", "X coordinate in pixels.")
                }
                putJsonObject("y") {
                    put("type", "integer")
                    put("description", "Y coordinate in pixels.")
                }
            },
            required = listOf("x", "y")
        )
    ) { request ->
        val x = request.params.arguments?.get("x").asInt("x")
        val y = request.params.arguments?.get("y").asInt("y")
        CallToolResult(content = listOf(TextContent(text = LxAccessibilityService.tap(x, y))))
    }

    addTool(
        name = "swipe",
        description = "Swipe the screen from one point to another via the Accessibility service.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("x1") {
                    put("type", "integer")
                    put("description", "Start X coordinate.")
                }
                putJsonObject("y1") {
                    put("type", "integer")
                    put("description", "Start Y coordinate.")
                }
                putJsonObject("x2") {
                    put("type", "integer")
                    put("description", "End X coordinate.")
                }
                putJsonObject("y2") {
                    put("type", "integer")
                    put("description", "End Y coordinate.")
                }
            },
            required = listOf("x1", "y1", "x2", "y2")
        )
    ) { request ->
        val x1 = request.params.arguments?.get("x1").asInt("x1")
        val y1 = request.params.arguments?.get("y1").asInt("y1")
        val x2 = request.params.arguments?.get("x2").asInt("x2")
        val y2 = request.params.arguments?.get("y2").asInt("y2")
        CallToolResult(content = listOf(TextContent(text = LxAccessibilityService.swipe(x1, y1, x2, y2))))
    }

    addTool(
        name = "input_text",
        description = "Type text into the currently focused editable field via the Accessibility service.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to type into the focused field.")
                }
            },
            required = listOf("text")
        )
    ) { request ->
        val text = request.params.arguments?.get("text").asString("text")
        CallToolResult(content = listOf(TextContent(text = LxAccessibilityService.inputText(text))))
    }

    addTool(
        name = "press_key",
        description = "Press a hardware/system key: back, home, recents, or notifications.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("key") {
                    put("type", "string")
                    put("description", "One of: back, home, recents, notifications.")
                }
            },
            required = listOf("key")
        )
    ) { request ->
        val key = request.params.arguments?.get("key").asString("key")
        CallToolResult(content = listOf(TextContent(text = LxAccessibilityService.pressKey(key))))
    }

    addTool(
        name = "tap_text",
        description = "Tap the element whose text, content description or view id matches the query. " +
            "Survives layout changes, unlike tap(x,y).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Text, content description (substring, case-insensitive) or exact view id.")
                }
            },
            required = listOf("query")
        )
    ) { request ->
        val query = request.params.arguments?.get("query").asString("query")
        CallToolResult(content = listOf(TextContent(text = LxAccessibilityService.tapText(query))))
    }

    addTool(
        name = "wait_for",
        description = "Block until an element matching the query appears on screen, or the timeout elapses. " +
            "Use between steps instead of guessing at render times.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Text, content description (substring, case-insensitive) or exact view id.")
                }
                putJsonObject("timeoutMs") {
                    put("type", "integer")
                    put("description", "How long to wait in milliseconds (default 5000, max 60000).")
                }
            },
            required = listOf("query")
        )
    ) { request ->
        val query = request.params.arguments?.get("query").asString("query")
        val timeout = (request.params.arguments?.get("timeoutMs")?.jsonPrimitive?.int ?: 5000)
            .coerceIn(0, 60_000).toLong()
        CallToolResult(content = listOf(TextContent(text = LxAccessibilityService.waitFor(query, timeout))))
    }

    addTool(
        name = "screenshot",
        description = "Capture a screenshot of the current screen via the Accessibility service (Android 11+ required).",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
    ) {
        val (base64, error) = LxAccessibilityService.screenshot()
        if (base64 != null) {
            CallToolResult(content = listOf(ImageContent(data = base64, mimeType = "image/jpeg")))
        } else {
            CallToolResult(content = listOf(TextContent(text = "Failed to take screenshot: $error")), isError = true)
        }
    }
}
