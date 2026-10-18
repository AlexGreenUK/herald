package dev.hermeskotlin.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.hermeskotlin.designsystem.R
import java.io.ByteArrayInputStream
import kotlin.io.encoding.Base64

/** Untrusted widget HTML gets no gateway origin, cookies, storage, native bridge or network. */
@Composable
internal actual fun WidgetWebView(html: String, theme: WidgetTheme, name: String, modifier: Modifier, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val failure = rememberUpdatedState(onFailure)
    val supported = remember { supportsWidgetIsolation(WebView.getCurrentWebViewPackage()?.versionName) }
    if (!supported) {
        LaunchedEffect(Unit) { failure.value("Update Android System WebView to run this widget.") }
        return
    }
    val font = remember { widgetFont(context) }
    val document = remember(html, theme, font) { widgetDocument(html, theme, font) }
    val currentDocument = rememberUpdatedState(document)
    val view = remember { createWidgetWebView(context, name, { currentDocument.value }) { failure.value("Couldn't display this widget.") } }
    DisposableEffect(view) {
        onDispose { view.stopLoading(); view.destroy() }
    }
    AndroidView(factory = { view }, modifier = modifier, update = {
        // By identity: the document is remembered, and comparing its text would read it all on each update.
        if (it.tag !== document) {
            it.tag = document
            it.loadUrl(WIDGET_URL)
        }
    })
}

private const val WIDGET_URL = "https://herald-widget.invalid/"

/** Geist as base64, which every widget's document embeds; read once, on the main thread like the widgets. */
private var cachedFont: String? = null

private fun widgetFont(context: Context): String = cachedFont
    ?: context.resources.openRawResource(R.font.geist_regular).use { Base64.encode(it.readBytes()) }.also { cachedFont = it }

/** The same restricted host for each widget; its document always arrives as app-supplied text. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
internal fun createWidgetWebView(context: Context, name: String, document: () -> String, onFailure: () -> Unit): WebView =
    WebView(context).apply {
        // A wrap-content WebView gives percentage-height frames a zero-height document.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = name
        // Keep iframe scrolling and controls out of the transcript's drag gesture, until what's under the finger has
        // scrolled to its end that way (the widget reports its ends as the title): then the transcript scrolls on.
        // Until the first report, as for content that doesn't scroll, both ends count as reached. WebView owns clicks.
        var edges = "tb"
        var lastY = 0f
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> lastY = event.y
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lastY
                    lastY = event.y
                    // A finger moving down scrolls content up, towards its top end.
                    val atEnd = if (dy > 0) 't' in edges else if (dy < 0) 'b' in edges else null
                    if (atEnd != null) view.parent?.requestDisallowInterceptTouchEvent(!atEnd)
                }
            }
            false
        }
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            // LocalDensity already includes Herald's and the system's text scales.
            textZoom = 100
            blockNetworkLoads = true
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (title != null && title.startsWith(WIDGET_EDGES)) edges = title.removePrefix(WIDGET_EDGES)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                if (request.isForMainFrame && request.url.toString() == WIDGET_URL) {
                    // CSP and blockNetworkLoads don't cover WebRTC. This policy is inherited by srcdoc.
                    WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Connection-Allowlist" to "()"),
                        ByteArrayInputStream(document().encodeToByteArray()))
                } else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.post { onFailure() }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                view.post { onFailure() }
                return true
            }
        }
    }
