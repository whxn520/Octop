package chat.octop.mobile.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatSocket(
    private val store: SessionStore,
    private val agentId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onFrame(json: JSONObject)
        fun onClosed(reason: String)
        fun onFailure(error: Throwable)
    }

    private val client =
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    private var socket: WebSocket? = null

    fun connect() {
        val token = store.accessToken ?: run {
            listener.onFailure(ApiException(401, "not signed in"))
            return
        }
        val wsBase = store.baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val url = "$wsBase/api/agents/$agentId/chat/ws?token=$token"
        val req = Request.Builder().url(url).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrElse { return }
                listener.onFrame(json)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t)
            }
        })
    }

    fun sendUserTurn(
        threadId: String,
        text: String,
        options: ComposerTurnOptions = ComposerTurnOptions(),
    ) {
        val content = buildContent(text, options.attachments)
        val message =
            JSONObject()
                .put("role", "user")
                .put("content", content)
        val frame =
            JSONObject()
                .put("type", "user_turn")
                .put("thread_id", threadId)
                .put("text", text)
                .put("messages", JSONArray().put(message))
        options.mcpServers?.let { names ->
            val arr = JSONArray()
            names.forEach { arr.put(it) }
            frame.put("mcp_servers", arr)
        }
        options.knowledgeBaseIds?.let { ids ->
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            frame.put("knowledge_base_ids", arr)
        }
        if (options.targetAgentIds.isNotEmpty()) {
            val arr = JSONArray()
            options.targetAgentIds.forEach { arr.put(it) }
            frame.put("target_agent_ids", arr)
        }
        options.modelRef?.takeIf { it.isNotBlank() }?.let { frame.put("model", it) }
        options.reasoningMode?.takeIf { it.isNotBlank() }?.let { frame.put("reasoning_mode", it) }
        options.reasoningEffort?.takeIf { it.isNotBlank() }?.let { frame.put("reasoning_effort", it) }
        options.conversationMode?.takeIf { it.isNotBlank() }?.let {
            frame.put("conversation_mode", it)
        }
        socket?.send(frame.toString())
    }

    private fun buildContent(text: String, attachments: List<ChatAttachment>): Any {
        if (attachments.isEmpty()) return text
        val arr = JSONArray()
        if (text.isNotBlank()) {
            arr.put(JSONObject().put("type", "text").put("text", text))
        }
        for (att in attachments) {
            if (att.isImage) {
                val source =
                    JSONObject()
                        .put("type", "url")
                        .put("url", att.url)
                if (att.mediaType.isNotBlank()) source.put("media_type", att.mediaType)
                val block =
                    JSONObject()
                        .put("type", "image")
                        .put("source", source)
                if (att.workspacePath.isNotBlank()) {
                    block.put("workspace_path", att.workspacePath)
                }
                arr.put(block)
            } else {
                val block =
                    JSONObject()
                        .put("type", "file")
                        .put("filename", att.filename)
                if (att.mediaType.isNotBlank()) block.put("media_type", att.mediaType)
                if (att.workspacePath.isNotBlank()) {
                    block.put("workspace_path", att.workspacePath)
                }
                arr.put(block)
            }
        }
        return arr
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }
}
