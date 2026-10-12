package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composeunstyled.AnchorAlignment
import com.composeunstyled.AnchorSide
import com.composeunstyled.DropdownMenuPanel
import com.composeunstyled.DropdownMenuPanelScope
import com.composeunstyled.MenuItem
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/**
 * A small menu that opens right under its [anchor] (or above it, when [above], for an anchor at the bottom of
 * the screen), lined up with the anchor's end edge (or its start edge, per [alignment]). Controlled: open with [expanded]; picking an item, tapping
 * outside or Back calls [onExpandedChange] with false.
 */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    above: Boolean = false,
    /** Which edge of the anchor the menu lines up with; [AnchorAlignment.Start] for an anchor at the left of the screen. */
    alignment: AnchorAlignment = AnchorAlignment.End,
    items: @Composable DropdownMenuPanelScope.() -> Unit,
    anchor: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    UnstyledDropdownMenu(
        expanded,
        onExpandedChange,
        modifier,
        if (above) AnchorSide.Top else AnchorSide.Bottom,
        alignment,
        4.dp,
        0.dp,
        {
            DropdownMenuPanel(
                Modifier
                    .widthIn(min = 200.dp, max = 280.dp)
                    // As wide as the widest row, so every row can fill it and take a tap anywhere across.
                    .width(IntrinsicSize.Max)
                    .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.20f))
                    .clip(shape)
                    .background(Theme[colors][surfaceElevated])
                    .border(1.dp, Theme[colors][stroke], shape)
                    .padding(vertical = 4.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f),
            ) { items() }
        },
        anchor,
    )
}

/** One row of a [DropdownMenu]: an icon and a label. The menu closes when it is picked. */
@Composable
fun DropdownMenuPanelScope.MenuAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    MenuItem(
        onClick = onClick,
        // The panel lays rows out at their own width; filling it makes the whole row the target, not just the words.
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        indication = rememberColoredIndication(Theme[colors][textColor]),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(18.dp))
            Text(text, style = Theme[typography][body], color = Theme[colors][textColor])
        }
    }
}

/**
 * One choice in a [DropdownMenu] that picks among options: an icon, the label, an optional [count] in mono, and
 * a tick on the [selected] one. The menu closes when it is picked.
 */
@Composable
fun DropdownMenuPanelScope.MenuChoice(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    count: Int? = null,
) {
    MenuItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = selected },
        indication = rememberColoredIndication(Theme[colors][textColor]),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(18.dp))
            Text(
                text,
                style = Theme[typography][body].copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = Theme[colors][textColor],
                singleLine = true,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count != null) {
                Text(
                    count.toString(),
                    style = Theme[typography][code].copy(fontSize = 12.sp),
                    color = Theme[colors][textTertiary],
                    singleLine = true,
                )
            }
            // Always takes the room, so counts line up whether or not a row is the picked one.
            Box(Modifier.size(18.dp)) {
                if (selected) UnstyledIcon(Lucide.Check, contentDescription = "Selected", tint = Theme[colors][textColor], modifier = Modifier.size(18.dp))
            }
        }
    }
}
