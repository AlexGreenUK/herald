package dev.hermeskotlin.android.wear

import dev.hermeskotlin.android.notify.ChatNotifications
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

    fun start() {
        scope.launch {
            val posted = mutableSetOf<String>()
            relay.session.flatMapLatest { it?.state ?: flowOf(null) }.collect { state ->
                val open = state?.inputRequests.orEmpty()
                for (request in open) {
                    if (request.id !in posted && notifications.postRequest(state?.storedSessionId, state?.title, request)) posted += request.id
                }
                val ids = open.mapTo(HashSet()) { it.id }
                posted.filter { it !in ids }.forEach {
                    posted -= it
                    notifications.cancelRequest(it)
                }
            }
        }
    }
}
