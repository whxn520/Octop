package chat.octop.mobile.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import chat.octop.mobile.BuildConfig

class SessionStore(context: Context) {
    private val prefs =
        runCatching {
            val masterKey =
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                "octop_session",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            context.getSharedPreferences("octop_session_plain", Context.MODE_PRIVATE)
        }

    /** Fixed at build time — not user-editable. */
    var baseUrl: String
        get() = BuildConfig.DEFAULT_BASE_URL.trimEnd('/')
        set(_) {
            // Ignore overrides; always use BuildConfig.DEFAULT_BASE_URL.
        }

    var accessToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    var mfaToken: String?
        get() = prefs.getString(KEY_MFA, null)
        set(value) {
            prefs.edit().putString(KEY_MFA, value).apply()
        }

    var selectedAgentId: String?
        get() = prefs.getString(KEY_AGENT, null)
        set(value) {
            prefs.edit().putString(KEY_AGENT, value).apply()
        }

    var username: String?
        get() = prefs.getString(KEY_USER, null)
        set(value) {
            prefs.edit().putString(KEY_USER, value).apply()
        }

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_MFA)
            .remove(KEY_AGENT)
            .remove(KEY_USER)
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_MFA = "mfa_token"
        private const val KEY_AGENT = "agent_id"
        private const val KEY_USER = "username"
    }
}
