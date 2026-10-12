package dev.hermeskotlin.ui.settings

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.hermeskotlin.core.phone.PhoneTool

@Composable
actual fun rememberPhoneToolAccess(): PhoneToolAccess {
    val context = LocalContext.current
    // Bumped when the user may have changed something in Android's settings: back from there, or a prompt answered.
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }
    return remember(version) { AndroidPhoneToolAccess(context) { prompt.launch(it) } }
}

private class AndroidPhoneToolAccess(private val context: Context, private val ask: (Array<String>) -> Unit) : PhoneToolAccess {

    override fun missing(tool: PhoneTool): String? = when {
        tool in ASKS && !notificationsOn() -> "Turn on Herald's notifications: changes are asked there."
        tool == PhoneTool.Sound && !policyAccess() -> "Allow Herald to change Do Not Disturb."
        tool == PhoneTool.ScreenTime && !usageAccess() -> "Give Herald Usage access, to see which apps you use."
        else -> null
    }

    override fun request(tool: PhoneTool) {
        when {
            tool in ASKS && !notificationsOn() -> requestNotifications()
            tool == PhoneTool.Sound && !policyAccess() -> open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            tool == PhoneTool.ScreenTime && !usageAccess() -> {
                // Herald's own switch where Android has a page for it (10 and later), else the list of apps.
                val list = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                if (!open(Intent(list).setData(Uri.fromParts("package", context.packageName, null)))) open(list)
            }
        }
    }

    private fun notificationsOn() = NotificationManagerCompat.from(context).let { manager ->
        manager.areNotificationsEnabled() &&
            manager.getNotificationChannelCompat(CHANNEL)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    private fun requestNotifications() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val activity = context as? Activity
        val prompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED &&
            // Asked before and refused for good: only the settings page can turn them on now.
            (activity == null || !askedBefore || ActivityCompat.shouldShowRequestPermissionRationale(activity, permission))
        if (prompt) {
            askedBefore = true
            ask(arrayOf(permission))
        } else {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }

    private fun policyAccess() = context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    private fun usageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Whether Android had a page for [intent]. */
    private fun open(intent: Intent): Boolean {
        // In Herald's own task, so Back from the settings page comes back here and not into a Settings app left open.
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private companion object {
        /** Tools that ask before changing the phone, in a notification on this channel (PhoneConfirmations). */
        val ASKS = setOf(PhoneTool.Sound, PhoneTool.Alarms)
        const val CHANNEL = "phone_tools"

        /** Whether the notification prompt was shown in this process, to tell "never asked" from "refused for good". */
        var askedBefore = false
    }
}
