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
