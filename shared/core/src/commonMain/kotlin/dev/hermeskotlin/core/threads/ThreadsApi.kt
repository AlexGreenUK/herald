package dev.hermeskotlin.core.threads

import dev.hermeskotlin.core.bots.parseBotRoster
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A thread's chat couldn't take the title asked for: another chat in the profile has it. */
class ThreadTitleTakenException(val title: String) : Exception("A chat named $title already exists.")

/**
 * The gateway calls behind threads, over the dashboard socket: the list kept in the profile's `ui_meta`
 * ([ThreadMeta]), and a new chat for a thread started fresh.
 */
class ThreadsApi(private val connection: GatewayConnection) {

    /** The threads of the profile [name] (the gateway's own name for it, `default` included). */
    suspend fun threads(name: String): List<ChatThread> = ThreadMeta.parse(stored(name).block)

    /**
     * Applies [edit] to the profile [name]'s threads and saves the result. The list is read fresh and written with
     * the revision it was read at, so an edit made meanwhile on another device isn't overwritten: a lost race
     * reads again and applies [edit] to that. Answers the list as saved.
     */
    suspend fun change(name: String, edit: (List<ChatThread>) -> List<ChatThread>): List<ChatThread> {
        repeat(META_ATTEMPTS) {
            val stored = stored(name)
            val before = ThreadMeta.parse(stored.block)
            val after = edit(before)
            if (after == before) return before
            val reply = client().request(
                "profiles.configure",
                buildJsonObject {
                    put("name", name)
                    put("ui_meta", buildJsonObject { put(ThreadMeta.KEY, ThreadMeta.write(after, stored.block)) })
                    stored.revision?.let { revision -> put("ui_meta_expected_revisions", buildJsonObject { put(ThreadMeta.KEY, revision) }) }
                },
            ) as? JsonObject
            val applied = reply?.get("applied") as? JsonObject
            if (applied?.get("ui_meta_conflicts") == null) {
                if ((applied?.get("ui_meta") as? JsonPrimitive)?.booleanOrNull == false) throw RpcException(0, "The gateway didn't save the threads.")
                return after
            }
        }
        throw RpcException(0, "The threads changed elsewhere at the same time. Try again.")
    }

    /**
     * A new chat titled [title] in [profile] (null: the gateway's launch profile), running in [cwd] when given,
     * made the way Desktop makes one and titled at once so its row exists before the first prompt. Answers its
     * stored id. A title another chat has is a [ThreadTitleTakenException], with nothing left behind.
     */
    suspend fun startChat(profile: String?, title: String, cwd: String?): String {
        val client = client()
        val created = client.request(
            "session.create",
            buildJsonObject {
                profile?.let { put("profile", it) }
                put("title", title)
                put("source", CLIENT_SOURCE)
                put("cols", TERMINAL_COLUMNS)
                cwd?.let { put("cwd", it) }
            },
        ) as? JsonObject ?: throw RpcException(0, "Empty session.create reply")
        val runtime = (created["session_id"] as? JsonPrimitive)?.contentOrNull
        val stored = (created["stored_session_id"] as? JsonPrimitive)?.contentOrNull
        if (runtime == null || stored == null) throw RpcException(0, "session.create returned no session")
        try {
            client.request(
                "session.title",
                buildJsonObject {
                    put("session_id", runtime)
                    put("title", title)
                },
            )
        } catch (e: RpcException) {
            discard(client, profile, runtime, stored)
            if (TITLE_TAKEN.containsMatchIn(e.message)) throw ThreadTitleTakenException(title)
            throw e
        }
        return stored
    }

    /** The `ui_meta` block and its revision as `profiles.list` has them for the profile [name]. */
    private suspend fun stored(name: String): Stored {
        val roster = parseBotRoster(client().request("profiles.list", buildJsonObject { put("include_sessions", false) }))
        val row = roster.bots.firstOrNull { it.name == name } ?: throw RpcException(0, "No profile named $name.")
        val revision = row.uiMetaRevisions?.let { revisions ->
            (revisions[ThreadMeta.KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull() ?: 0
        }
        return Stored(row.uiMeta?.get(ThreadMeta.KEY) as? JsonObject, revision)
    }

    private class Stored(val block: JsonObject?, val revision: Int?)

    private suspend fun discard(client: JsonRpcClient, profile: String?, runtime: String, stored: String) {
        runCatching { client.request("session.close", buildJsonObject { put("session_id", runtime) }) }
        runCatching {
            client.request(
                "session.delete",
                buildJsonObject {
                    put("session_id", stored)
                    profile?.let { put("profile", it) }
                },
            )
        }
    }

    private fun client(): JsonRpcClient = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    private companion object {
        /** Same surface hint as [dev.hermeskotlin.core.chat.ChatSession]: Markdown and pictures show here. */
        const val CLIENT_SOURCE = "desktop"
        const val TERMINAL_COLUMNS = 80
        val TITLE_TAKEN = Regex("already in use", RegexOption.IGNORE_CASE)
        const val META_ATTEMPTS = 4
    }
}
