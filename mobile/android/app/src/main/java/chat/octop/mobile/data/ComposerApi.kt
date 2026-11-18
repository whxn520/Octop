package chat.octop.mobile.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Composer-related HTTP helpers (upload, polish, STT, pickers). */
class ComposerApi(
    private val store: SessionStore,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun uploadFile(agentId: String, file: File, filename: String, mediaType: String): ChatAttachment {
        val body =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    filename,
                    file.asRequestBody(mediaType.toMediaType()),
                )
                .build()
        val req = authorizedBuilder("/api/agents/$agentId/upload").post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            captureToken(resp.header(OctopApi.RENEW_HEADER))
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, OctopApi.apiErrorMessage(json, text, "upload failed"))
            }
            return ChatAttachment(
                url = json.optString("url").ifBlank { json.optString("access_url") },
                workspacePath = json.optString("workspace_path").ifBlank { json.optString("path") },
                filename = json.optString("filename").ifBlank { filename },
                mediaType = json.optString("media_type").ifBlank { mediaType },
            )
        }
    }

    fun polishPrompt(agentId: String, text: String): String {
        val payload =
            JSONObject()
                .put("kind", "chat.prompt")
                .put("text", text)
                .put("agent_id", agentId)
                .toString()
                .toRequestBody(jsonMedia)
        val req = authorizedBuilder("/api/text/assist").post(payload).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            captureToken(resp.header(OctopApi.RENEW_HEADER))
            val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, OctopApi.apiErrorMessage(json, body, "polish failed"))
            }
            return json.optString("text").trim()
        }
    }

    data class ActiveVoice(
        val stt: String,
        val tts: String,
        val sttRealtime: Boolean,
    ) {
        val usesClientStt: Boolean
            get() = stt.isBlank() || stt.equals("browser", ignoreCase = true)
    }

    fun getActiveVoice(): ActiveVoice {
        val json = getObject("/api/voice/active")
        return ActiveVoice(
            stt = json.optString("stt").trim(),
            tts = json.optString("tts").trim(),
            sttRealtime = json.optBoolean("stt_realtime", false),
        )
    }

    /** First configured server STT provider (skips browser / edge). */
    fun resolveServerSttProvider(): String? {
        val active = getActiveVoice().stt
        if (active.isNotBlank() && !active.equals("browser", ignoreCase = true)) {
            return active
        }
        val arr = getArray("/api/voice/providers")
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (!item.optBoolean("enabled", true)) continue
            val kind = item.optString("kind")
            if (kind.equals("browser", ignoreCase = true) || kind.equals("edge", ignoreCase = true)) {
                continue
            }
            val cap = item.optString("capability")
            if (cap != "stt" && cap != "both") continue
            val name = item.optString("name")
            if (name.isNotBlank()) return name
        }
        return null
    }

    fun transcribe(
        audioFile: File,
        mime: String,
        language: String = "zh-CN",
        provider: String? = null,
    ): String {
        val sttProvider = provider ?: resolveServerSttProvider()
            ?: throw ApiException(400, "no server STT provider configured")
        val body =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("language", language)
                .addFormDataPart("provider", sttProvider)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody(mime.toMediaType()),
                )
                .build()
        val req = authorizedBuilder("/api/voice/stt").post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            captureToken(resp.header(OctopApi.RENEW_HEADER))
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, OctopApi.apiErrorMessage(json, text, "stt failed"))
            }
            return json.optString("text").trim()
        }
    }

    fun listConnectors(agentId: String?): List<ConnectorOption> {
        val arr = getArray("/api/connector-instances", agentId)
        val out = mutableListOf<ConnectorOption>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val status = item.optString("status")
            val usable = item.optBoolean("usable", true)
            val hasCreds = item.optBoolean("has_credentials", true)
            if (status != "active" || !usable || !hasCreds) continue
            val name = item.optString("mcp_server_name")
            if (name.isBlank()) continue
            out +=
                ConnectorOption(
                    mcpServerName = name,
                    label = item.optString("display_name").ifBlank { name },
                    kind = item.optString("kind"),
                    defaultOpen = item.optBoolean("default_open", false),
                )
        }
        return out
    }

    fun listKnowledgeBases(): List<KnowledgeOption> {
        val arr = getArray("/api/knowledge-bases")
        val out = mutableListOf<KnowledgeOption>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id =
                item.optString("knowledge_base_id").ifBlank {
                    item.optString("kb_id").ifBlank { item.optString("id") }
                }
            if (id.isBlank()) continue
            out += KnowledgeOption(id = id, name = item.optString("name").ifBlank { id })
        }
        return out
    }

    fun listSkills(agentId: String): List<SkillOption> {
        val arr = getArray("/api/agents/$agentId/skills")
        val out = mutableListOf<SkillOption>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val slug = item.optString("slug").ifBlank { item.optString("name") }
            if (slug.isBlank()) continue
            out +=
                SkillOption(
                    slug = slug,
                    name = item.optString("name").ifBlank { slug },
                    enabled = item.optBoolean("enabled", true),
                )
        }
        return out
    }

    fun listModels(agentId: String?): List<ModelOption> {
        val arr = getArray("/api/providers/resolved", agentId)
        val out = mutableListOf<ModelOption>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val provider = item.optString("provider_name")
            val model = item.optString("model")
            if (provider.isBlank() || model.isBlank()) continue
            out +=
                ModelOption(
                    providerName = provider,
                    model = model,
                    displayName = item.optString("name").ifBlank { "$provider/$model" },
                    reasoning = item.optBoolean("reasoning", false),
                )
        }
        return out
    }

    fun listSubagents(agentId: String): List<SubagentOption> {
        val arr = getArray("/api/agents/$agentId/subagents")
        val out = mutableListOf<SubagentOption>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val slug = item.optString("slug").ifBlank { item.optString("name") }
            if (slug.isBlank()) continue
            out += SubagentOption(slug = slug, name = item.optString("name").ifBlank { slug })
        }
        return out
    }

    private fun getArray(path: String, agentId: String? = null): JSONArray {
        val req = authorizedBuilder(path, agentId).get().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            captureToken(resp.header(OctopApi.RENEW_HEADER))
            if (!resp.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                throw ApiException(resp.code, json.optString("message", text))
            }
            val trimmed = text.trimStart()
            if (trimmed.startsWith("[")) return JSONArray(trimmed)
            val obj = runCatching { JSONObject(trimmed) }.getOrElse { JSONObject() }
            return obj.optJSONArray("items")
                ?: obj.optJSONArray("data")
                ?: obj.optJSONArray("commands")
                ?: JSONArray()
        }
    }

    private fun getObject(path: String): JSONObject {
        val req = authorizedBuilder(path).get().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            captureToken(resp.header(OctopApi.RENEW_HEADER))
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, json.optString("message", text))
            }
            return json
        }
    }

    private fun authorizedBuilder(path: String, agentId: String? = null): Request.Builder {
        val token = store.accessToken ?: throw ApiException(401, "not signed in")
        val builder =
            Request.Builder()
                .url("${store.baseUrl}$path")
                .header("Authorization", "Bearer $token")
        if (!agentId.isNullOrBlank()) {
            builder.header("X-Octop-Agent-Id", agentId)
        }
        return builder
    }

    private fun captureToken(renewed: String?) {
        if (!renewed.isNullOrBlank()) store.accessToken = renewed
    }
}
