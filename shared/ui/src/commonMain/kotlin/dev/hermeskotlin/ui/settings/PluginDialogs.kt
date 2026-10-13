package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import dev.hermeskotlin.core.push.PluginUpdate
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog

/** Where the plugin comes from, as the dialogs name it. */
private val PLUGIN_SOURCE = PushApi.PLUGIN_REPO.substringAfter("://")

/**
 * Asked before a feature that needs the herald-push plugin is turned on: Herald installs code on the user's
 * gateway, so it says so first. [what] is what the plugin does for this feature.
 */
@Composable
internal fun PluginInstallDialog(visible: Boolean, feature: String, what: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = "Install on the gateway?",
        message = "$feature needs the herald-push plugin on your gateway. If it isn't there yet, Herald installs " +
            "version ${PushApi.PLUGIN_VERSION} from $PLUGIN_SOURCE, at the commit this Herald was reviewed with. $what " +
            "Updates are only installed when you choose to, and you can remove the plugin on the gateway at any time.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Turn on", onClick = onConfirm, size = ButtonSize.Small)
        },
    )
}

/** A newer plugin is available: the row says from which version to which, and asks before installing it. */
@Composable
internal fun PluginUpdateRow(update: PluginUpdate, onUpdate: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    ActionRow(
        if (update.needed) "Update the gateway's plugin to ${update.available}" else "Plugin update: ${update.installed} → ${update.available}",
        Lucide.RefreshCw,
    ) { asking = true }
    Dialog(
        visible = asking,
        onDismissRequest = { asking = false },
        title = "Update the plugin?",
        message = "The gateway runs herald-push ${update.installed}. Herald installs ${update.available} from " +
            "$PLUGIN_SOURCE, at the commit this Herald was reviewed with, in its place." +
            if (update.needed) " This Herald needs it." else " What you have keeps working until you do.",
        actions = {
            Button("Not now", onClick = { asking = false }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                "Update",
                onClick = {
                    asking = false
                    onUpdate()
                },
                size = ButtonSize.Small,
            )
        },
    )
}
