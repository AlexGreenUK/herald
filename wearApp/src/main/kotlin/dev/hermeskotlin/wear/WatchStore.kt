package dev.hermeskotlin.wear

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The chat the watch talks in (null id: the next ask starts one). */
data class WatchChatChoice(val id: String?, val title: String?)

/** What the watch remembers between launches: its chat, whether replies are read aloud, and the last reply (for the tile). */
class WatchStore(context: Context) {

    private val prefs = context.getSharedPreferences("herald_watch", Context.MODE_PRIVATE)

    private val _chat = MutableStateFlow(WatchChatChoice(prefs.getString(CHAT_ID, null), prefs.getString(CHAT_TITLE, null)))
    val chat: StateFlow<WatchChatChoice> = _chat.asStateFlow()

    private val _speak = MutableStateFlow(prefs.getBoolean(SPEAK, true))
    val speak: StateFlow<Boolean> = _speak.asStateFlow()

    fun setChat(id: String?, title: String?) {
        val choice = WatchChatChoice(id, title?.takeIf { it.isNotBlank() })
        if (choice == _chat.value) return
        _chat.value = choice
        prefs.edit {
            putString(CHAT_ID, choice.id)
            putString(CHAT_TITLE, choice.title)
        }
    }

    fun setSpeak(on: Boolean) {
        _speak.value = on
        prefs.edit { putBoolean(SPEAK, on) }
    }

    var lastReply: String?
        get() = prefs.getString(LAST_REPLY, null)
        set(value) = prefs.edit { putString(LAST_REPLY, value?.take(MAX_KEPT)) }

    private companion object {
        const val CHAT_ID = "chat_id"
        const val CHAT_TITLE = "chat_title"
        const val SPEAK = "speak"
        const val LAST_REPLY = "last_reply"
        const val MAX_KEPT = 400
    }
}
