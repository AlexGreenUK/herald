package dev.hermeskotlin.android.wear

import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.wear.WatchRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Approvals and questions in the watch's chat, as the phone's usual notifications: the watch says to answer on
 * the phone, where an approval asks for an unlock first. Posted whether or not Herald is in sight, since the
 * app never shows the watch's chat; answered through [dev.hermeskotlin.android.notify.NotificationActionReceiver].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearRequests(private val relay: WatchRelay, private val notifications: ChatNotifications) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val posted = mutableSetOf<String>()

    /** Posted quietly over a call, by request id, with its chat's id and title: the usual notification once it's over. */
    private val quiet = mutableMapOf<String, Triple<String?, String?, InputRequest>>()

    // Both on the main thread, so the two never interleave.
    fun start() {
        scope.launch {
            relay.session.flatMapLatest { it?.state ?: flowOf(null) }.collect { state ->
                val open = state?.inputRequests.orEmpty()
                for (request in open) {
                    if (request.id in posted) continue
                    // On a live call the voice asks it: it waits here without a sound, and off the watch, in case the
                    // call doesn't get it answered.
                    val onCall = relay.onCall
                    if (notifications.postRequest(state?.storedSessionId, state?.title, request, quiet = onCall)) {
                        posted += request.id
                        if (onCall) quiet[request.id] = Triple(state?.storedSessionId, state?.title, request)
                    }
                }
                val ids = open.mapTo(HashSet()) { it.id }
                posted.filter { it !in ids }.forEach {
                    posted -= it
                    quiet -= it
                    notifications.cancelRequest(it)
                }
            }
        }
        scope.launch {
            relay.busy.collect { busy ->
                if (busy || quiet.isEmpty()) return@collect
                // The call's over and they're still unanswered: they wait as any question does.
                val left = quiet.values.toList()
                quiet.clear()
                left.forEach { (chat, title, request) -> notifications.postRequest(chat, title, request) }
            }
        }
    }
}
