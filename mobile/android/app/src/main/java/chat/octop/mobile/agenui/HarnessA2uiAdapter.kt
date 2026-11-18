package chat.octop.mobile.agenui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps Octop harness / octop_ui payloads into A2UI protocol JSON lines
 * that AGenUI can consume via beginTextStream / receiveTextChunk / endTextStream.
 */
class HarnessA2uiAdapter {
    private var surfaceId: String = "octop-chat"
    private var streaming = false
    private val tokenBuffer = StringBuilder()

    fun beginTurn(surfaceId: String = "octop-chat") {
        this.surfaceId = surfaceId
        tokenBuffer.clear()
        streaming = true
    }

    fun onToken(text: String): List<String> {
        if (!streaming || text.isEmpty()) return emptyList()
        tokenBuffer.append(text)
        return listOf(textSurfaceUpdate(tokenBuffer.toString()))
    }

    fun onOctopUi(envelopeJson: String): List<String> {
        val root = runCatching { JSONObject(envelopeJson) }.getOrNull() ?: return emptyList()
        val octopUi = root.optJSONObject("octop_ui") ?: root
        val renderer = octopUi.optString("renderer").ifBlank { "unknown" }
        val data = root.opt("data") ?: octopUi.opt("data") ?: JSONObject()
        val title = when (data) {
            is JSONObject -> data.optString("title").ifBlank { renderer }
            else -> renderer
        }
        val body = when (data) {
            is JSONObject -> data.toString(2)
            is JSONArray -> data.toString(2)
            else -> data.toString()
        }
        return listOf(cardSurface(title, body))
    }

    fun endTurn(): List<String> {
        streaming = false
        val finalText = tokenBuffer.toString()
        tokenBuffer.clear()
        return if (finalText.isNotBlank()) listOf(textSurfaceUpdate(finalText)) else emptyList()
    }

    private fun textSurfaceUpdate(text: String): String {
        val components =
            JSONObject()
                .put(
                    "root",
                    JSONObject()
                        .put("type", "Column")
                        .put("children", JSONArray().put("body")),
                )
                .put(
                    "body",
                    JSONObject()
                        .put("type", "Text")
                        .put("text", text),
                )
        return JSONObject()
            .put("surfaceUpdate", JSONObject()
                .put("surfaceId", surfaceId)
                .put("components", components))
            .toString()
    }

    private fun cardSurface(title: String, body: String): String {
        val components =
            JSONObject()
                .put(
                    "root",
                    JSONObject()
                        .put("type", "Card")
                        .put("children", JSONArray().put("title").put("body")),
                )
                .put(
                    "title",
                    JSONObject()
                        .put("type", "Text")
                        .put("text", title),
                )
                .put(
                    "body",
                    JSONObject()
                        .put("type", "Text")
                        .put("text", body),
                )
        return JSONObject()
            .put("surfaceUpdate", JSONObject()
                .put("surfaceId", surfaceId)
                .put("components", components))
            .toString()
    }
}
