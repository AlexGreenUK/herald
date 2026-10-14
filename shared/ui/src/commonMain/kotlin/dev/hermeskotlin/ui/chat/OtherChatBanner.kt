package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.ui.sessions.OtherChatEvent
import dev.hermeskotlin.ui.sessions.SessionsBadge
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.Clock

/**
 * The top bar's Sessions button, with a dot when another chat needs the user: the accent for a reply, the
 * warning colour when one waits on an approval or answer. A long press goes to the next chat needing the user.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionsButton(badge: SessionsBadge, onClick: () -> Unit, onNext: (() -> Unit)?) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(MinTouchTarget)
            .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onNext?.let { next ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        next()
                    }
                },
                onClickLabel = "Open sessions",
                onLongClickLabel = if (onNext != null) "Next chat needing you" else null,
                role = Role.Button,
                interactionSource = null,
                indication = rememberColoredIndication(Theme[colors][textColor]),
            )
            .semantics { contentDescription = badge.description },
        contentAlignment = Alignment.Center,
    ) {
        UnstyledIcon(Lucide.PanelLeft, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(19.dp))
        if (!badge.none) {
            val dot = if (badge.urgent) Theme[colors][warning] else Theme[colors][accent]
            // Ringed in the bar's background, so it reads over the icon's corner.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(start = 16.dp, bottom = 16.dp)
                    .size(10.dp)
                    .background(Theme[colors][background], CircleShape)
                    .padding(2.dp)
                    .background(dot, CircleShape),
            )
        }
    }
}

/**
 * The strip over the chat saying another chat replied or waits on the user, with Open, Next and ×. It goes after
 * [BANNER_MILLIS] (or as long as the accessibility settings ask; with a screen reader on, until it's closed), and
 * a sideways swipe takes it away. The view model holds the event, so a rotation doesn't show it twice or start
 * its time over.
 */
@Composable
internal fun OtherChatBanner(
    event: OtherChatEvent,
    onOpen: () -> Unit,
    onNext: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val accessibility = LocalAccessibilityManager.current
    // A preview is there to be read, so it stays a little longer.
    val base = if (event.preview != null) PREVIEW_BANNER_MILLIS else BANNER_MILLIS
    val timeout = remember(accessibility, base) {
        accessibility?.calculateRecommendedTimeoutMillis(base, containsIcons = true, containsText = true, containsControls = true)
            ?: base
    }
    LaunchedEffect(event.chatId, event.atMillis, timeout) {
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        val left = timeout - (Clock.System.now().toEpochMilliseconds() - event.atMillis)
        if (left > 0) delay(left)
        onDismiss()
    }
    var offset by remember(event.chatId, event.atMillis) { mutableFloatStateOf(0f) }
    // The swipe's handler outlives recompositions (the preview filling in), so it calls the latest dismiss.
    val dismiss by rememberUpdatedState(onDismiss)
    val dismissAt = with(LocalDensity.current) { 96.dp.toPx() }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .graphicsLayer {
                translationX = offset
                alpha = 1f - (abs(offset) / (dismissAt * 2)).coerceIn(0f, 0.8f)
            }
            .pointerInput(event.chatId, event.atMillis) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (abs(offset) > dismissAt) dismiss() else offset = 0f },
                    onDragCancel = { offset = 0f },
                ) { _, delta -> offset += delta }
            }
            .clip(shape)
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            // The whole strip opens the chat; Open stays for those who look for a button.
            .clickable(onClickLabel = "Open", role = Role.Button, onClick = onOpen)
            .padding(start = 12.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(
            if (event.waiting) Lucide.ShieldAlert else Lucide.MessageCircle,
            contentDescription = null,
            tint = if (event.waiting) Theme[colors][warning] else Theme[colors][accentText],
            modifier = Modifier.size(16.dp),
        )
        Column(
            // Announced once as it comes; a newer event replacing it is announced in turn.
            Modifier.weight(1f).padding(vertical = 6.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                event.message,
                style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.Medium),
                color = Theme[colors][textColor],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            event.preview?.let { preview ->
                Text(
                    preview,
                    style = Theme[typography][caption],
                    color = Theme[colors][textSecondary],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        BannerChip("Open", onClick = onOpen)
        if (onNext != null) BannerChip("Next", onClick = onNext)
        IconButton(Lucide.X, contentDescription = "Dismiss", onClick = onDismiss, tint = Theme[colors][textSecondary], iconSize = 16.dp)
    }
}

/** A small pill button, 30dp drawn with the full touch height around it, as the offline banner's. */
@Composable
private fun BannerChip(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = MinTouchTarget)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // One fixed height, the label centred in it: a pill whose height followed the text sat unevenly.
        Box(
            Modifier
                .height(30.dp)
                .clip(CircleShape)
                .background(Theme[colors][surface3], CircleShape)
                .indication(interaction, rememberColoredIndication(Theme[colors][textColor]))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = Theme[typography][caption].copy(fontWeight = FontWeight.Medium),
                color = Theme[colors][textColor],
                maxLines = 1,
            )
        }
    }
}

private const val BANNER_MILLIS = 5_000L

/** With a preview to read. */
private const val PREVIEW_BANNER_MILLIS = 7_000L
