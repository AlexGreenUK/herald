package dev.hermeskotlin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolUse
import dev.hermeskotlin.core.phone.PhoneToolsBridge
import dev.hermeskotlin.core.phone.PhoneToolsStatus
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

/** Settings → Phone tools: the switch for all of them, one per tool, and what the agent used lately. */
class PhoneToolsViewModel(
    private val store: SettingsStore,
    private val bridge: PhoneToolsBridge,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        store.settings.filterNotNull().stateIn(viewModelScope, SharingStarted.Eagerly, store.settings.value ?: AppSettings())
    val status: StateFlow<PhoneToolsStatus> = bridge.status
    val recent: StateFlow<List<PhoneToolUse>> = bridge.recent

    /** Turning it on sets the gateway up too (installs the plugin there when it's missing). */
    fun setAll(on: Boolean) {
        store.update { it.copy(phoneTools = on) }
        if (on) bridge.setUp()
    }

    fun setTool(tool: PhoneTool, on: Boolean) = store.update { s ->
        s.copy(phoneToolsOn = if (on) (s.phoneToolsOn + tool.id).distinct() else s.phoneToolsOn - tool.id)
    }

    fun setUp() = bridge.setUp()
}
