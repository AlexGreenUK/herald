package dev.hermeskotlin.android.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks before a phone tool changes anything: a notification says what the agent wants to do, with Allow and
 * Deny. Allow opens [PhoneActionActivity], which does it from the tap (Android lets an app start an alarm, a
 * link or the share sheet only from something the user did) and closes. Nothing happens unless allowed;
 * swiping the question away is Deny, and it goes when the agent stops waiting.
 */
class PhoneConfirmations(private val context: Context) {

    internal class Pending(val action: (Activity) -> Unit, val done: CompletableDeferred<Unit>)

    private val manager = NotificationManagerCompat.from(context)
    private val ids = AtomicInteger(0)

    private val system = context.getSystemService(NotificationManager::class.java)

    init {
        createChannel()
        // No question outlives the process that asked it: one still showing is from before a restart, and dead.
        system?.activeNotifications
            ?.filter { it.tag == TAG }
            ?.forEach { manager.cancel(TAG, it.id) }
    }

    /**
     * The channel questions go on. It gets through Do Not Disturb (priority only) once Herald may manage Do Not
     * Disturb, which the Sound tool needs anyway: else the agent could never ask to turn it off. Created again
     * before each question, since that access can come after the channel was first made.
     */
    private fun createChannel() {
        system?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone tools", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When the agent wants to change something on this phone"
                if (system.isNotificationPolicyAccessGranted) setBypassDnd(true)
            },
        )
    }

    /** Why a question can't reach the user now, or null when it can. */
    private fun cantAsk(): String? {
        val channel = system?.getNotificationChannel(CHANNEL)
        return when {
            !manager.areNotificationsEnabled() || channel?.importance == NotificationManager.IMPORTANCE_NONE ->
                "Herald's notifications for phone tools are off, so the phone couldn't ask the user."
            system?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                system?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS ->
                "Do Not Disturb hides every notification right now, so the phone couldn't ask the user."
            system?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY && channel?.canBypassDnd() != true ->
                "Do Not Disturb hides Herald's questions right now, so the phone couldn't ask the user."
            else -> null
        }
    }

    /**
     * Shows [what] (a sentence: "Set an alarm for 7:00") and waits. Runs [action] on the tap of Allow, from an
     * activity; throws [PhoneToolException] when the user denies, doesn't answer before the agent stops waiting,
     * or can't be asked.
     */
    suspend fun confirm(what: String, action: (Activity) -> Unit) {
        val key = "c" + ids.incrementAndGet()
        val pending = Pending(action, CompletableDeferred())
        waiting[key] = pending
        val id = key.hashCode()
        try {
            createChannel()
            cantAsk()?.let { throw PhoneToolException(it) }
            if (!post(id, key, what)) throw PhoneToolException("Android didn't show the question, so the phone couldn't ask the user.")
            // Well inside the agent's wait: an Allow it would never hear of must not change the phone.
            withTimeout(ANSWER_MS) { pending.done.await() }
        } catch (e: TimeoutCancellationException) {
            throw PhoneToolException("The user didn't answer on the phone in time.")
        } finally {
            waiting.remove(key)
            manager.cancel(TAG, id)
        }
    }

    @SuppressLint("MissingPermission") // cantAsk checks it.
    private fun post(id: Int, key: String, what: String): Boolean {
        val allow = PendingIntent.getActivity(
            context, id,
            Intent(context, PhoneActionActivity::class.java).putExtra(EXTRA_KEY, key).putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deny = PendingIntent.getBroadcast(
            context, id,
            Intent(context, PhoneDenyReceiver::class.java).putExtra(EXTRA_KEY, key).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle("The agent wants to change your phone")
            .setContentText(what)
            .setStyle(NotificationCompat.BigTextStyle().bigText(what))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDeleteIntent(deny)
            .setTimeoutAfter(ANSWER_MS)
            .addAction(0, "Deny", deny)
            .addAction(0, "Allow", allow)
            .build()
        return runCatching { manager.notify(TAG, id, notification) }.isSuccess
    }

    companion object {
        private const val CHANNEL = "phone_tools"
        private const val TAG = "phone_tool"
        internal const val EXTRA_KEY = "phone_tool_key"
        internal const val EXTRA_ID = "phone_tool_id"

        /** How long a question waits for the user: under the agent's 120 s for a tool that asks. */
        private const val ANSWER_MS = 110_000L

        /** Process-wide: the activity and receiver Android makes find the waiting question here. */
        internal val waiting = ConcurrentHashMap<String, Pending>()

        internal fun allow(intent: Intent, activity: Activity) {
            val pending = intent.getStringExtra(EXTRA_KEY)?.let(waiting::remove)
            if (pending == null) {
                // The agent stopped waiting, or the app restarted: nothing to do, but the question shouldn't stay.
                forget(activity, intent)
                return
            }
            try {
                pending.action(activity)
                pending.done.complete(Unit)
            } catch (e: Exception) {
                pending.done.completeExceptionally(PhoneToolException(e.message ?: "The phone couldn't do it."))
            }
        }

        internal fun deny(context: Context, intent: Intent) {
            val pending = intent.getStringExtra(EXTRA_KEY)?.let(waiting::remove)
            if (pending == null) forget(context, intent)
            pending?.done?.completeExceptionally(PhoneToolException("The user said no on the phone."))
        }

        private fun forget(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_ID, 0).takeIf { it != 0 } ?: return
            NotificationManagerCompat.from(context).cancel(TAG, id)
        }
    }
}

/** Opened by Allow: does the action from the user's tap, then closes. Draws nothing. */
class PhoneActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneConfirmations.allow(intent, this)
        finish()
    }
}

/** Deny, or the question swiped away. */
class PhoneDenyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PhoneConfirmations.deny(context, intent)
    }
}
