package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.BatteryMedium
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Wifi
import com.composables.icons.lucide.Wrench
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolUse
import dev.hermeskotlin.core.phone.PhoneToolsStatus
import dev.hermeskotlin.core.phone.PhoneToolsStatus.State
import dev.hermeskotlin.core.phone.enabledPhoneTools
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.sessions.SubpageHeader
import kotlin.time.Clock
import org.koin.compose.viewmodel.koinViewModel

/** Settings → Phone tools, over the settings page. */
@Composable
fun PhoneToolsScreen(onBack: () -> Unit, viewModel: PhoneToolsViewModel = koinViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val access = rememberPhoneToolAccess()
    PlatformBackHandler(enabled = true, onBack = onBack)
    PhoneToolsView(
        settings = settings,
        status = status,
        recent = recent,
        onBack = onBack,
        onAll = viewModel::setAll,
        onTool = { tool, on ->
            viewModel.setTool(tool, on)
            // Turning on a tool that can't work yet asks for what it needs straight away.
            if (on && access.missing(tool) != null) access.request(tool)
        },
        onSetUp = viewModel::setUp,
        missing = access::missing,
        onAllow = access::request,
    )
}

/** The page itself, stateless so previews can draw it. */
@Composable
internal fun PhoneToolsView(
    settings: AppSettings,
    status: PhoneToolsStatus,
    recent: List<PhoneToolUse>,
    onBack: () -> Unit,
    onAll: (Boolean) -> Unit,
    onTool: (PhoneTool, Boolean) -> Unit,
    onSetUp: () -> Unit,
    missing: (PhoneTool) -> String? = { null },
    onAllow: (PhoneTool) -> Unit = {},
    nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader("Phone tools", onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(
                    "Let the agent use this phone: read how it is, or do something on it. Every tool is off until you " +
                        "turn it on, and anything that changes the phone asks you first, in a notification. A tool you " +
                        "turn off is taken away from the agent at once.",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Section("Phone tools") {
                    SwitchRow(
                        title = "Let the agent use this phone",
                        detail = statusLine(settings, status),
                        checked = settings.phoneTools,
                        onCheckedChange = onAll,
                        icon = Lucide.Smartphone,
                    )
                    if (settings.phoneTools && status.state in setOf(State.NotInstalled, State.Failed)) {
                        Divider()
                        ActionRow("Set up on the gateway", Lucide.Wrench, onSetUp)
                    }
                }
                if (settings.phoneTools) {
                    Section("Tools") {
                        PhoneTool.entries.forEachIndexed { i, tool ->
                            if (i > 0) Divider()
                            val on = tool.id in settings.phoneToolsOn
                            // Only once it's on: a tool that's off needs nothing, and asking early would nag.
                            val needs = if (on) missing(tool) else null
                            SwitchRow(
                                title = tool.title,
                                detail = needs ?: tool.detail,
                                checked = on,
                                onCheckedChange = { onTool(tool, it) },
                                icon = tool.icon,
                            )
                            if (needs != null) {
                                Divider()
                                ActionRow("Allow on this phone", Lucide.ShieldCheck) { onAllow(tool) }
                            }
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    Section("Used lately") {
                        recent.forEachIndexed { i, use ->
                            if (i > 0) Divider()
                            UseRow(use, nowMillis)
                        }
                    }
                }
            }
        }
    }
}

/** What the switch for all of them does now, or where the gateway's side stands. */
internal fun statusLine(settings: AppSettings, status: PhoneToolsStatus): String {
    if (!settings.phoneTools) return "Off. The agent can't use this phone."
    val count = settings.enabledPhoneTools().size
    return when (status.state) {
        State.Off, State.On -> status.detail ?: when (count) {
            0 -> "On. Turn on below each tool the agent may use."
            1 -> "On. New chats can use 1 tool."
            else -> "On. New chats can use $count tools."
        }
        State.Offline -> "Waiting to connect to the gateway."
        State.Working -> status.detail ?: "Setting up with the gateway…"
        State.NotInstalled -> "Needs the herald-push plugin on the gateway."
        State.Failed -> status.detail ?: "The gateway refused."
    }
}

@Composable
private fun UseRow(use: PhoneToolUse, nowMillis: Long) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(use.tool.icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(use.tool.title, style = Theme[typography][body], color = Theme[colors][text])
            Text(
                use.error ?: relativeTime(use.atMillis / 1000.0, nowMillis).let { if (it == "now") "Just now" else "$it ago" },
                style = Theme[typography][bodySmall],
                color = if (use.error != null) Theme[colors][danger] else Theme[colors][textTertiary],
            )
        }
    }
}

/** A row that opens a page: [detail] under the title, a chevron at the end. */
@Composable
internal fun NavRow(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme[typography][body], color = Theme[colors][text])
            Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
        UnstyledIcon(Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
    }
}

internal val PhoneTool.icon: ImageVector
    get() = when (this) {
        PhoneTool.Battery -> Lucide.BatteryMedium
        PhoneTool.Network -> Lucide.Wifi
        PhoneTool.Sound -> Lucide.Volume2
        PhoneTool.Alarms -> Lucide.AlarmClock
    }
