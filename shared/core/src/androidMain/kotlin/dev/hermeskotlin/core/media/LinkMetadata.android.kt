package dev.hermeskotlin.core.media

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.html.entities.EntityConverter
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

internal actual fun linkMetadata(bytes: ByteArray, url: String): LinkMetadata? {
    val doc = Jsoup.parse(bytes.inputStream(), null, url)
    fun meta(vararg names: String): String = names.firstNotNullOfOrNull { name ->
        doc.selectFirst("meta[property=$name], meta[name=$name]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
    }.orEmpty()
    val title = meta("og:title", "twitter:title").ifBlank { doc.title() }.trim().take(200)
    if (title.isBlank()) return null
    val image = listOf("meta[property=og:image]", "meta[name=twitter:image]", "meta[itemprop=image]").firstNotNullOfOrNull { selector ->
        doc.selectFirst(selector)?.absUrl("content")?.takeIf { it.isNotBlank() }
    } ?: listOf("link[rel=apple-touch-icon]", "link[rel=icon]").firstNotNullOfOrNull { selector ->
        doc.selectFirst(selector)?.absUrl("href")?.takeIf { it.isNotBlank() }
    }
    return LinkMetadata(title, meta("og:description", "description", "twitter:description").take(400), image)
}

private val BARE_WEB_URL = Regex("""https?://[^\s<>\[\]`]+""")

actual fun replyWebLinks(text: String): List<String> {
    val source = text.replace("\r\n", "\n").replace('\r', '\n')
    val flavour = GFMFlavourDescriptor()
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
    val links = linkedSetOf<String>()
    HtmlGenerator(source, tree, flavour).generateHtml { node, tag, attributes ->
        if (tag == "a" && links.size < 3) {
            // The renderer's bare autolink token can stop before a query; keep the source URL whole.
            val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION || it.type == MarkdownElementTypes.AUTOLINK }
                ?.getTextInNode(source)?.toString()?.removeSurrounding("<", ">")
            val rendered = destination?.let { Parser.unescapeEntities(EntityConverter.replaceEntities(it, true, true), false) }
                ?: Jsoup.parse("<a ${attributes.joinToString(" ")}>").selectFirst("a")?.attr("href")
            val bare = node.type == GFMTokenTypes.GFM_AUTOLINK
            var href = if (bare) {
                BARE_WEB_URL.find(source, node.startOffset)?.takeIf { it.range.first == node.startOffset }?.value
                    ?.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'') ?: rendered
            } else rendered
            while (bare && href != null && href.endsWith(')') && href.count { it == ')' } > href.count { it == '(' }) href = href.dropLast(1)
            if (href != null && webUrl(href) != null) links += href
        }
        attributes
    }
    return links.toList()
}
