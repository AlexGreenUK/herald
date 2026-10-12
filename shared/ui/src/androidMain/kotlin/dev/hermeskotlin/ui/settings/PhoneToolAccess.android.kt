package dev.hermeskotlin.ui.settings

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.core.content.edit
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
        tool == PhoneTool.Location && !granted(FINE) && !granted(COARSE) -> "Allow Herald to use this phone's location."
        tool == PhoneTool.Geofences && !granted(FINE) -> "Allow precise location: Android only watches places with it."
        tool == PhoneTool.Geofences && needsBackground() ->
            "Allow location all the time: places are watched while Herald is closed. Choose \"Allow all the time\"."
        tool == PhoneTool.Calendar && (!granted(READ_CALENDAR) || !granted(WRITE_CALENDAR)) -> "Allow Herald to use your calendar."
        else -> null
    }

    private fun needsBackground() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(BACKGROUND)

    override fun request(tool: PhoneTool) {
        when {
            tool in ASKS && !notificationsOn() -> requestNotifications()
            // Both, so the prompt offers precise or approximate: the user picks.
            tool == PhoneTool.Location && !granted(FINE) && !granted(COARSE) -> prompt(FINE, COARSE)
            // Precise first: Android only offers "all the time" once Herald has location while in use.
            tool == PhoneTool.Geofences && !granted(FINE) -> prompt(FINE, COARSE)
            tool == PhoneTool.Geofences && needsBackground() -> prompt(BACKGROUND)
            tool == PhoneTool.Calendar && (!granted(READ_CALENDAR) || !granted(WRITE_CALENDAR)) -> prompt(READ_CALENDAR, WRITE_CALENDAR)
            tool == PhoneTool.Sound && !policyAccess() -> open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            tool == PhoneTool.ScreenTime && !usageAccess() -> {
                // Herald's own switch where Android has a page for it (10 and later), else the list of apps.
                val list = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                if (!open(Intent(list).setData(Uri.fromParts("package", context.packageName, null)))) open(list)
            }
        }
    }

    override fun optional(tool: PhoneTool): String? = when {
        tool == PhoneTool.Location && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (granted(FINE) || granted(COARSE)) && !granted(BACKGROUND) -> "Also while Herald is closed (optional)"
        else -> null
    }

    override fun requestOptional(tool: PhoneTool) {
        // Android 11 and later answer this one with Herald's location page, where "Allow all the time" is a choice.
        if (tool == PhoneTool.Location && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) prompt(BACKGROUND)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The permission prompt, or Herald's page in Android's settings once the prompt was refused for good. */
    private fun prompt(vararg permissions: String) {
        val first = permissions.first()
        val activity = context as? Activity
        // Kept across restarts: a refused-for-good prompt shows nothing, so the first tap after one would do nothing.
        val store = context.getSharedPreferences(ASKED_STORE, Context.MODE_PRIVATE)
        val asked = store.getStringSet(ASKED, emptySet()).orEmpty()
        if (activity == null || first !in asked || ActivityCompat.shouldShowRequestPermissionRationale(activity, first)) {
            store.edit { putStringSet(ASKED, asked + first) }
            ask(arrayOf(*permissions))
        } else {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)))
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
        val ASKS = setOf(PhoneTool.Sound, PhoneTool.Alarms, PhoneTool.Open, PhoneTool.Clipboard, PhoneTool.Geofences, PhoneTool.Calendar)
        const val CHANNEL = "phone_tools"
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
        const val READ_CALENDAR = Manifest.permission.READ_CALENDAR
        const val WRITE_CALENDAR = Manifest.permission.WRITE_CALENDAR

        @SuppressLint("InlinedApi") // Only asked on Android 10 and later.
        const val BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION

        /** Permissions prompted for, to tell "never asked" from "refused for good". */
        const val ASKED_STORE = "phone_tool_access"
        const val ASKED = "asked"

        /** Whether the notification prompt was shown in this process, to tell "never asked" from "refused for good". */
        var askedBefore = false
    }
}
