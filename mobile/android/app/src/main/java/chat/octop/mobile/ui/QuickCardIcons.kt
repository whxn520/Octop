package chat.octop.mobile.ui

import androidx.annotation.DrawableRes
import chat.octop.mobile.R

/**
 * Maps welcome quick-prompt ``icon_name`` (same keys as dashboard lucide icons)
 * to local vector drawables. Cards already persist ``icon_name`` + ``color``.
 */
object QuickCardIcons {
    @DrawableRes
    fun drawableRes(iconName: String?): Int {
        return when (iconName?.trim()?.lowercase()) {
            "zap" -> R.drawable.ic_quick_zap
            "list-todo" -> R.drawable.ic_quick_list_todo
            "activity" -> R.drawable.ic_quick_activity
            "file-text" -> R.drawable.ic_quick_file_text
            "pen-tool" -> R.drawable.ic_quick_pen_tool
            "heart" -> R.drawable.ic_quick_heart
            "message-square", "mail" -> R.drawable.ic_quick_message
            "globe" -> R.drawable.ic_quick_globe
            "server", "cpu", "hard-drive", "terminal" -> R.drawable.ic_server
            "user", "users" -> R.drawable.ic_user
            "sparkles", "layers", null, "" -> R.drawable.ic_sparkle
            else -> R.drawable.ic_sparkle
        }
    }
}
