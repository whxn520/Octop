package chat.octop.mobile.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OctopApi(
    private val store: SessionStore,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun getCaptcha(): CaptchaPublic {
        val req =
            Request.Builder()
                .url("${store.baseUrl}/api/auth/captcha")
                .get()
                .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, apiErrorMessage(json, text, "captcha config failed"))
            }
            return CaptchaPublic(
                provider = json.optString("provider").ifBlank { "slider" },
                siteKey = json.optString("site_key").ifBlank { null },
            )
        }
    }

    fun login(username: String, password: String, captchaToken: String? = null): LoginResult {
        val payload =
            JSONObject()
                .put("username", username)
                .put("password", password)
        if (!captchaToken.isNullOrBlank()) {
            payload.put("captcha_token", captchaToken)
        }
        val body = payload.toString().toRequestBody(jsonMedia)
        val req =
            Request.Builder()
                .url("${store.baseUrl}/api/auth/login")
                .post(body)
                .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, apiErrorMessage(json, text, "login failed"))
            }
            // Envelope may be flat or nested under "error".
            val mfaRequired = json.optBoolean("mfa_required") ||
                json.optJSONObject("error")?.optBoolean("mfa_required") == true
            val mfa = json.optString("mfa_token").ifBlank {
                json.optJSONObject("error")?.optString("mfa_token").orEmpty()
            }.ifBlank { null }
            if (mfa != null || mfaRequired) {
                return LoginResult(mfaToken = mfa, mfaRequired = true)
            }
            val token = json.optString("access_token")
            if (token.isBlank()) {
                throw ApiException(resp.code, "missing access_token")
            }
            captureRenewedToken(resp.header(RENEW_HEADER), token)
            return LoginResult(accessToken = token)
        }
    }

    fun verifyMfa(mfaToken: String, code: String): LoginResult {
        val body =
            JSONObject()
                .put("mfa_token", mfaToken)
                .put("method", "totp")
                .put("code", code)
                .toString()
                .toRequestBody(jsonMedia)
        val req =
            Request.Builder()
                .url("${store.baseUrl}/api/auth/mfa/verify")
                .post(body)
                .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, apiErrorMessage(json, text, "mfa failed"))
            }
            val token = json.optString("access_token")
            if (token.isBlank()) throw ApiException(resp.code, "missing access_token")
            captureRenewedToken(resp.header(RENEW_HEADER), token)
            return LoginResult(accessToken = token)
        }
    }

    fun listAgents(): List<AgentSummary> {
        val arr = authorizedGetArray("/api/agents")
        val out = mutableListOf<AgentSummary>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            // Public id is agent_id (ULID). JSON "id" is the integer PK — do not use it in URLs.
            val id = item.optString("agent_id").ifBlank {
                val raw = item.opt("id")
                if (raw is String) raw else ""
            }
            val name = item.optString("name").ifBlank { id }
            val kind = item.optString("kind").ifBlank { "expert" }
            val iconUrl = item.optString("icon_url").ifBlank { null }
            if (id.isNotBlank()) out += AgentSummary(id, name, kind, iconUrl)
        }
        return out
    }

    fun listThreads(agentId: String): List<ThreadSummary> {
        val arr = authorizedGetArray("/api/agents/$agentId/threads")
        val out = mutableListOf<ThreadSummary>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.optString("thread_id").ifBlank { item.optString("id") }
            if (id.isBlank()) continue
            // Empty "new chat" shells must not appear in History until the first turn.
            val hasMessages = item.optBoolean("has_messages", false)
            if (!hasMessages) continue
            out +=
                ThreadSummary(
                    threadId = id,
                    title = item.optString("title").ifBlank { id },
                    hasMessages = true,
                )
        }
        return out
    }

    fun createThread(agentId: String): ThreadSummary {
        val req =
            authorizedBuilder("/api/agents/$agentId/threads")
                .post("{}".toRequestBody(jsonMedia))
                .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            captureRenewedToken(resp.header(RENEW_HEADER))
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, apiErrorMessage(json, text, "create thread failed"))
            }
            val id = json.optString("thread_id").ifBlank { json.optString("id") }
            return ThreadSummary(
                threadId = id,
                title = json.optString("title").ifBlank { id },
                hasMessages = false,
            )
        }
    }

    fun history(agentId: String, threadId: String): List<ChatMessage> {
        val json = authorizedGet("/api/agents/$agentId/threads/$threadId/history")
        val arr = json.optJSONArray("messages")
            ?: json.optJSONArray("items")
            ?: json.optJSONArray("data")
            ?: JSONArray()
        val out = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val role = item.optString("role").ifBlank { item.optString("type", "assistant") }
            val text = extractText(item)
            val octopUi = extractOctopUi(item)
            val speakerId = item.optString("agent_id").ifBlank { null }
            if (text.isNotBlank() || octopUi != null) {
                out +=
                    ChatMessage(
                        role = role,
                        text = text,
                        octopUiJson = octopUi,
                        speakerAgentId = speakerId,
                    )
            }
        }
        return out
    }

    fun getChatWelcome(agentId: String, locale: String = preferredLocale()): ChatWelcome {
        val json = authorizedGet("/api/agents/$agentId/chat/welcome")
        val welcome = pickLocalized(json.opt("welcome_message"), locale).ifBlank { null }
        val arr = json.optJSONArray("quick_prompts") ?: JSONArray()
        val cards = mutableListOf<WelcomeCard>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = pickLocalized(item.opt("title"), locale)
            val description = pickLocalized(item.opt("description"), locale)
            val prompt = pickLocalized(item.opt("prompt"), locale)
            if (prompt.isBlank() && title.isBlank()) continue
            cards +=
                WelcomeCard(
                    title = title.ifBlank { prompt.take(40) },
                    description = description,
                    prompt = prompt.ifBlank { title },
                    expertName = item.optString("expert").ifBlank { null },
                    iconName = item.optString("icon_name").ifBlank { null },
                    iconUrl = item.optString("icon_url").ifBlank { null },
                    color = item.optString("color").ifBlank { null },
                )
        }
        return ChatWelcome(welcomeMessage = welcome, cards = cards)
    }

    fun registerPushDevice(deviceToken: String, appId: String = "chat.octop.mobile") {
        val body =
            JSONObject()
                .put("platform", "android")
                .put("device_token", deviceToken)
                .put("app_id", appId)
                .toString()
                .toRequestBody(jsonMedia)
        val req = authorizedBuilder("/api/push/devices").post(body).build()
        client.newCall(req).execute().use { resp ->
            captureRenewedToken(resp.header(RENEW_HEADER))
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                throw ApiException(resp.code, text.ifBlank { "push register failed" })
            }
        }
    }

    fun unregisterPushDevice(deviceToken: String) {
        val body =
            JSONObject()
                .put("platform", "android")
                .put("device_token", deviceToken)
                .toString()
                .toRequestBody(jsonMedia)
        val req = authorizedBuilder("/api/push/devices").delete(body).build()
        client.newCall(req).execute().use { resp ->
            captureRenewedToken(resp.header(RENEW_HEADER))
        }
    }

    private fun authorizedGet(path: String): JSONObject {
        val req = authorizedBuilder(path).get().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            captureRenewedToken(resp.header(RENEW_HEADER))
            val json = runCatching { JSONObject(text) }.getOrElse {
                if (text.trimStart().startsWith("[")) {
                    return JSONObject().put("items", JSONArray(text))
                }
                JSONObject()
            }
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, json.optString("message", text))
            }
            return json
        }
    }

    private fun authorizedGetArray(path: String): JSONArray {
        val req = authorizedBuilder(path).get().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            captureRenewedToken(resp.header(RENEW_HEADER))
            if (!resp.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                throw ApiException(resp.code, json.optString("message", text))
            }
            val trimmed = text.trimStart()
            if (trimmed.startsWith("[")) {
                return JSONArray(trimmed)
            }
            val obj = runCatching { JSONObject(trimmed) }.getOrElse { JSONObject() }
            return obj.optJSONArray("threads")
                ?: obj.optJSONArray("agents")
                ?: obj.optJSONArray("items")
                ?: obj.optJSONArray("data")
                ?: JSONArray()
        }
    }

    private fun authorizedBuilder(path: String): Request.Builder {
        val token = store.accessToken ?: throw ApiException(401, "not signed in")
        return Request.Builder()
            .url("${store.baseUrl}$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
    }

    private fun captureRenewedToken(header: String?, fallback: String? = null) {
        val renewed = header?.takeIf { it.isNotBlank() } ?: fallback
        if (!renewed.isNullOrBlank()) {
            store.accessToken = renewed
        }
    }

    private fun extractText(item: JSONObject): String {
        // Do not use optString("content") first: when content is a JSONArray,
        // org.json stringifies the whole array and bubbles show raw JSON.
        val content = item.opt("content")
        when (content) {
            is JSONArray -> return flattenContentParts(content)
            is String -> {
                val trimmed = content.trim()
                if (trimmed.startsWith("[")) {
                    runCatching { flattenContentParts(JSONArray(trimmed)) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                }
                if (trimmed.isNotBlank()) return trimmed
            }
        }
        return item.optString("text").trim()
    }

    private fun flattenContentParts(parts: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.opt(i) ?: continue
            val piece =
                when (part) {
                    is String -> part
                    is JSONObject -> {
                        val type = part.optString("type")
                        when {
                            type == "text" || type.isBlank() ->
                                part.optString("text").ifBlank { part.optString("content") }
                            type == "output_text" -> part.optString("text")
                            else -> "" // skip thinking / tool_use / tool_result noise
                        }
                    }
                    else -> ""
                }
            if (piece.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(piece.trim())
            }
        }
        return sb.toString()
    }

    private fun extractOctopUi(item: JSONObject): String? {
        val output = item.opt("output")
        if (output is JSONObject && output.has("octop_ui")) {
            return output.toString()
        }
        if (item.has("octop_ui")) {
            return item.toString()
        }
        val artifact = item.opt("artifact")
        if (artifact is JSONObject && artifact.has("octop_ui")) {
            return artifact.toString()
        }
        return null
    }

    companion object {
        const val RENEW_HEADER = "X-Octop-Access-Token"

        fun preferredLocale(): String =
            if (java.util.Locale.getDefault().language.startsWith("zh")) "zh" else "en"

        fun pickLocalized(raw: Any?, locale: String): String {
            when (raw) {
                null, JSONObject.NULL -> return ""
                is String -> return raw.trim()
                is JSONObject -> {
                    val primary = raw.optString(locale).trim()
                    if (primary.isNotBlank()) return primary
                    val fallback = if (locale == "zh") "en" else "zh"
                    return raw.optString(fallback).trim()
                }
            }
            return raw.toString().trim()
        }

        fun apiErrorMessage(json: JSONObject, raw: String, fallback: String): String {
            val nested = json.optJSONObject("error")
            val msg =
                nested?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json.optString("message").takeIf { it.isNotBlank() }
                    ?: raw.takeIf { it.isNotBlank() }
                    ?: fallback
            val code = nested?.optString("code")?.takeIf { it.isNotBlank() }
            return if (code != null) "$code: $msg" else msg
        }
    }
}

class ApiException(val status: Int, message: String) : Exception(message)
