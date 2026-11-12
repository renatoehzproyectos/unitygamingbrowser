package dev.gamingbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class GameHost(
    val onRenderGone: (didCrash: Boolean) -> Unit,
    val onMainFrameError: (String) -> Unit,
    val onCustomView: (View?, WebChromeClient.CustomViewCallback?) -> Unit
)

/** Capa de abstracción: aquí es donde se sustituiría WebView por un Chromium propio. */
object GameWebView {

    fun origin(url: String): String {
        val u = Uri.parse(url)
        val p = if (u.port > 0) ":${u.port}" else ""
        return "${u.scheme}://${u.host}$p"
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context, url: String, tel: Telemetry, hookJs: String, host: GameHost): WebView {
        val wv = WebView(ctx)
        val gameOrigin = origin(url)
        var hooksOk = false

        wv.setBackgroundColor(Color.BLACK)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)   // renderer siempre en primer plano

        wv.settings.apply {
            javaScriptEnabled = true               // V8 + WebAssembly
            domStorageEnabled = true               // PlayerPrefs / IndexedDB de Unity
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(false)
            builtInZoomControls = false
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect

        // Puente de telemetría y script de arranque, restringidos al origen del juego
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, "gbBridge", setOf(gameOrigin),
                WebViewCompat.WebMessageListener { _, message: WebMessageCompat, _, _, _ ->
                    message.data?.let { tel.handle(it) }
                })
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(wv, hookJs, setOf(gameOrigin))
                hooksOk = true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            private var navStart = 0L
            override fun onPageStarted(v: WebView, u: String?, f: android.graphics.Bitmap?) {
                navStart = SystemClock.elapsedRealtime()
                if (!hooksOk) v.evaluateJavascript(hookJs, null)   // respaldo tardío (sin DOCUMENT_START_SCRIPT)
            }
            override fun onPageFinished(v: WebView, u: String?) {
                tel.pageFinishedMs = SystemClock.elapsedRealtime() - navStart
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (origin(r.url.toString()) != gameOrigin) {
                    tel.addLog("nav", "bloqueada navegación a ${r.url}")
                    return true
                }
                return false
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) host.onMainFrameError("${e.description} (${e.errorCode})")
                else { tel.loadErrors++; tel.addLog("load", "${r.url} ${e.description}") }
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                if (e.statusCode < 400) return
                if (r.isForMainFrame) host.onMainFrameError("HTTP ${e.statusCode}")
                else { tel.httpErrors++; tel.addLog("http", "${e.statusCode} ${r.url}") }
            }
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                host.onRenderGone(d.didCrash())
                return true            // la app NO muere con el renderer
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) tel.addLog("console", m.message().take(200))
                return false
            }
            override fun onShowCustomView(view: View, cb: CustomViewCallback) = host.onCustomView(view, cb)
            override fun onHideCustomView() = host.onCustomView(null, null)
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) = request.deny()
        }
        return wv
    }
}
