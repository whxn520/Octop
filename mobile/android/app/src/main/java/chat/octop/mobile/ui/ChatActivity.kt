package chat.octop.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import androidx.core.graphics.drawable.DrawableCompat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import chat.octop.mobile.OctopApp
import chat.octop.mobile.R
import chat.octop.mobile.agenui.AgenUiBridge
import chat.octop.mobile.agenui.HarnessA2uiAdapter
import chat.octop.mobile.data.AgentSummary
import chat.octop.mobile.data.ChatMessage
import chat.octop.mobile.data.ChatSocket
import chat.octop.mobile.data.ComposerApi
import chat.octop.mobile.data.OctopApi
import chat.octop.mobile.data.SessionStore
import chat.octop.mobile.data.ThreadSummary
import chat.octop.mobile.data.WelcomeCard
import chat.octop.mobile.databinding.ActivityChatBinding
import chat.octop.mobile.databinding.LayoutDrawerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.max

class ChatActivity : AppCompatActivity(), ChatSocket.Listener {
    private lateinit var binding: ActivityChatBinding
    private lateinit var drawerBinding: LayoutDrawerBinding
    private val app get() = application as OctopApp
    private val messages = mutableListOf<ChatMessage>()
    private val agents = mutableListOf<AgentSummary>()
    private val visibleAgents = mutableListOf<AgentSummary>()
    private val threads = mutableListOf<ThreadSummary>()
    private var selectedAgent: AgentSummary? = null
    private var agentId: String = ""
    private var threadId: String = ""
    private var navScopeTeams = false
    private var socket: ChatSocket? = null
    private var agenSession: AgenUiBridge.Session? = null
    private val a2ui = HarnessA2uiAdapter()
    private var turnOpen = false
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var agentAdapter: DrawerAgentAdapter
    private lateinit var threadAdapter: DrawerThreadAdapter
    private val composerState = ComposerSelectionState()
    private val voiceRecorder = VoiceRecorder(this)
    private var voiceBaseline: String = ""
    private var polishing = false
    private var generatingActive = false
    private var generatingAwaitingReply = false
    private var generatingOnTool = false
    private var generatingStartedAt = 0L
    private var generatingPhraseIndex = 0
    private val generatingHandler = Handler(Looper.getMainLooper())
    private var generatingTickCount = 0
    private val generatingTicker =
        object : Runnable {
            override fun run() {
                if (!generatingActive) return
                generatingTickCount += 1
                // Rotate awaiting phrases every ~3s (PC footer keeps status for the whole turn).
                if (generatingAwaitingReply && !generatingOnTool && generatingTickCount % 3 == 0) {
                    generatingPhraseIndex =
                        (generatingPhraseIndex + 1) % awaitingStatusPhrases().size
                }
                refreshGeneratingLabel()
                generatingHandler.postDelayed(this, 1_000L)
            }
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) uploadUri(uri, fallbackMime = "application/octet-stream")
        }
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) uploadFolder(uri)
        }
    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginVoiceInput() else Toast.makeText(this, R.string.mic_permission, Toast.LENGTH_SHORT).show()
        }
    private val speechRecognize =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val spoken =
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
            if (spoken.isNotBlank()) appendComposerText(spoken)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = ContextCompat.getColor(this, R.color.brand_active)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        drawerBinding = binding.drawer

        setupDrawerHeader()
        setupLists()
        setupNav()
        setupComposer()
        agenSession = AgenUiBridge.createSession(this, binding.agenUiContainer)
        binding.send.setOnClickListener { sendMessage() }
        updateGreeting(null)

        val deepAgent = intent.getStringExtra(EXTRA_AGENT_ID)
        val deepThread = intent.getStringExtra(EXTRA_THREAD_ID)
        bootstrap(deepAgent, deepThread)
    }

    override fun onDestroy() {
        clearPendingStatus()
        voiceRecorder.cancel()
        socket?.close()
        agenSession?.destroy()
        super.onDestroy()
    }

    private fun awaitingStatusPhrases(): List<String> =
        listOf(
            getString(R.string.agent_status_analyzing),
            getString(R.string.agent_status_thinking),
            getString(R.string.agent_status_processing),
            getString(R.string.agent_status_loading),
        )

    private fun generatingElapsedSeconds(): Int =
        max(0, ((System.currentTimeMillis() - generatingStartedAt) / 1000L).toInt())

    private fun showGeneratingStatus() {
        clearGeneratingStatus()
        generatingActive = true
        generatingAwaitingReply = true
        generatingOnTool = false
        generatingStartedAt = System.currentTimeMillis()
        generatingPhraseIndex = 0
        generatingTickCount = 0
        // Keep status in the message list so it sits right under the user turn.
        binding.generatingStatus.visibility = View.GONE
        val agent = selectedAgent
        messages +=
            ChatMessage(
                role = "assistant",
                text = currentGeneratingLabel(),
                speakerAgentId = agent?.id,
                speakerName = agent?.name,
                speakerIconUrl = agent?.iconUrl,
                pending = true,
            )
        messageAdapter.notifyItemInserted(messages.lastIndex)
        updateConversationVisibility()
        binding.messageList.scrollToPosition(messages.lastIndex)
        generatingHandler.postDelayed(generatingTicker, 1_000L)
    }

    private fun markGeneratingAnswering() {
        if (!generatingActive) return
        generatingAwaitingReply = false
        generatingOnTool = false
        refreshGeneratingLabel()
    }

    private fun markGeneratingTool() {
        if (!generatingActive) return
        generatingOnTool = true
        refreshGeneratingLabel()
    }

    private fun currentGeneratingLabel(): String {
        val body =
            when {
                generatingOnTool -> getString(R.string.agent_status_tool)
                generatingAwaitingReply -> {
                    val phrases = awaitingStatusPhrases()
                    val base = phrases[generatingPhraseIndex % phrases.size]
                    getString(R.string.agent_status_with_elapsed, base, generatingElapsedSeconds())
                }
                selectedAgent?.isTeam == true ->
                    getString(R.string.agent_status_member_answering, pendingAgentName())
                else -> getString(R.string.agent_status_answering)
            }
        return "··· $body"
    }

    private fun refreshGeneratingLabel() {
        if (!generatingActive) return
        val idx = messages.indexOfLast { it.pending }
        if (idx < 0) return
        messages[idx] = messages[idx].copy(text = currentGeneratingLabel())
        messageAdapter.notifyItemChanged(idx)
    }

    private fun pendingAgentName(): String =
        selectedAgent?.name.orEmpty().ifBlank { getString(R.string.app_name) }

    private fun clearGeneratingStatus() {
        generatingActive = false
        generatingAwaitingReply = false
        generatingOnTool = false
        generatingHandler.removeCallbacks(generatingTicker)
        binding.generatingStatus.visibility = View.GONE
        val idx = messages.indexOfLast { it.pending }
        if (idx >= 0) {
            messages.removeAt(idx)
            messageAdapter.notifyItemRemoved(idx)
        }
    }

    private fun clearPendingStatus() = clearGeneratingStatus()

    private fun setupComposer() {
        binding.plusButton.setOnClickListener { openPlusSheet() }
        binding.polishButton.setOnClickListener { polishComposer() }
        binding.micButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                beginVoiceInput()
            } else {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        refreshPlusBadge()
        renderAttachmentStrip()
    }

    private fun appendComposerText(spoken: String) {
        val baseline = voiceBaseline.ifBlank { binding.composer.text?.toString().orEmpty() }
        val merged =
            listOf(baseline.trim(), spoken.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        binding.composer.setText(merged)
        binding.composer.setSelection(merged.length)
    }

    private fun beginVoiceInput() {
        if (voiceRecorder.isRecording) {
            stopServerRecordingAndTranscribe()
            return
        }
        voiceBaseline = binding.composer.text?.toString().orEmpty()
        lifecycleScope.launch {
            try {
                val active =
                    withContext(Dispatchers.IO) {
                        ComposerApi(app.sessionStore).getActiveVoice()
                    }
                if (active.usesClientStt) {
                    launchDeviceSpeechRecognition()
                } else {
                    startServerRecording()
                }
            } catch (e: Exception) {
                // If active-voice lookup fails, still try on-device STT.
                if (!launchDeviceSpeechRecognition()) {
                    Toast.makeText(
                        this@ChatActivity,
                        e.message ?: getString(R.string.stt_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun launchDeviceSpeechRecognition(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Fall back to cloud STT upload path when the device has no recognizer.
            lifecycleScope.launch {
                val provider =
                    withContext(Dispatchers.IO) {
                        runCatching { ComposerApi(app.sessionStore).resolveServerSttProvider() }.getOrNull()
                    }
                if (provider.isNullOrBlank()) {
                    Toast.makeText(this@ChatActivity, R.string.stt_unavailable, Toast.LENGTH_LONG).show()
                } else {
                    startServerRecording()
                }
            }
            return false
        }
        val localeTag = if (OctopApi.preferredLocale() == "zh") "zh-CN" else "en-US"
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.stt_speak_now))
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
        return try {
            speechRecognize.launch(intent)
            true
        } catch (_: Exception) {
            Toast.makeText(this, R.string.stt_unavailable, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun startServerRecording() {
        try {
            voiceRecorder.start()
            binding.micButton.setColorFilter(ContextCompat.getColor(this, R.color.error))
            Toast.makeText(this, R.string.recording, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: getString(R.string.stt_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServerRecordingAndTranscribe() {
        val file = voiceRecorder.stop() ?: return
        binding.micButton.setColorFilter(ContextCompat.getColor(this, R.color.n9))
        val language = if (OctopApi.preferredLocale() == "zh") "zh-CN" else "en-US"
        lifecycleScope.launch {
            try {
                val spoken =
                    withContext(Dispatchers.IO) {
                        ComposerApi(app.sessionStore).transcribe(file, "audio/mp4", language)
                    }
                file.delete()
                if (spoken.isNotBlank()) appendComposerText(spoken)
            } catch (e: Exception) {
                file.delete()
                Toast.makeText(
                    this@ChatActivity,
                    e.message ?: getString(R.string.stt_provider_required),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openPlusSheet() {
        ComposerPlusSheet(
            activity = this,
            store = app.sessionStore,
            scope = lifecycleScope,
            state = composerState,
            agentsProvider = { agents.toList() },
            agentIdProvider = { agentId },
            isTeamProvider = { selectedAgent?.isTeam == true },
            onInsertText = { token ->
                val cur = binding.composer.text?.toString().orEmpty()
                val next = if (cur.isBlank()) token else "$cur$token"
                binding.composer.setText(next)
                binding.composer.setSelection(next.length)
            },
            onUploadFile = { pickFile.launch("*/*") },
            onUploadFolder = { pickFolder.launch(null) },
            onChanged = {
                refreshPlusBadge()
            },
        ).show()
    }

    private fun refreshPlusBadge() {
        val n = composerState.selectionCount()
        if (n <= 0) {
            binding.plusBadge.visibility = View.GONE
        } else {
            binding.plusBadge.visibility = View.VISIBLE
            binding.plusBadge.text = if (n > 9) "9+" else n.toString()
        }
    }

    private fun renderAttachmentStrip() {
        binding.attachmentStrip.removeAllViews()
        if (composerState.attachments.isEmpty()) {
            binding.attachmentStripScroll.visibility = View.GONE
            return
        }
        binding.attachmentStripScroll.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        composerState.attachments.forEachIndexed { index, att ->
            val view = inflater.inflate(R.layout.item_attachment_chip, binding.attachmentStrip, false)
            view.findViewById<TextView>(R.id.name).text = att.filename
            view.findViewById<ImageButton>(R.id.remove).setOnClickListener {
                composerState.attachments.removeAt(index)
                renderAttachmentStrip()
            }
            binding.attachmentStrip.addView(view)
        }
    }

    private fun uploadUri(uri: Uri, fallbackMime: String) {
        val agent = selectedAgent ?: return
        lifecycleScope.launch {
            try {
                uploadOne(agent.id, uri, fallbackMime)
                renderAttachmentStrip()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ChatActivity,
                    e.message ?: getString(R.string.upload_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun uploadFolder(treeUri: Uri) {
        val agent = selectedAgent ?: return
        lifecycleScope.launch {
            try {
                val files =
                    withContext(Dispatchers.IO) {
                        collectFolderFiles(treeUri)
                    }
                if (files.isEmpty()) {
                    Toast.makeText(this@ChatActivity, R.string.upload_folder_empty, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                var ok = 0
                var fail = 0
                for (item in files) {
                    try {
                        uploadOne(agent.id, item.uri, item.mime)
                        ok += 1
                    } catch (_: Exception) {
                        fail += 1
                    }
                }
                renderAttachmentStrip()
                if (fail > 0) {
                    Toast.makeText(
                        this@ChatActivity,
                        getString(R.string.upload_folder_partial, ok, fail),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ChatActivity,
                    e.message ?: getString(R.string.upload_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private suspend fun uploadOne(agentId: String, uri: Uri, fallbackMime: String) {
        val tmp =
            withContext(Dispatchers.IO) {
                copyUriToCache(uri, fallbackMime)
            }
        try {
            val uploaded =
                withContext(Dispatchers.IO) {
                    ComposerApi(app.sessionStore).uploadFile(
                        agentId,
                        tmp.file,
                        tmp.filename,
                        tmp.mime,
                    )
                }
            composerState.attachments.add(uploaded)
        } finally {
            tmp.file.delete()
        }
    }

    private data class CachedUpload(val file: File, val filename: String, val mime: String)

    private data class FolderEntry(val uri: Uri, val mime: String)

    private fun collectFolderFiles(treeUri: Uri): List<FolderEntry> {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return emptyList()
        val out = mutableListOf<FolderEntry>()
        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (out.size >= MAX_FOLDER_UPLOAD_FILES) return
                val name = child.name.orEmpty()
                if (name.startsWith(".")) continue
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile && child.length() > 0L) {
                    out +=
                        FolderEntry(
                            uri = child.uri,
                            mime = child.type ?: "application/octet-stream",
                        )
                }
            }
        }
        walk(root)
        return out
    }

    private fun copyUriToCache(uri: Uri, fallbackMime: String): CachedUpload {
        val resolver = contentResolver
        var filename = "upload.bin"
        resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                filename = c.getString(idx) ?: filename
            }
        }
        val mime = resolver.getType(uri) ?: fallbackMime
        val out = File(cacheDir, "upload-${System.currentTimeMillis()}-$filename")
        resolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("cannot read file")
        return CachedUpload(out, filename, mime)
    }

    private fun polishComposer() {
        val text = binding.composer.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.polish_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val agent = selectedAgent ?: return
        if (polishing) return
        polishing = true
        binding.polishButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val polished =
                    withContext(Dispatchers.IO) {
                        ComposerApi(app.sessionStore).polishPrompt(agent.id, text)
                    }
                if (polished.isNotBlank()) {
                    binding.composer.setText(polished)
                    binding.composer.setSelection(polished.length)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ChatActivity,
                    e.message ?: getString(R.string.polish_failed),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                polishing = false
                binding.polishButton.isEnabled = true
            }
        }
    }

    private fun setupDrawerHeader() {
        val name = app.sessionStore.username.orEmpty().ifBlank { getString(R.string.default_user) }
        drawerBinding.drawerName.text = name
        drawerBinding.drawerAvatar.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
        drawerBinding.tabExperts.setOnClickListener { setNavScope(teams = false) }
        drawerBinding.tabTeams.setOnClickListener { setNavScope(teams = true) }
        drawerBinding.logout.setOnClickListener {
            app.sessionStore.clearAuth()
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
        }
        renderNavScopeTabs()
    }

    private fun setupLists() {
        messageAdapter =
            MessageAdapter(messages, app.sessionStore, lifecycleScope) { msg ->
                resolveSpeaker(msg)
            }
        // Start from the top so the first user turn is not pinned to the bottom.
        binding.messageList.layoutManager = LinearLayoutManager(this)
        binding.messageList.adapter = messageAdapter

        agentAdapter =
            DrawerAgentAdapter(visibleAgents) { idx ->
                selectAgent(visibleAgents[idx], openLatest = true)
                binding.drawerLayout.closeDrawer(Gravity.START)
            }
        threadAdapter =
            DrawerThreadAdapter(threads) { idx ->
                openThread(threads[idx].threadId)
                binding.drawerLayout.closeDrawer(Gravity.START)
            }
        drawerBinding.agentList.layoutManager = LinearLayoutManager(this)
        drawerBinding.threadList.layoutManager = LinearLayoutManager(this)
        drawerBinding.agentList.adapter = agentAdapter
        drawerBinding.threadList.adapter = threadAdapter
    }

    private fun setupNav() {
        binding.menuButton.setOnClickListener {
            if (binding.drawerLayout.isDrawerOpen(Gravity.START)) {
                binding.drawerLayout.closeDrawer(Gravity.START)
            } else {
                binding.drawerLayout.openDrawer(Gravity.START)
            }
        }
        binding.newChat.setOnClickListener { createNewThread() }
        binding.drawerLayout.setScrimColor(Color.parseColor("#730F172A"))
        binding.drawerLayout.addDrawerListener(
            object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) {
                    refreshDrawerLists()
                }
            },
        )
    }

    private fun setNavScope(teams: Boolean, syncSelection: Boolean = true) {
        if (navScopeTeams == teams && syncSelection) {
            renderNavScopeTabs()
            return
        }
        navScopeTeams = teams
        renderNavScopeTabs()
        rebuildVisibleAgents()
        if (!syncSelection) return
        val currentVisible = selectedAgent?.takeIf { matchesNavScope(it) }
        when {
            currentVisible != null -> {
                agentAdapter.selectedId = currentVisible.id
                agentAdapter.notifyDataSetChanged()
                loadThreads()
            }
            visibleAgents.isNotEmpty() -> selectAgent(visibleAgents.first(), openLatest = true)
            else -> {
                selectedAgent = null
                agentId = ""
                threads.clear()
                threadAdapter.notifyDataSetChanged()
                showEmptyConversation()
                refreshChrome()
                Toast.makeText(
                    this,
                    if (navScopeTeams) R.string.no_teams else R.string.no_experts,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun matchesNavScope(agent: AgentSummary): Boolean =
        if (navScopeTeams) agent.isTeam else !agent.isTeam

    private fun rebuildVisibleAgents() {
        visibleAgents.clear()
        visibleAgents.addAll(agents.filter(::matchesNavScope))
        agentAdapter.selectedId = selectedAgent?.id
        agentAdapter.notifyDataSetChanged()
        drawerBinding.agentsLabel.setText(
            if (navScopeTeams) R.string.nav_teams else R.string.nav_experts,
        )
    }

    private fun renderNavScopeTabs() {
        val activeBg = R.drawable.bg_nav_scope_tab_active
        val activeColor = ContextCompat.getColor(this, R.color.brand_active)
        val idleColor = ContextCompat.getColor(this, R.color.n7)
        if (navScopeTeams) {
            drawerBinding.tabExperts.setBackgroundResource(android.R.color.transparent)
            drawerBinding.tabExperts.setTextColor(idleColor)
            drawerBinding.tabTeams.setBackgroundResource(activeBg)
            drawerBinding.tabTeams.setTextColor(activeColor)
        } else {
            drawerBinding.tabExperts.setBackgroundResource(activeBg)
            drawerBinding.tabExperts.setTextColor(activeColor)
            drawerBinding.tabTeams.setBackgroundResource(android.R.color.transparent)
            drawerBinding.tabTeams.setTextColor(idleColor)
        }
    }

    private fun refreshDrawerLists() {
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { OctopApi(app.sessionStore).listAgents() }
                agents.clear()
                agents.addAll(list)
                rebuildVisibleAgents()
                loadThreadsInternal()
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bootstrap(deepAgent: String?, deepThread: String?) {
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { OctopApi(app.sessionStore).listAgents() }
                agents.clear()
                agents.addAll(list)
                if (list.isEmpty()) {
                    rebuildVisibleAgents()
                    Toast.makeText(this@ChatActivity, R.string.no_agents, Toast.LENGTH_LONG).show()
                    refreshChrome()
                    return@launch
                }
                val chosen =
                    list.firstOrNull { it.id == deepAgent }
                        ?: list.firstOrNull { it.id == app.sessionStore.selectedAgentId }
                        ?: list.first()
                navScopeTeams = chosen.isTeam
                renderNavScopeTabs()
                rebuildVisibleAgents()
                selectAgent(chosen, openThreadId = deepThread, openLatest = deepThread.isNullOrBlank())
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun selectAgent(
        agent: AgentSummary,
        openThreadId: String? = null,
        openLatest: Boolean = false,
    ) {
        if (agent.isTeam != navScopeTeams) {
            navScopeTeams = agent.isTeam
            renderNavScopeTabs()
        }
        selectedAgent = agent
        agentId = agent.id
        app.sessionStore.selectedAgentId = agent.id
        rebuildVisibleAgents()
        reconnectSocket()
        refreshChrome()
        lifecycleScope.launch {
            loadThreadsInternal()
            when {
                !openThreadId.isNullOrBlank() -> openThread(openThreadId)
                openLatest && threads.isNotEmpty() -> openThread(threads.first().threadId)
                else -> showEmptyConversation()
            }
        }
    }

    private fun loadThreads() {
        lifecycleScope.launch { loadThreadsInternal() }
    }

    private suspend fun loadThreadsInternal() {
        val agent = selectedAgent ?: return
        try {
            val list =
                withContext(Dispatchers.IO) {
                    OctopApi(app.sessionStore).listThreads(agent.id)
                }
            threads.clear()
            threads.addAll(list)
            threadAdapter.selectedId = threadId.ifBlank { null }
            threadAdapter.notifyDataSetChanged()
        } catch (e: Exception) {
            Toast.makeText(this@ChatActivity, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun createNewThread() {
        // Defer server create until the first message — empty shells stay out of History.
        showEmptyConversation()
        binding.drawerLayout.closeDrawer(Gravity.START)
    }

    private fun openThread(id: String) {
        threadId = id
        threadAdapter.selectedId = id
        threadAdapter.notifyDataSetChanged()
        turnOpen = false
        clearPendingStatus()
        messages.clear()
        messageAdapter.notifyDataSetChanged()
        updateConversationVisibility()
        if (id.isBlank()) {
            showEmptyConversation()
            return
        }
        loadHistory()
    }

    private fun showEmptyConversation() {
        threadId = ""
        threadAdapter.selectedId = null
        threadAdapter.notifyDataSetChanged()
        clearPendingStatus()
        messages.clear()
        messageAdapter.notifyDataSetChanged()
        updateConversationVisibility()
        loadWelcome()
    }

    private fun reconnectSocket() {
        socket?.close()
        if (agentId.isBlank()) return
        socket = ChatSocket(app.sessionStore, agentId, this).also { it.connect() }
    }

    private fun refreshChrome() {
        val name = selectedAgent?.name.orEmpty().ifBlank { getString(R.string.app_name) }
        binding.navTitle.text = name
        val hint = getString(R.string.message_hint, name)
        binding.composer.hint =
            SpannableString(hint).apply {
                setSpan(
                    AbsoluteSizeSpan(12, true),
                    0,
                    hint.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
    }

    private fun updateGreeting(welcomeMessage: String?) {
        val agent = selectedAgent
        val userName =
            app.sessionStore.username.orEmpty().trim().ifBlank { getString(R.string.app_name) }
        val agentName =
            agent?.name?.trim().orEmpty().ifBlank {
                if (agent?.isTeam == true) getString(R.string.nav_teams) else getString(R.string.app_name)
            }
        val brand = ContextCompat.getColor(this, R.color.brand_active)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello =
            when {
                hour < 11 -> getString(R.string.greet_morning)
                hour < 18 -> getString(R.string.greet_noon)
                else -> getString(R.string.greet_evening)
            }
        // Line 1: 晚上好，xxx
        val helloLine = getString(R.string.greet_hello, hello, userName)
        val helloSpan = SpannableString(helloLine)
        val userStart = helloLine.indexOf(userName)
        if (userStart >= 0) {
            helloSpan.setSpan(
                ForegroundColorSpan(brand),
                userStart,
                userStart + userName.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            helloSpan.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                userStart,
                userStart + userName.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        binding.greetTitle.text = helloSpan

        // Same line: 我是你的yyyy。专家标题语
        val welcome =
            welcomeMessage?.takeIf { it.isNotBlank() } ?: getString(R.string.greet_sub)
        val iamLine = getString(R.string.greet_iam, agentName)
        val combined =
            if (iamLine.endsWith("。") || welcome.startsWith("。")) {
                "$iamLine$welcome"
            } else {
                "$iamLine。$welcome"
            }
        val subSpan = SpannableString(combined)
        val agentStart = combined.indexOf(agentName)
        if (agentStart >= 0) {
            subSpan.setSpan(
                ForegroundColorSpan(brand),
                agentStart,
                agentStart + agentName.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            subSpan.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                agentStart,
                agentStart + agentName.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        binding.greetIam.visibility = View.GONE
        binding.greetSub.text = subSpan
    }

    private fun loadWelcome() {
        val agent = selectedAgent
        if (agent == null) {
            binding.quickStartTitle.visibility = View.GONE
            binding.quickCards.removeAllViews()
            updateGreeting(null)
            return
        }
        lifecycleScope.launch {
            try {
                val welcome =
                    withContext(Dispatchers.IO) {
                        OctopApi(app.sessionStore).getChatWelcome(agent.id)
                    }
                if (selectedAgent?.id != agent.id || threadId.isNotBlank()) return@launch
                updateGreeting(welcome.welcomeMessage)
                renderQuickCards(welcome.cards)
            } catch (_: Exception) {
                if (selectedAgent?.id != agent.id) return@launch
                updateGreeting(null)
                binding.quickStartTitle.visibility = View.GONE
                binding.quickCards.removeAllViews()
            }
        }
    }

    private fun renderQuickCards(cards: List<WelcomeCard>) {
        binding.quickCards.removeAllViews()
        if (cards.isEmpty()) {
            binding.quickStartTitle.visibility = View.GONE
            return
        }
        binding.quickStartTitle.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        val pastelColors =
            intArrayOf(
                R.color.quick_pastel_0,
                R.color.quick_pastel_1,
                R.color.quick_pastel_2,
                R.color.quick_pastel_3,
                R.color.quick_pastel_4,
                R.color.quick_pastel_5,
                R.color.quick_pastel_6,
                R.color.quick_pastel_7,
            )
        val radiusPx =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics)
        cards.forEachIndexed { index, card ->
            val view = inflater.inflate(R.layout.item_quick_card, binding.quickCards, false)
            val image = view.findViewById<ImageView>(R.id.cardIconImage)
            val pastel =
                parsePastelOrFallback(card.color, pastelColors[index % pastelColors.size])
            image.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setColor(pastel)
                }
            // Cards persist icon_name (+ color); render the matching vector (URL overrides when set).
            val icon =
                ContextCompat.getDrawable(this, QuickCardIcons.drawableRes(card.iconName))
                    ?.mutate()
            if (icon != null) {
                DrawableCompat.setTint(icon, ContextCompat.getColor(this, R.color.n9))
                image.setImageDrawable(icon)
            }
            image.visibility = View.VISIBLE
            if (!card.iconUrl.isNullOrBlank()) {
                val placeholder = TextView(this)
                AgentAvatarBinder.bind(
                    scope = lifecycleScope,
                    store = app.sessionStore,
                    letterView = placeholder,
                    imageView = image,
                    name = card.title,
                    iconUrl = card.iconUrl,
                )
            }
            view.findViewById<TextView>(R.id.cardTitle).text = card.title
            val expert = view.findViewById<TextView>(R.id.cardExpert)
            if (!card.expertName.isNullOrBlank()) {
                expert.visibility = View.VISIBLE
                expert.text = "@${card.expertName}"
            } else {
                expert.visibility = View.GONE
            }
            val desc = view.findViewById<TextView>(R.id.cardDesc)
            if (card.description.isBlank()) {
                desc.visibility = View.GONE
            } else {
                desc.visibility = View.VISIBLE
                desc.text = card.description
            }
            view.background =
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(ColorUtils.setAlphaComponent(pastel, 90), Color.WHITE),
                ).apply {
                    cornerRadius =
                        TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            14f,
                            resources.displayMetrics,
                        )
                    setStroke(
                        TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            1f,
                            resources.displayMetrics,
                        ).toInt(),
                        ColorUtils.blendARGB(
                            ColorUtils.setAlphaComponent(pastel, 180),
                            ContextCompat.getColor(this@ChatActivity, R.color.n4),
                            0.35f,
                        ),
                    )
                }
            view.setOnClickListener {
                val text =
                    if (!card.expertName.isNullOrBlank()) {
                        "@${card.expertName} ${card.prompt}".trim()
                    } else {
                        card.prompt
                    }
                binding.composer.setText(text)
                binding.composer.setSelection(text.length)
                sendMessage()
            }
            binding.quickCards.addView(view)
        }
    }

    private fun parsePastelOrFallback(raw: String?, fallbackRes: Int): Int {
        val hex = raw?.trim().orEmpty()
        if (hex.matches(Regex("^#[0-9a-fA-F]{6}$"))) {
            return Color.parseColor(hex)
        }
        return ContextCompat.getColor(this, fallbackRes)
    }

    private fun updateConversationVisibility() {
        val hasMessages = messages.isNotEmpty()
        binding.messageList.visibility = if (hasMessages) View.VISIBLE else View.GONE
        binding.emptyGreeting.visibility = if (hasMessages) View.GONE else View.VISIBLE
    }

    private fun loadHistory() {
        if (agentId.isBlank() || threadId.isBlank()) return
        lifecycleScope.launch {
            try {
                val history =
                    withContext(Dispatchers.IO) {
                        OctopApi(app.sessionStore).history(agentId, threadId)
                    }
                clearPendingStatus()
                messages.clear()
                messages.addAll(enrichHistory(history))
                messageAdapter.notifyDataSetChanged()
                updateConversationVisibility()
                if (messages.isNotEmpty()) {
                    binding.messageList.scrollToPosition(messages.lastIndex)
                }
                history.lastOrNull { !it.octopUiJson.isNullOrBlank() }?.octopUiJson?.let { feedOctopUi(it) }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendMessage() {
        val text = binding.composer.text?.toString()?.trim().orEmpty()
        val hasAttachments = composerState.attachments.isNotEmpty()
        if (text.isBlank() && !hasAttachments) return
        val agent = selectedAgent
        if (agent == null) {
            Toast.makeText(this, R.string.no_agents, Toast.LENGTH_SHORT).show()
            return
        }
        val attachmentsSnapshot = composerState.attachments.toList()
        val turnOptions = composerState.toTurnOptions(attachmentsSnapshot)
        val displayText =
            text.ifBlank {
                attachmentsSnapshot.joinToString(", ") { it.filename }.ifBlank { "[attachment]" }
            }
        binding.composer.setText("")
        composerState.attachments.clear()
        renderAttachmentStrip()
        lifecycleScope.launch {
            try {
                if (threadId.isBlank()) {
                    val created =
                        withContext(Dispatchers.IO) {
                            OctopApi(app.sessionStore).createThread(agent.id)
                        }
                    threadId = created.threadId
                    // First send starts the conversation — now it belongs in History.
                    threads.add(0, created.copy(hasMessages = true, title = displayText.take(40)))
                    threadAdapter.selectedId = threadId
                    threadAdapter.notifyDataSetChanged()
                }
                messages += ChatMessage(role = "user", text = displayText)
                messageAdapter.notifyItemInserted(messages.lastIndex)
                updateConversationVisibility()
                binding.messageList.scrollToPosition(messages.lastIndex)
                showGeneratingStatus()
                openAgenTurn()
                socket?.sendUserTurn(
                    threadId = threadId,
                    text = text,
                    options = turnOptions,
                )
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openAgenTurn() {
        if (!turnOpen) {
            a2ui.beginTurn("thread-${threadId.ifBlank { "new" }}")
            agenSession?.beginTextStream()
            turnOpen = true
        }
    }

    private fun feedChunks(chunks: List<String>) {
        chunks.forEach { agenSession?.receiveTextChunk(it) }
    }

    private fun feedOctopUi(json: String) {
        openAgenTurn()
        feedChunks(a2ui.onOctopUi(json))
    }

    override fun onFrame(json: JSONObject) {
        runOnUiThread {
            when (json.optString("type")) {
                "token" -> {
                    val text = json.optString("text").ifBlank { json.optString("content") }
                    appendAssistant(text)
                    openAgenTurn()
                    feedChunks(a2ui.onToken(text))
                }
                "tool_start", "tool_call_chunk" -> {
                    markGeneratingTool()
                }
                "tool_result" -> {
                    if (generatingActive) {
                        generatingOnTool = false
                        refreshGeneratingLabel()
                    }
                    val output = json.opt("output")
                    if (output is JSONObject && output.has("octop_ui")) {
                        markGeneratingAnswering()
                        feedOctopUi(output.toString())
                    }
                }
                "hitl_required" -> {
                    clearGeneratingStatus()
                    messages += ChatMessage(role = "system", text = getString(R.string.hitl_web))
                    messageAdapter.notifyItemInserted(messages.lastIndex)
                    updateConversationVisibility()
                }
                "done", "error", "turn.ended" -> {
                    clearGeneratingStatus()
                    if (json.optString("type") == "error") {
                        val msg = json.optString("message").ifBlank { "error" }
                        messages += ChatMessage(role = "system", text = msg)
                        messageAdapter.notifyItemInserted(messages.lastIndex)
                        updateConversationVisibility()
                    }
                    if (turnOpen) {
                        feedChunks(a2ui.endTurn())
                        agenSession?.endTextStream()
                        turnOpen = false
                    }
                }
            }
        }
    }

    private fun resolveSpeaker(msg: ChatMessage): Pair<String, String?> {
        val agent =
            msg.speakerAgentId?.let { id -> agents.firstOrNull { it.id == id } }
                ?: selectedAgent
        val name =
            msg.speakerName?.takeIf { it.isNotBlank() }
                ?: agent?.name?.takeIf { it.isNotBlank() }
                ?: getString(R.string.app_name)
        val icon = msg.speakerIconUrl ?: agent?.iconUrl
        return name to icon
    }

    private fun enrichHistory(raw: List<ChatMessage>): List<ChatMessage> =
        raw.map { msg ->
            if (!msg.role.equals("assistant", ignoreCase = true) &&
                !msg.role.equals("ai", ignoreCase = true)
            ) {
                msg
            } else {
                val (name, icon) = resolveSpeaker(msg)
                msg.copy(speakerName = name, speakerIconUrl = icon)
            }
        }

    private fun appendAssistant(text: String) {
        if (text.isEmpty()) return
        // Drop the in-list waiting row so the answer sits directly under the question.
        clearGeneratingStatus()
        val last = messages.lastOrNull()
        if (last != null && last.role == "assistant" && !last.pending) {
            messages[messages.lastIndex] = last.copy(text = last.text + text)
            messageAdapter.notifyItemChanged(messages.lastIndex)
        } else {
            val agent = selectedAgent
            messages +=
                ChatMessage(
                    role = "assistant",
                    text = text,
                    speakerAgentId = agent?.id,
                    speakerName = agent?.name,
                    speakerIconUrl = agent?.iconUrl,
                )
            messageAdapter.notifyItemInserted(messages.lastIndex)
        }
        updateConversationVisibility()
        binding.messageList.scrollToPosition(messages.lastIndex)
    }

    override fun onClosed(reason: String) {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.socket_closed, reason), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onFailure(error: Throwable) {
        runOnUiThread {
            Toast.makeText(this, error.message ?: "socket failure", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_AGENT_ID = "agent_id"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_AGENT_NAME = "agent_name"
        private const val MAX_FOLDER_UPLOAD_FILES = 50
    }
}

private class MessageAdapter(
    private val items: List<ChatMessage>,
    private val store: SessionStore,
    private val scope: CoroutineScope,
    private val resolveSpeaker: (ChatMessage) -> Pair<String, String?>,
) : RecyclerView.Adapter<MessageAdapter.VH>() {
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val row: LinearLayout = view.findViewById(R.id.row)
        val avatarWrap: View = view.findViewById(R.id.avatarWrap)
        val avatarLetter: TextView = view.findViewById(R.id.avatarLetter)
        val avatarImage: ImageView = view.findViewById(R.id.avatarImage)
        val contentCol: LinearLayout = view.findViewById(R.id.contentCol)
        val bubble: TextView = view.findViewById(R.id.bubble)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        val ctx = holder.itemView.context
        AgentAvatarBinder.cancel(holder.avatarImage)
        holder.avatarWrap.setOnClickListener(null)
        when (msg.role) {
            "user" -> {
                holder.row.gravity = Gravity.END
                holder.avatarWrap.visibility = View.GONE
                holder.contentCol.gravity = Gravity.END
                holder.bubble.setBackgroundResource(R.drawable.bg_bubble_user)
                holder.bubble.setTextColor(ContextCompat.getColor(ctx, R.color.n1))
            }
            "system" -> {
                holder.row.gravity = Gravity.CENTER_HORIZONTAL
                holder.avatarWrap.visibility = View.GONE
                holder.contentCol.gravity = Gravity.CENTER_HORIZONTAL
                holder.bubble.setBackgroundResource(R.drawable.bg_bubble_system)
                holder.bubble.setTextColor(ContextCompat.getColor(ctx, R.color.n9))
            }
            else -> {
                holder.row.gravity = Gravity.START
                holder.avatarWrap.visibility = View.VISIBLE
                holder.contentCol.gravity = Gravity.START
                if (msg.pending) {
                    holder.bubble.setBackgroundResource(0)
                    holder.bubble.setPadding(0, 4, 0, 4)
                    holder.bubble.setTextColor(ContextCompat.getColor(ctx, R.color.n6))
                } else {
                    holder.bubble.setBackgroundResource(R.drawable.bg_bubble_assistant)
                    val padH =
                        TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            14f,
                            ctx.resources.displayMetrics,
                        ).toInt()
                    val padV =
                        TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            11f,
                            ctx.resources.displayMetrics,
                        ).toInt()
                    holder.bubble.setPadding(padH, padV, padH, padV)
                    holder.bubble.setTextColor(ContextCompat.getColor(ctx, R.color.n8))
                }
                val (name, iconUrl) = resolveSpeaker(msg)
                holder.avatarWrap.contentDescription = name
                holder.avatarWrap.setOnClickListener { showSpeakerNameTip(it, name) }
                AgentAvatarBinder.bind(
                    scope = scope,
                    store = store,
                    letterView = holder.avatarLetter,
                    imageView = holder.avatarImage,
                    name = name,
                    iconUrl = iconUrl,
                )
            }
        }
        holder.bubble.setTypeface(null, android.graphics.Typeface.NORMAL)
        holder.bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        holder.bubble.text = msg.text
    }

    override fun onViewRecycled(holder: VH) {
        AgentAvatarBinder.cancel(holder.avatarImage)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    private fun showSpeakerNameTip(anchor: View, name: String) {
        val label = name.trim()
        if (label.isEmpty()) return
        val ctx = anchor.context
        val padH =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, ctx.resources.displayMetrics)
                .toInt()
        val padV =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, ctx.resources.displayMetrics)
                .toInt()
        val tip =
            TextView(ctx).apply {
                text = label
                setTextColor(ContextCompat.getColor(ctx, R.color.n1))
                textSize = 12f
                setBackgroundResource(R.drawable.bg_name_tip)
                setPadding(padH, padV, padH, padV)
            }
        val popup =
            PopupWindow(tip, WRAP_CONTENT, WRAP_CONTENT, true).apply {
                elevation = 8f
                isOutsideTouchable = true
            }
        tip.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val xOff = (anchor.width - tip.measuredWidth) / 2
        val yOff = -anchor.height - tip.measuredHeight -
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, ctx.resources.displayMetrics)
                .toInt()
        popup.showAsDropDown(anchor, xOff, yOff)
        Handler(Looper.getMainLooper()).postDelayed({
            if (popup.isShowing) popup.dismiss()
        }, 1600)
    }
}

private class DrawerAgentAdapter(
    private val items: List<AgentSummary>,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<DrawerAgentAdapter.VH>() {
    var selectedId: String? = null

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.root)
        val avatar: TextView = view.findViewById(R.id.avatar)
        val title: TextView = view.findViewById(R.id.title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_drawer_agent, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val agent = items[position]
        val selected = agent.id == selectedId
        holder.root.setBackgroundResource(
            if (selected) R.drawable.bg_drawer_session_active else R.drawable.bg_drawer_session,
        )
        holder.avatar.text = agent.name.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
        holder.title.text = agent.name
        holder.title.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (selected) R.color.brand_active else R.color.n9,
            ),
        )
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = items.size
}

private class DrawerThreadAdapter(
    private val items: List<ThreadSummary>,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<DrawerThreadAdapter.VH>() {
    var selectedId: String? = null

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.root)
        val iconWrap: View = view.findViewById(R.id.iconWrap)
        val icon: ImageView = view.findViewById(R.id.icon)
        val title: TextView = view.findViewById(R.id.title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_drawer_session, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val thread = items[position]
        val selected = thread.threadId == selectedId
        val ctx = holder.itemView.context
        holder.root.setBackgroundResource(
            if (selected) R.drawable.bg_drawer_session_active else R.drawable.bg_drawer_session,
        )
        holder.iconWrap.setBackgroundResource(
            if (selected) R.drawable.bg_session_icon_active else R.drawable.bg_session_icon,
        )
        holder.title.text =
            thread.title.ifBlank { ctx.getString(R.string.new_thread) }
        holder.title.setTextColor(
            ContextCompat.getColor(ctx, if (selected) R.color.brand_active else R.color.n9),
        )
        holder.icon.setColorFilter(
            ContextCompat.getColor(ctx, if (selected) R.color.brand_active else R.color.brand),
        )
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = items.size
}
