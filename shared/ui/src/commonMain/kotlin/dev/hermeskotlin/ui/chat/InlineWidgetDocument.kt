package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile

/** Connection Allowlists, including WebRTC blocking, shipped in Chromium WebView 152. */
internal fun supportsWidgetIsolation(version: String?): Boolean =
    version?.substringBefore('.')?.toIntOrNull()?.let { it >= 152 } == true

/** Only an explicit workspace HTML preview may execute; ordinary file links remain files. */
internal fun widgetFile(item: ReplyMedia): GatewayFile? =
    if (item.preview) GatewayFile.fromLink(item.source)?.takeIf { it.extension in setOf("html", "htm") } else null

internal fun widgetProblem(file: FileText): String? = when {
    file.binary -> "This file isn't HTML text."
    file.truncated || file.byteSize > 512 * 1024 || file.text.encodeToByteArray().size > 512 * 1024 -> "This widget is over the 512 KB preview limit."
    file.text.isBlank() -> "This widget is empty."
    else -> null
}

/** Resolved Herald appearance, using Desktop's public widget token names. */
internal data class WidgetTheme(
    val foreground: String,
    val muted: String,
    val accent: String,
    val border: String,
    val card: String,
    val dark: Boolean,
    val fontSize: Float,
)

/** A CSS `#rrggbbaa` color for [argb]: Herald's lines and muted text are translucent, and lose that as `#rrggbb`. */
internal fun cssColor(argb: Int): String {
    val value = argb.toLong() and 0xffffffff
    return "#" + (((value and 0xffffff) shl 8) or (value ushr 24)).toString(16).padStart(8, '0')
}

/**
 * The wrapper's title while a widget is shown: [WIDGET_EDGES] and then `t` and/or `b` for the ends the part under
 * the finger has scrolled to (both when it doesn't scroll). At an end the transcript takes the drag on.
 */
internal const val WIDGET_EDGES = "edges:"

/**
 * Reports the scroll ends of whatever the finger is on to the wrapper. No native bridge: the wrapper only turns a
 * well-formed report into its title. `touch-action:none` (a drawing canvas) keeps every drag.
 */
private const val EDGES_SCRIPT = "<script>(function(){var el=null;" +
    "function send(v){parent.postMessage({heraldEdges:v},'*')}" +
    "function edges(){var e=el||document.scrollingElement;if(!e)return;" +
    "send(e.scrollHeight-e.clientHeight<=1?'tb':(e.scrollTop<=0?'t':'')+(e.scrollTop+e.clientHeight>=e.scrollHeight-1?'b':''))}" +
    "function pick(n){for(;n&&n.nodeType===1&&n!==document.body&&n!==document.documentElement;n=n.parentElement){" +
    "var s=getComputedStyle(n);if(s.touchAction==='none')return false;" +
    "if(/auto|scroll/.test(s.overflowY)&&n.scrollHeight>n.clientHeight)return n}return null}" +
    "addEventListener('touchstart',function(e){var p=pick(e.target);if(p===false){el=null;send('')}else{el=p;edges()}},{capture:true,passive:true});" +
    "addEventListener('scroll',edges,{capture:true,passive:true});addEventListener('load',edges);addEventListener('resize',edges);" +
    "if(window.ResizeObserver)new ResizeObserver(function(){edges()}).observe(document.documentElement)})()</script>"

/** A separate, opaque-origin document: HTML cannot escape srcdoc into the app-owned wrapper. */
internal fun widgetDocument(html: String, theme: WidgetTheme, font: String): String {
    val prelude = "<meta http-equiv=\"Content-Security-Policy\" content=\"frame-src 'none'\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + EDGES_SCRIPT +
        "<style>@font-face{font-family:Herald;src:url(data:font/ttf;base64,$font)}" +
        ":root{color-scheme:${if (theme.dark) "dark" else "light"};--foreground:${theme.foreground};" +
        "--muted-foreground:${theme.muted};--accent:${theme.accent};--border:${theme.border};--card:${theme.card}}" +
        "html,body{margin:0;padding:0;background:transparent;color:var(--foreground);" +
        "font-family:Herald,sans-serif;font-size:${theme.fontSize}px}</style>"
    val content = ("<!doctype html>" + prelude + html).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
        "script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; " +
        "frame-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">" +
        "<style>html,body{margin:0;height:100%;background:transparent}iframe{width:100%;height:100%;border:0}</style>" +
        "<script>addEventListener('message',function(e){var f=document.querySelector('iframe'),v=e.data&&e.data.heraldEdges;" +
        "if(f&&e.source===f.contentWindow&&typeof v==='string'&&/^t?b?$/.test(v))document.title='$WIDGET_EDGES'+v})</script>" +
        "</head><body><iframe title=\"HTML widget\" sandbox=\"allow-scripts\" srcdoc=\"$content\"></iframe></body></html>"
}
