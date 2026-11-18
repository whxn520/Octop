package chat.octop.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import chat.octop.mobile.R
import chat.octop.mobile.data.AgentSummary
import chat.octop.mobile.data.ComposerApi
import chat.octop.mobile.data.ConnectorOption
import chat.octop.mobile.data.KnowledgeOption
import chat.octop.mobile.data.ModelOption
import chat.octop.mobile.data.SessionStore
import chat.octop.mobile.data.SkillOption
import chat.octop.mobile.data.SubagentOption
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComposerPlusSheet(
    private val activity: ChatActivity,
    private val store: SessionStore,
    private val scope: CoroutineScope,
    private val state: ComposerSelectionState,
    private val agentsProvider: () -> List<AgentSummary>,
    private val agentIdProvider: () -> String,
    private val isTeamProvider: () -> Boolean,
    private val onInsertText: (String) -> Unit,
    private val onUploadFile: () -> Unit,
    private val onUploadFolder: () -> Unit,
    private val onChanged: () -> Unit,
) {
    private enum class Page {
        ROOT, MODE, MODEL, CONNECTOR, KNOWLEDGE, SKILL, EXPERT, SUBAGENT, UPLOAD
    }

    private var page = Page.ROOT
    private var dialog: BottomSheetDialog? = null
    private var adapter: RowAdapter? = null
    private var sheetTitle: TextView? = null
    private var backButton: ImageButton? = null

    fun show() {
        val d = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_composer_plus, null)
        d.setContentView(view)
        sheetTitle = view.findViewById(R.id.sheetTitle)
        backButton = view.findViewById(R.id.back)
        val list = view.findViewById<RecyclerView>(R.id.list)
        adapter = RowAdapter { onRowClick(it) }
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        backButton?.setOnClickListener { showPage(Page.ROOT) }
        dialog = d
        showPage(Page.ROOT)
        d.show()
    }

    private fun showPage(next: Page) {
        page = next
        backButton?.visibility = if (next == Page.ROOT) View.GONE else View.VISIBLE
        sheetTitle?.setText(
            when (next) {
                Page.ROOT -> R.string.composer_tools
                Page.MODE -> R.string.mode
                Page.MODEL -> R.string.model
                Page.CONNECTOR -> R.string.connectors
                Page.KNOWLEDGE -> R.string.knowledge
                Page.SKILL -> R.string.skills
                Page.EXPERT -> R.string.experts
                Page.SUBAGENT -> R.string.subagents
                Page.UPLOAD -> R.string.upload
            },
        )
        when (next) {
            Page.ROOT -> renderRoot()
            Page.MODE -> renderModes()
            Page.MODEL -> loadAndRenderModels()
            Page.CONNECTOR -> loadAndRenderConnectors()
            Page.KNOWLEDGE -> loadAndRenderKnowledge()
            Page.SKILL -> loadAndRenderSkills()
            Page.EXPERT -> renderExperts()
            Page.SUBAGENT -> loadAndRenderSubagents()
            Page.UPLOAD -> renderUpload()
        }
    }

    private fun renderRoot() {
        val craft = state.conversationMode == "craft"
        val rows = mutableListOf<Row>()
        rows += Row("mode", activity.getString(R.string.mode), modeLabel(state.conversationMode))
        rows +=
            Row(
                "model",
                activity.getString(R.string.model),
                state.modelRef?.substringAfterLast('/') ?: activity.getString(R.string.auto_model),
            )
        if (craft && !isTeamProvider()) {
            rows +=
                Row(
                    "connector",
                    activity.getString(R.string.connectors),
                    countMeta(state.selectedConnectors.size),
                )
            rows +=
                Row(
                    "knowledge",
                    activity.getString(R.string.knowledge),
                    countMeta(state.selectedKnowledgeIds.size),
                )
            rows +=
                Row(
                    "skill",
                    activity.getString(R.string.skills),
                    countMeta(state.selectedSkills.size),
                )
            rows += Row("expert", activity.getString(R.string.experts), null)
        }
        if (craft) {
            rows += Row("subagent", activity.getString(R.string.subagents), null)
        }
        rows += Row("upload", activity.getString(R.string.upload), null)
        adapter?.submit(rows)
    }

    private fun renderModes() {
        adapter?.submit(
            listOf("ask", "plan", "craft").map { mode ->
                Row(
                    id = "mode:$mode",
                    title = modeLabel(mode),
                    meta = null,
                    checked = state.conversationMode == mode,
                )
            },
        )
    }

    private fun renderUpload() {
        adapter?.submit(
            listOf(
                Row("do_upload_file", activity.getString(R.string.upload_file), null),
                Row("do_upload_folder", activity.getString(R.string.upload_folder), null),
            ),
        )
    }

    private fun renderExperts() {
        val rows =
            agentsProvider()
                .filter { !it.isTeam && it.id != agentIdProvider() }
                .map { Row("expert:${it.id}", it.name, null) }
        adapter?.submit(rows)
    }

    private fun loadAndRenderModels() {
        val agentId = agentIdProvider()
        scope.launch {
            val list =
                runCatching {
                    withContext(Dispatchers.IO) { ComposerApi(store).listModels(agentId) }
                }.getOrElse {
                    toast(it.message)
                    emptyList()
                }
            if (page != Page.MODEL) return@launch
            val rows = mutableListOf(
                Row("model:", activity.getString(R.string.auto_model), null, state.modelRef == null),
            )
            rows +=
                list.map { m ->
                    Row("model:${m.ref}", m.displayName, m.providerName, state.modelRef == m.ref)
                }
            adapter?.submit(rows)
        }
    }

    private fun loadAndRenderConnectors() {
        val agentId = agentIdProvider()
        scope.launch {
            val list =
                runCatching {
                    withContext(Dispatchers.IO) { ComposerApi(store).listConnectors(agentId) }
                }.getOrElse {
                    toast(it.message)
                    emptyList()
                }
            if (page != Page.CONNECTOR) return@launch
            adapter?.submit(
                list.map { c ->
                    Row(
                        "conn:${c.mcpServerName}",
                        c.label,
                        c.kind,
                        state.selectedConnectors.contains(c.mcpServerName),
                    )
                },
            )
        }
    }

    private fun loadAndRenderKnowledge() {
        scope.launch {
            val list =
                runCatching {
                    withContext(Dispatchers.IO) { ComposerApi(store).listKnowledgeBases() }
                }.getOrElse {
                    toast(it.message)
                    emptyList()
                }
            if (page != Page.KNOWLEDGE) return@launch
            adapter?.submit(
                list.map { k ->
                    Row(
                        "kb:${k.id}",
                        k.name,
                        null,
                        state.selectedKnowledgeIds.contains(k.id),
                    )
                },
            )
        }
    }

    private fun loadAndRenderSkills() {
        val agentId = agentIdProvider()
        scope.launch {
            val list =
                runCatching {
                    withContext(Dispatchers.IO) { ComposerApi(store).listSkills(agentId) }
                }.getOrElse {
                    toast(it.message)
                    emptyList()
                }
            if (page != Page.SKILL) return@launch
            adapter?.submit(
                list.filter { it.enabled }.map { s ->
                    Row(
                        "skill:${s.slug}",
                        s.name,
                        "/${s.slug}",
                        state.selectedSkills.contains(s.slug),
                    )
                },
            )
        }
    }

    private fun loadAndRenderSubagents() {
        val agentId = agentIdProvider()
        scope.launch {
            val list =
                runCatching {
                    withContext(Dispatchers.IO) { ComposerApi(store).listSubagents(agentId) }
                }.getOrElse {
                    toast(it.message)
                    emptyList()
                }
            if (page != Page.SUBAGENT) return@launch
            adapter?.submit(list.map { Row("sub:${it.slug}", it.name, null) })
        }
    }

    private fun onRowClick(row: Row) {
        when {
            row.id == "mode" -> showPage(Page.MODE)
            row.id == "model" -> showPage(Page.MODEL)
            row.id == "connector" -> showPage(Page.CONNECTOR)
            row.id == "knowledge" -> showPage(Page.KNOWLEDGE)
            row.id == "skill" -> showPage(Page.SKILL)
            row.id == "expert" -> showPage(Page.EXPERT)
            row.id == "subagent" -> showPage(Page.SUBAGENT)
            row.id == "upload" -> showPage(Page.UPLOAD)
            row.id == "do_upload_file" -> {
                dialog?.dismiss()
                onUploadFile()
            }
            row.id == "do_upload_folder" -> {
                dialog?.dismiss()
                onUploadFolder()
            }
            row.id.startsWith("mode:") -> {
                state.conversationMode = row.id.removePrefix("mode:")
                if (state.conversationMode != "craft") {
                    state.selectedConnectors.clear()
                    state.selectedSkills.clear()
                }
                onChanged()
                showPage(Page.ROOT)
            }
            row.id.startsWith("model:") -> {
                val ref = row.id.removePrefix("model:")
                state.modelRef = ref.ifBlank { null }
                onChanged()
                showPage(Page.ROOT)
            }
            row.id.startsWith("conn:") -> {
                toggle(state.selectedConnectors, row.id.removePrefix("conn:"))
                onChanged()
                showPage(Page.CONNECTOR)
            }
            row.id.startsWith("kb:") -> {
                toggle(state.selectedKnowledgeIds, row.id.removePrefix("kb:"))
                onChanged()
                showPage(Page.KNOWLEDGE)
            }
            row.id.startsWith("skill:") -> {
                val slug = row.id.removePrefix("skill:")
                toggle(state.selectedSkills, slug)
                onInsertText("/$slug ")
                onChanged()
                showPage(Page.SKILL)
            }
            row.id.startsWith("expert:") -> {
                val id = row.id.removePrefix("expert:")
                val name = agentsProvider().firstOrNull { it.id == id }?.name ?: return
                onInsertText("@$name ")
                dialog?.dismiss()
            }
            row.id.startsWith("sub:") -> {
                onInsertText("@${row.id.removePrefix("sub:")} ")
                dialog?.dismiss()
            }
        }
    }

    private fun toggle(set: MutableSet<String>, id: String) {
        if (!set.add(id)) set.remove(id)
    }

    private fun countMeta(n: Int): String? = if (n > 0) n.toString() else null

    private fun modeLabel(mode: String): String =
        when (mode) {
            "ask" -> activity.getString(R.string.mode_ask)
            "plan" -> activity.getString(R.string.mode_plan)
            else -> activity.getString(R.string.mode_craft)
        }

    private fun toast(msg: String?) {
        Toast.makeText(activity, msg ?: "error", Toast.LENGTH_SHORT).show()
    }

    private data class Row(
        val id: String,
        val title: String,
        val meta: String?,
        val checked: Boolean = false,
    )

    private class RowAdapter(
        private val onClick: (Row) -> Unit,
    ) : RecyclerView.Adapter<RowAdapter.VH>() {
        private val items = mutableListOf<Row>()

        fun submit(rows: List<Row>) {
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.title)
            val meta: TextView = view.findViewById(R.id.meta)
            val check: TextView = view.findViewById(R.id.check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_plus_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.title.text = row.title
            if (row.meta.isNullOrBlank()) {
                holder.meta.visibility = View.GONE
            } else {
                holder.meta.visibility = View.VISIBLE
                holder.meta.text = row.meta
            }
            holder.check.visibility = if (row.checked) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onClick(row) }
        }

        override fun getItemCount(): Int = items.size
    }
}

class ComposerSelectionState {
    var conversationMode: String = "craft"
    var modelRef: String? = null
    val selectedConnectors = linkedSetOf<String>()
    val selectedKnowledgeIds = linkedSetOf<String>()
    val selectedSkills = linkedSetOf<String>()
    val attachments = mutableListOf<chat.octop.mobile.data.ChatAttachment>()

    fun selectionCount(): Int =
        selectedConnectors.size + selectedKnowledgeIds.size + selectedSkills.size +
            (if (modelRef != null) 1 else 0) +
            (if (conversationMode != "craft") 1 else 0)

    fun toTurnOptions(
        attachments: List<chat.octop.mobile.data.ChatAttachment> = this.attachments.toList(),
    ): chat.octop.mobile.data.ComposerTurnOptions {
        val craft = conversationMode == "craft"
        return chat.octop.mobile.data.ComposerTurnOptions(
            attachments = attachments,
            // Always send arrays so opt-out / ask-plan empty lists are explicit.
            mcpServers = if (craft) selectedConnectors.toList() else emptyList(),
            knowledgeBaseIds = selectedKnowledgeIds.toList(),
            targetAgentIds = emptyList(),
            modelRef = modelRef,
            conversationMode = conversationMode,
        )
    }
}
