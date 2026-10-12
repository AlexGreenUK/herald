package dev.hermeskotlin.android.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `phone_contacts`: looks people up in the phone's contacts by name, number or email, and gives only their names,
 * numbers and email addresses: never notes, addresses, birthdays or photos, and never the whole address book.
 */
internal class ContactsTool(private val context: Context) {

    fun run(args: JsonObject): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw PhoneToolException("Herald isn't allowed to read the contacts. The user can allow it in Herald: Settings → Phone tools.")
        }
        val query = args.string("query")?.trim()?.takeIf { it.length >= MIN_QUERY }
            ?: throw PhoneToolException("query is needed: at least $MIN_QUERY characters of a name, number or email.")
        if (query.length > MAX_QUERY) throw PhoneToolException("query is at most $MAX_QUERY characters.")
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).also {
            if (it !in 1..MAX_LIMIT) throw PhoneToolException("limit is 1 to $MAX_LIMIT.")
        }
        val ids = matches(query, limit)
        val people = ids.associateWith { Person() }
        if (ids.isNotEmpty()) details(people)
        return buildJsonObject {
            putJsonArray("contacts") {
                for (id in ids) {
                    val person = people.getValue(id)
                    addJsonObject {
                        put("name", person.name ?: "(no name)")
                        if (person.phones.isNotEmpty()) put("phones", JsonArray(person.phones.map(::JsonPrimitive)))
                        if (person.emails.isNotEmpty()) put("emails", JsonArray(person.emails.map(::JsonPrimitive)))
                    }
                }
            }
            if (ids.size == limit) put("note", "There may be more; ask with a narrower query.")
        }
    }

    private class Person(var name: String? = null, val phones: MutableList<String> = mutableListOf(), val emails: MutableList<String> = mutableListOf())

    /** Contact ids whose name, number or email holds [query], favourites first. */
    private fun matches(query: String, limit: Int): List<Long> {
        val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        // Without a leading 0, so "0151 234…" finds "+49151234…" as stored in the normalized form.
        val digits = query.filter(Char::isDigit).trimStart('0')
        val data = ContactsContract.Data.CONTENT_URI
        // A number as typed, without its spaces, dashes, dots and brackets: not every number has a normalized form.
        val bare = listOf(" ", "-", ".", "(", ")").fold(ContactsContract.CommonDataKinds.Phone.NUMBER) { column, mark -> "REPLACE($column, '$mark', '')" }
        val where = buildString {
            append("${ContactsContract.Data.DISPLAY_NAME_PRIMARY} LIKE ? ESCAPE '\\'")
            append(" OR (${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ? ESCAPE '\\')")
            if (digits.length >= MIN_DIGITS) {
                append(" OR (${ContactsContract.Data.MIMETYPE} = ? AND ")
                append("(${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ? OR $bare LIKE ?))")
            }
        }
        val whereArgs = buildList {
            add(like)
            add(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            add(like)
            if (digits.length >= MIN_DIGITS) {
                add(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                add("%$digits%")
                add("%$digits%")
            }
        }.toTypedArray()
        val ids = LinkedHashSet<Long>()
        context.contentResolver.query(
            data,
            arrayOf(ContactsContract.Data.CONTACT_ID),
            where,
            whereArgs,
            "${ContactsContract.Data.STARRED} DESC, ${ContactsContract.Data.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { c ->
            while (c.moveToNext() && ids.size < limit) ids += c.getLong(0)
        }
        return ids.toList()
    }

    /** Names, numbers and emails of [people], and nothing else of theirs. */
    private fun details(people: Map<Long, Person>) {
        val marks = people.keys.joinToString(",") { "?" }
        val where = "${ContactsContract.Data.CONTACT_ID} IN ($marks) AND ${ContactsContract.Data.MIMETYPE} IN (?, ?)"
        val whereArgs = people.keys.map(Long::toString) +
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE + ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        val columns = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
        )
        context.contentResolver.query(ContactsContract.Data.CONTENT_URI, columns, where, whereArgs.toTypedArray(), null)?.use { c ->
            while (c.moveToNext()) {
                val person = people[c.getLong(0)] ?: continue
                if (person.name == null) person.name = c.getString(1)
                val value = c.getString(3)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val list = if (c.getString(2) == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) person.phones else person.emails
                // The same number often comes from two accounts.
                if (list.none { it.filterNot(Char::isWhitespace) == value.filterNot(Char::isWhitespace) }) list += value
            }
        }
    }

    private companion object {
        const val MIN_QUERY = 2
        const val MAX_QUERY = 100
        const val MIN_DIGITS = 3
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 20
    }
}
