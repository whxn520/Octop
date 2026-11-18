package chat.octop.mobile.data

data class AgentSummary(
    val id: String,
    val name: String,
    val kind: String = "expert",
    val iconUrl: String? = null,
) {
    val isTeam: Boolean get() = kind.equals("team", ignoreCase = true)
}

data class ThreadSummary(
    val threadId: String,
    val title: String,
    val hasMessages: Boolean = true,
)

data class ChatMessage(
    val role: String,
    val text: String,
    val octopUiJson: String? = null,
    val speakerAgentId: String? = null,
    val speakerName: String? = null,
    val speakerIconUrl: String? = null,
    /** Local-only waiting bubble while the agent turn is in flight. */
    val pending: Boolean = false,
)

data class LoginResult(
    val accessToken: String? = null,
    val mfaToken: String? = null,
    val mfaRequired: Boolean = false,
)

data class CaptchaPublic(
    val provider: String,
    val siteKey: String? = null,
)

data class WelcomeCard(
    val title: String,
    val description: String,
    val prompt: String,
    val expertName: String? = null,
    val iconName: String? = null,
    val iconUrl: String? = null,
    val color: String? = null,
)

data class ChatWelcome(
    val welcomeMessage: String?,
    val cards: List<WelcomeCard>,
)
