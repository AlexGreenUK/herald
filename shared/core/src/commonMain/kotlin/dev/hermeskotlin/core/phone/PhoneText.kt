package dev.hermeskotlin.core.phone

/**
 * A web link the Open tool may open: [url] as it will be opened, and its [host] as the user is asked about it.
 * Only links read the same way by everything are taken (see [checkLink]), so the site in the question is the site
 * the browser opens.
 */
data class CheckedLink(val url: String, val host: String, val secure: Boolean) {
    /** The host for the question: whole, or its end when long, since the site's own name is at the end. */
    val site: String
        get() = if (host.length <= SITE_LENGTH) host else "…" + host.takeLast(SITE_LENGTH)

    private companion object {
        const val SITE_LENGTH = 40
    }
}

/**
 * [value] as a link the user can trust the question about, or a [PhoneToolException] saying why not. Taken: http and
 * https, a host of plain letters, digits, dots and dashes (a name in another alphabet as its xn-- form, which every
 * browser reads the same; letters that only look Latin can't hide then) or a bracketed IPv6 address, an optional port,
 * and nothing hidden anywhere. Refused: a name before an @, which makes a link look like another site; percent signs
 * and backslashes in the host part, which browsers and Android read differently.
 */
fun checkLink(value: String): CheckedLink {
    if (hidden(value, newlines = false) || value.any { it == '\\' || it == ' ' }) {
        throw PhoneToolException("The link has spaces or hidden characters in it.")
    }
    val match = LINK.matchEntire(value) ?: throw PhoneToolException("Only http and https links open: give the full link.")
    val (scheme, authority, rest) = match.destructured
    if ('@' in authority) throw PhoneToolException("Links with a name before an @ don't open.")
    val parts = AUTHORITY.matchEntire(authority.lowercase())
        ?: throw PhoneToolException("The link's site must be written in plain letters (xn-- for other alphabets).")
    val host = parts.groupValues[1]
    val lower = scheme.lowercase()
    return CheckedLink(url = "$lower://${authority.lowercase()}$rest", host = host.removePrefix("www."), secure = lower == "https")
}

/**
 * Whether [text] has characters a reader can't see or that change how it reads: controls, direction marks, zero-width
 * characters and spaces other than the plain one. Line breaks are taken when [newlines] (in text to share).
 */
fun hidden(text: String, newlines: Boolean): Boolean = text.any { c ->
    when {
        c == ' ' -> false
        c == '\n' && newlines -> false
        c.isISOControl() -> true
        c.isWhitespace() -> true
        c in NOT_PLAIN_SPACES -> true
        c.category == CharCategory.FORMAT -> true
        c.category == CharCategory.LINE_SEPARATOR || c.category == CharCategory.PARAGRAPH_SEPARATOR -> true
        c.category == CharCategory.SPACE_SEPARATOR -> true
        else -> false
    }
}

/** [text] for the question: line breaks shown as ⏎, so a quote can't be closed to add words that look like Herald's. */
fun forQuestion(text: String): String = text.replace("\n", " ⏎ ")

private val LINK = Regex("""^([Hh][Tt][Tt][Pp][Ss]?)://([^/?#]*)([/?#].*)?$""")

/** host (letters, digits, dots, dashes; or [IPv6]) and an optional port. */
private val AUTHORITY = Regex(
    """^((?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\.?|\[[0-9a-f:.]+])(?::[0-9]{1,5})?$""",
)

private val NOT_PLAIN_SPACES = setOf(' ', ' ', ' ', '　', '᠎')
