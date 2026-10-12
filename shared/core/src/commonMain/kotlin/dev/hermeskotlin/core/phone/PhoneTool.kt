package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.settings.AppSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * An agent tool that runs on this phone. [id] is the tool's name on the gateway (the herald-push plugin's
 * fixed list); [title] and [detail] are what Settings → Phone tools shows by its switch.
 */
enum class PhoneTool(val id: String, val title: String, val detail: String) {
    Battery("phone_battery", "Battery", "The level, whether it's charging, and the time until full."),
    Network("phone_network", "Network", "Wi-Fi, mobile data or offline, whether it's metered, and a VPN."),
    Sound("phone_sound", "Sound", "Do Not Disturb, the ringer and volumes. A change asks you first."),
    ;

    companion object {
        fun byId(id: String): PhoneTool? = entries.firstOrNull { it.id == id }
    }
}

/** The tools the agent may use now: the ones switched on, while the Phone tools switch itself is on. */
fun AppSettings.enabledPhoneTools(): Set<PhoneTool> =
    if (!phoneTools) emptySet() else phoneToolsOn.mapNotNull(PhoneTool::byId).toSet()

/** Why a tool couldn't do what the agent asked, in words the agent passes on to the user. */
class PhoneToolException(message: String) : Exception(message)

/** Does what the agent asked of the phone. The platform side: Android reads the battery, sets the alarm, … */
interface PhoneToolRunner {
    /**
     * Runs [tool] with the agent's [args] for the chat [session] (empty when the gateway didn't say) and returns
     * the result the agent reads. Throws [PhoneToolException] for anything the agent should be told.
     */
    suspend fun run(tool: PhoneTool, args: JsonObject, session: String): JsonElement
}

/** One time the agent used a phone tool: shown on the Phone tools page, so nothing happens unseen. */
data class PhoneToolUse(val tool: PhoneTool, val atMillis: Long, val error: String?)

/** Where phone tools stand with the gateway, as a line under the Phone tools switch. */
data class PhoneToolsStatus(val state: State = State.Off, val detail: String? = null) {
    enum class State { Off, Offline, Working, NotInstalled, On, Failed }
}
