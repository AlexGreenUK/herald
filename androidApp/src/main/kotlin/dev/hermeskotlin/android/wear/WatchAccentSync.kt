package dev.hermeskotlin.android.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.watchAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Tells the watches at once when the accent is changed on the phone: otherwise a watch learns it only with its
 * next status ask, up to half a minute later while its app is open. A watch that's out of reach gets it on that ask.
 */
class WatchAccentSync(private val context: Context, private val settings: SettingsStore, private val relay: WatchRelay) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            // The first value is the stored one, which the watch already gets when it asks.
            settings.settings.filterNotNull().map { it.accent }.distinctUntilChanged().drop(1).collectLatest { accent ->
                val nodes = try {
                    Wearable.getNodeClient(context).connectedNodes.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No Wear OS on this phone, or nothing paired.
                    return@collectLatest
                }
                nodes.forEach { relay.status(it.id, accent = watchAccent(accent)) }
            }
        }
    }
}
