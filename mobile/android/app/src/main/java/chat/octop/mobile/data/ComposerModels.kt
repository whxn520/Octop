package chat.octop.mobile.data

data class ChatAttachment(
    val url: String,
    val workspacePath: String,
    val filename: String,
    val mediaType: String,
) {
    val isImage: Boolean
        get() = mediaType.startsWith("image/")
}

data class ConnectorOption(
    val mcpServerName: String,
    val label: String,
    val kind: String,
    val defaultOpen: Boolean = false,
)

data class KnowledgeOption(
    val id: String,
    val name: String,
)

data class SkillOption(
    val slug: String,
    val name: String,
    val enabled: Boolean = true,
)

data class ModelOption(
    val providerName: String,
    val model: String,
    val displayName: String,
    val reasoning: Boolean = false,
) {
    val ref: String get() = "$providerName/$model"
}

data class SubagentOption(
    val slug: String,
    val name: String,
)

data class ComposerTurnOptions(
    val attachments: List<ChatAttachment> = emptyList(),
    val mcpServers: List<String>? = null,
    val knowledgeBaseIds: List<String>? = null,
    val targetAgentIds: List<String> = emptyList(),
    val modelRef: String? = null,
    val reasoningMode: String? = null,
    val reasoningEffort: String? = null,
    val conversationMode: String? = null,
)
