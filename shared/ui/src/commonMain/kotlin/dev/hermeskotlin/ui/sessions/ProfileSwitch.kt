package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronsUpDown
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.profiles.ProfileRoster
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.rememberHoverIndication
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/** What the sidebar's profile switch shows: the profile whose chats are listed, by the name the picker gives it. */
data class ProfileSwitch(val label: String)

/** Said for the launch profile when the gateway didn't say which one it runs as (older gateways). */
internal const val UNKNOWN_LAUNCH_LABEL = "Gateway default"

/**
 * The sidebar's profile switch for [roster] with [picked] the chosen profile (null: the gateway's launch profile),
 * or null to leave it out. It shows where there is a profile to switch to: the gateway lists two or more, or
 * the chats are another profile's than the gateway's own (one since deleted, say), so there's a way back.
 * A gateway with one profile, or one that doesn't list them, shows nothing new. While the list is still on its
 * way it shows only for a picked profile, which needs no list to name.
 */
internal fun profileSwitch(roster: ProfileRoster?, picked: String?): ProfileSwitch? {
    if (roster == null) return picked?.let(::ProfileSwitch)
    val elsewhere = picked != null && picked != roster.launch
    if (roster.profiles.size < 2 && !elsewhere) return null
    return profileLabel(roster, picked)?.let(::ProfileSwitch)
}

/**
 * The name of the profile whose chats are listed, as the switch and the account sheet both say it: [picked], by
 * its display name when [roster] lists it, else the gateway's launch profile, which is named only when the gateway
 * said which it runs as. Null while there's no list and nothing picked.
 */
internal fun profileLabel(roster: ProfileRoster?, picked: String?): String? {
    if (roster == null) return picked
    val name = picked ?: roster.launch
    val profile = roster.profiles.find { it.name == name }
    return when {
        profile != null && (picked != null || roster.launchKnown) -> profile.label
        picked != null -> picked
        else -> UNKNOWN_LAUNCH_LABEL
    }
}

/** The first character of [label] as a profile's initial, upper-cased; an emoji or other pair is kept whole. */
internal fun profileInitial(label: String): String {
    val first = label.trimStart()
    if (first.isEmpty()) return ""
    val end = if (first.length > 1 && first[0].isHighSurrogate() && first[1].isLowSurrogate()) 2 else 1
    return first.substring(0, end).uppercase()
}

/** What a screen reader says for the switch; the tap itself is said as "switch profile". */
internal fun ProfileSwitch.spoken(): String = "Profile: $label"

/**
 * The active profile at the top of the Chats side, clearly labelled: its initial as the profile picker draws it,
 * "Profile", the profile's name (cut short when long) and a chevron. A tap opens the profile picker.
 */
@Composable
internal fun ProfileSwitchRow(switch: ProfileSwitch, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = CircleShape
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .handCursor()
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = SWITCH_PROFILE, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = switch.spoken()
                role = Role.Button
                onClick(label = SWITCH_PROFILE) { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clip(shape)
                .background(Theme[colors][surface2], shape)
                .border(1.dp, Theme[colors][stroke], shape)
                .indication(interaction, rememberHoverIndication(Theme[colors][text], shape))
                .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileInitial(switch.label, checked = true, size = 26.dp)
            Text(
                "Profile",
                style = Theme[typography][caption].copy(fontSize = 12.sp),
                color = Theme[colors][textTertiary],
                maxLines = 1,
            )
            Text(
                switch.label,
                style = Theme[typography][label].copy(fontWeight = FontWeight.Medium),
                color = Theme[colors][text],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            UnstyledIcon(Lucide.ChevronsUpDown, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
        }
    }
}

private const val SWITCH_PROFILE = "Switch profile"

/** A profile's initial on a disc, as the profile picker draws each profile: the accent fill for the active one. */
@Composable
internal fun ProfileInitial(label: String, checked: Boolean, size: Dp = 28.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Theme[colors][if (checked) accent else surface]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profileInitial(label),
            style = Theme[typography][caption].copy(fontWeight = FontWeight.SemiBold),
            color = Theme[colors][if (checked) onAccent else textSecondary],
        )
    }
}
