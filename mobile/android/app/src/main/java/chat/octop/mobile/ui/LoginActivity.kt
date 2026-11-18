package chat.octop.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import chat.octop.mobile.OctopApp
import chat.octop.mobile.R
import chat.octop.mobile.data.ApiException
import chat.octop.mobile.data.OctopApi
import chat.octop.mobile.databinding.ActivityLoginBinding
import chat.octop.mobile.push.PushRegistrar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val app get() = application as OctopApp
    private var passwordVisible = false
    private var captchaProvider = "slider"
    private var captchaGateOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = ContextCompat.getColor(this, R.color.brand_active)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.n1)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.username.setText(app.sessionStore.username.orEmpty())

        if (!app.sessionStore.accessToken.isNullOrBlank()) {
            openChat(deepLinkExtras())
            return
        }

        wireFieldFocus(binding.username, binding.usernameField)
        wireFieldFocus(binding.password, binding.passwordField)
        wireFieldFocus(binding.mfaCode, binding.mfaField)

        binding.slideCaptcha.onVerified = {
            captchaGateOk = true
            refreshSignInEnabled()
        }
        binding.slideCaptcha.onReset = {
            captchaGateOk = false
            refreshSignInEnabled()
        }

        binding.togglePassword.setOnClickListener { togglePassword() }
        binding.signIn.setOnClickListener { signIn() }
        binding.forgotPassword.setOnClickListener {
            showHelp(R.string.forgot_password_title, R.string.forgot_password_body)
        }
        binding.password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                signIn()
                true
            } else {
                false
            }
        }
        listOf(binding.username, binding.password).forEach {
            it.doAfterTextChanged { refreshSignInEnabled() }
        }
        refreshSignInEnabled()
        loadCaptchaConfig()
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun loadCaptchaConfig() {
        lifecycleScope.launch {
            try {
                val cfg = withContext(Dispatchers.IO) { OctopApi(app.sessionStore).getCaptcha() }
                captchaProvider = cfg.provider.ifBlank { "slider" }
                applyCaptchaMode()
            } catch (e: Exception) {
                Log.w(TAG, "captcha config failed; defaulting to slider", e)
                captchaProvider = "slider"
                applyCaptchaMode()
            }
        }
    }

    private fun applyCaptchaMode() {
        val slider = captchaProvider == "slider"
        binding.slideCaptcha.visibility = if (slider) View.VISIBLE else View.GONE
        binding.captchaUnsupported.visibility = if (slider) View.GONE else View.VISIBLE
        binding.captchaSection.visibility = View.VISIBLE
        if (slider) {
            binding.slideCaptcha.reset()
            captchaGateOk = false
        } else {
            captchaGateOk = false
        }
        refreshSignInEnabled()
    }

    private fun showHelp(titleRes: Int, bodyRes: Int) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun wireFieldFocus(edit: EditText, field: View) {
        edit.setOnFocusChangeListener { _, hasFocus ->
            field.setBackgroundResource(
                if (hasFocus) R.drawable.bg_login_field_focused else R.drawable.bg_login_field,
            )
        }
    }

    private fun togglePassword() {
        passwordVisible = !passwordVisible
        val start = binding.password.selectionStart
        binding.password.inputType =
            if (passwordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        binding.password.setSelection(start.coerceAtLeast(0))
    }

    private fun refreshSignInEnabled() {
        val fieldsReady =
            binding.username.text?.isNotBlank() == true &&
                binding.password.text?.isNotEmpty() == true
        val captchaReady = captchaProvider == "slider" && captchaGateOk
        val ready = fieldsReady && captchaReady
        binding.signIn.isEnabled = ready
        binding.signIn.alpha = if (ready) 1f else 0.55f
    }

    private fun signIn() {
        if (!binding.signIn.isEnabled) {
            if (captchaProvider == "slider" && !captchaGateOk) {
                showError(getString(R.string.captcha_required))
            }
            return
        }
        val user = binding.username.text?.toString()?.trim().orEmpty()
        val pass = binding.password.text?.toString().orEmpty()
        if (user.isBlank() || pass.isBlank()) {
            showError(getString(R.string.login_credentials_required))
            return
        }
        binding.signIn.isEnabled = false
        binding.errorText.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val mfaVisible = binding.mfaLayout.visibility == View.VISIBLE
                val result =
                    withContext(Dispatchers.IO) {
                        val api = OctopApi(app.sessionStore)
                        if (mfaVisible) {
                            val mfa = app.sessionStore.mfaToken
                                ?: throw ApiException(400, "missing mfa token")
                            api.verifyMfa(mfa, binding.mfaCode.text?.toString().orEmpty())
                        } else {
                            api.login(user, pass)
                        }
                    }
                if (result.mfaRequired) {
                    app.sessionStore.mfaToken = result.mfaToken
                    binding.mfaLayout.visibility = View.VISIBLE
                    showError(getString(R.string.mfa_code_required))
                    binding.slideCaptcha.reset()
                    refreshSignInEnabled()
                } else {
                    app.sessionStore.accessToken = result.accessToken
                    app.sessionStore.username = user
                    app.sessionStore.mfaToken = null
                    PushRegistrar.registerCurrentIfPossible(app)
                    openChat(deepLinkExtras())
                }
            } catch (e: Exception) {
                Log.e(TAG, "sign-in failed url=${app.sessionStore.baseUrl}", e)
                showError(e.message ?: getString(R.string.sign_in_failed))
                binding.slideCaptcha.reset()
                refreshSignInEnabled()
            }
        }
    }

    private fun openChat(extras: Bundle?) {
        startActivity(
            Intent(this, ChatActivity::class.java).apply {
                if (extras != null) putExtras(extras)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
        finish()
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "octop" || data.host != "chat") return
        val agentId = data.getQueryParameter("agent_id")
        val threadId = data.getQueryParameter("thread_id")
        if (!agentId.isNullOrBlank() && !app.sessionStore.accessToken.isNullOrBlank()) {
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_AGENT_ID, agentId)
                    .putExtra(ChatActivity.EXTRA_THREAD_ID, threadId),
            )
            finish()
        }
    }

    private fun deepLinkExtras(): Bundle? {
        val data = intent?.data ?: return null
        if (data.scheme != "octop") return null
        return Bundle().apply {
            putString(ChatActivity.EXTRA_AGENT_ID, data.getQueryParameter("agent_id"))
            putString(ChatActivity.EXTRA_THREAD_ID, data.getQueryParameter("thread_id"))
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "LoginActivity"
    }
}
