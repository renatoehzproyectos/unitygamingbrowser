package dev.gamingbrowser

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private val tel = Telemetry()
    private lateinit var stab: Stability
    private lateinit var perf: PerfEngine
    private lateinit var bench: Bench
    private lateinit var hookJs: String

    private lateinit var root: FrameLayout
    private lateinit var launcher: LinearLayout
    private lateinit var gameBox: FrameLayout
    private lateinit var overlay: TextView
    private lateinit var urlField: EditText
    private var webView: WebView? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var gaming = false
    private var currentUrl = ""
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); stab = Stability(this); perf = PerfEngine(this, prefs)
        bench = Bench(this, perf, tel, stab, prefs)
        hookJs = assets.open("gaming_hook.js").bufferedReader().use { it.readText() }
        stab.logPreviousExits()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode =
            if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.decorView.setBackgroundColor(Color.BLACK)

        buildUi()
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> onCustomView(null, null)
                    gaming -> showGameMenu()
                    else -> finish()
                }
            }
        })
    }

    // ------------------------------------------------------------ UI
    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun buildUi() {
        root = FrameLayout(this)
        gameBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        launcher = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418")); setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        launcher.addView(TextView(this).apply {
            text = "Unity Gaming Browser"; textSize = 24f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        })
        launcher.addView(TextView(this).apply {
            text = "Solo juegos Unity WebGL · un único WebView a pantalla completa"; setTextColor(Color.LTGRAY); setPadding(0, dp(4), 0, dp(16))
        })
        urlField = EditText(this).apply {
            setText(prefs.url); setSingleLine(); hint = "https://tu-juego.vercel.app/?anticrash=0"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_TEXT_VARIATION_URI; imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { play(); true } else false }
        }
        launcher.addView(urlField)
        launcher.addView(btn("▶  Play") { play() })
        launcher.addView(btn("Performance Monitor: ${if (prefs.overlay) "ON" else "OFF"}") { prefs.overlay = !prefs.overlay; recreateLauncherLabels() }.also { it.tag = "mon" })
        launcher.addView(btn("Configuración técnica") { showSettings() })
        launcher.addView(btn("Ver log de estabilidad") { showText("Estabilidad", stab.tail(40)) })
        launcher.addView(btn("Salir de la app") { finish() })

        overlay = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 10.5f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0)); setPadding(dp(6), dp(4), dp(6), dp(4))
            visibility = View.GONE; isClickable = false; isFocusable = false
        }
        root.addView(gameBox, match()); gameBox.visibility = View.GONE
        root.addView(launcher, match())
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(40), dp(8), 0, 0)
        })
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun recreateLauncherLabels() {
        (launcher.findViewWithTag<Button>("mon")).text = "Performance Monitor: ${if (prefs.overlay) "ON" else "OFF"}"
    }

    // ------------------------------------------------------------ Gaming Mode
    private fun play() {
        var u = urlField.text.toString().trim()
        if (u.isEmpty()) return
        if (!u.contains("://")) u = (if (u.startsWith("localhost")) "http://" else "https://") + u
        prefs.url = u
        startGame(u)
    }

    private fun startGame(url: String) {
        currentUrl = url
        gaming = true
        launcher.visibility = View.GONE; gameBox.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive(prefs.fullscreen)
        tel.reset(); stab.beginSession()

        val wv = GameWebView.create(this, url, tel, hookJs, GameHost(
            onRenderGone = { crashed -> onRenderGone(crashed) },
            onMainFrameError = { msg -> onMainFrameError(msg) },
            onCustomView = { v, cb -> onCustomView(v, cb) }
        ))
        webView = wv
        gameBox.addView(wv, match())
        wv.requestFocus()
        perf.start()
        wv.loadUrl(url)
        setOverlay(prefs.overlay)
    }

    private fun exitGaming() {
        bench.cancel(); perf.stop(); perf.sampling = false
        ui.removeCallbacksAndMessages(null)
        destroyWebView()
        gaming = false
        overlay.visibility = View.GONE
        gameBox.visibility = View.GONE; launcher.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive(false)
        stab.log("sesión cerrada por el usuario")
    }

    private fun destroyWebView() {
        webView?.let { w ->
            try { w.stopLoading(); w.loadUrl("about:blank") } catch (_: Exception) {}
            (w.parent as? ViewGroup)?.removeView(w)
            try { w.destroy() } catch (_: Exception) {}
        }
        webView = null
        gameBox.removeAllViews()
    }

    private fun onRenderGone(crashed: Boolean) {
        bench.cancel()
        val extra = "ctxLost=${tel.contextLost} jsErr=${tel.jsErrors} pss=${perf.pssMb}MB avail=${perf.availMb}MB therm=${perf.thermalName(perf.thermalStatus)}"
        val repeated = stab.onRendererGone(crashed, extra)
        destroyWebView()
        applyImmersive(false)
        AlertDialog.Builder(this)
            .setTitle(if (crashed) "El renderer se ha cerrado (crash)" else "El sistema cerró el renderer (memoria)")
            .setMessage(
                (if (repeated) "Ha ocurrido varias veces seguidas: recargar probablemente falle otra vez. Cierra otras apps o baja la calidad del juego (p. ej. ?scale=).\n\n" else "") +
                "Causa registrada. No se recargará nada automáticamente.\n\n" + extra
            )
            .setCancelable(false)
            .setPositiveButton("Recuperar (recargar)") { _, _ -> startGame(currentUrl) }
            .setNegativeButton("Salir") { _, _ -> exitGaming() }
            .show()
    }

    private fun onMainFrameError(msg: String) {
        stab.log("error de carga principal: $msg")
        AlertDialog.Builder(this).setTitle("No se pudo cargar el juego").setMessage(msg)
            .setPositiveButton("Reintentar") { _, _ -> webView?.reload() }
            .setNegativeButton("Salir") { _, _ -> exitGaming() }.show()
    }

    private fun onCustomView(v: View?, cb: WebChromeClient.CustomViewCallback?) {
        customView?.let { root.removeView(it) }
        customCb?.onCustomViewHidden()
        customView = v; customCb = cb
        if (v != null) root.addView(v, match())
    }

    private fun showGameMenu() {
        val items = arrayOf(
            "Continuar",
            "Performance Monitor: ${if (overlay.visibility == View.VISIBLE) "ocultar" else "mostrar"}",
            "Benchmark (${prefs.benchSeconds} s)",
            "Recargar juego",
            "Log de estabilidad",
            "Salir de Gaming Mode"
        )
        AlertDialog.Builder(this).setTitle("Gaming Mode").setItems(items) { _, i ->
            when (i) {
                1 -> setOverlay(overlay.visibility != View.VISIBLE)
                2 -> startBench()
                3 -> { tel.reset(); webView?.reload() }
                4 -> showText("Estabilidad", stab.tail(40))
                5 -> exitGaming()
            }
        }.setOnDismissListener { if (gaming) applyImmersive(prefs.fullscreen) }.show()
    }

    private fun startBench() {
        val w = webView ?: return
        setOverlay(true)
        overlay.append("\n[BENCHMARK en curso, no toques nada]")
        bench.start(w, currentUrl, prefs.benchSeconds) { text -> showText("Resultado del benchmark", text, copy = true) }
    }

    // ------------------------------------------------------------ Monitor
    private val ticker = object : Runnable {
        override fun run() {
            if (!gaming || overlay.visibility != View.VISIBLE) return
            if (!bench.running) overlay.text = overlayText()
            ui.postDelayed(this, 1000)
        }
    }

    private fun setOverlay(on: Boolean) {
        overlay.visibility = if (on) View.VISIBLE else View.GONE
        perf.sampling = on || bench.running
        ui.removeCallbacks(ticker)
        if (on) { perf.sampleOnce(); ui.post(ticker) }
    }

    private fun f(v: Double, fmt: String = "%.1f") = if (v.isNaN()) "n/d" else fmt.format(v)

    private fun overlayText(): String {
        val t = tel
        val ok = fun(b: Boolean?) = when (b) { true -> "sí"; false -> "NO"; null -> "?" }
        val nativeSlowPct = if (perf.nativeFrames > 0) 100.0 * perf.nativeSlow / perf.nativeFrames else Double.NaN
        return buildString {
            appendLine("Página (rAF, estimado): ${f(t.rafFps)} fps · ft ${f(t.avgMs)} ms · p99 ${f(t.p99Ms)} · pico ${f(t.maxMs)}")
            appendLine("Unity interno: ${if (t.unityFps.isNaN()) "n/d (el juego no lo publica)" else f(t.unityFps) + " fps"}")
            appendLine("Pantalla: ${perf.displayInfo()}  [${perf.supportedRates()}]  DPR ${f(t.dpr, "%.2f")}")
            appendLine("UI nativa: ${perf.nativeFrames} frames · ${f(nativeSlowPct)}% sobre presupuesto · hint=${if (perf.hintActive) "sí" else "no"}")
            appendLine("Mem app PSS ${perf.pssMb} MB (máx ${perf.pssMaxMb}) · libre sist. ${perf.availMb} MB${if (perf.lowMemory) " ¡LOW!" else ""}")
            appendLine("Térmico ${perf.thermalName(perf.thermalStatus)} · headroom ${f(perf.headroom.toDouble(), "%.2f")} · batería ${f(perf.batteryTemp.toDouble())}°C")
            appendLine("WebGL ${t.glType ?: "?"} · ${t.renderer ?: "?"} · buffer ${t.bufW}x${t.bufH} · ext ${t.extCount}")
            appendLine("Ctx perdidos ${t.contextLost} (rest. ${t.contextRestored}) · JS err ${t.jsErrors} · carga err ${t.loadErrors}/${t.httpErrors}")
            append("COI ${ok(t.isolated)} · SAB ${ok(t.sab)}")
            t.log.lastOrNull()?.let { append("\n$it".take(110)) }
        }
    }

    // ------------------------------------------------------------ Configuración
    private fun showSettings() {
        val fpsOpts = arrayOf("30", "60", "90", "120")
        val items = arrayOf(
            "FPS objetivo: ${prefs.targetFps}",
            "Pantalla: ${if (prefs.refreshMode == 0) "igualar al objetivo (recomendado para 60 estables)" else "máxima frecuencia del panel"}",
            "Duración del benchmark: ${prefs.benchSeconds} s",
            "Pantalla completa/inmersivo: ${if (prefs.fullscreen) "sí" else "no"}",
            "Info del dispositivo y WebView"
        )
        AlertDialog.Builder(this).setTitle("Configuración técnica").setItems(items) { _, i ->
            when (i) {
                0 -> AlertDialog.Builder(this).setTitle("FPS objetivo").setItems(fpsOpts) { _, k -> prefs.targetFps = fpsOpts[k].toInt() }.show()
                1 -> prefs.refreshMode = 1 - prefs.refreshMode
                2 -> prefs.benchSeconds = if (prefs.benchSeconds >= 120) 30 else prefs.benchSeconds * 2
                3 -> prefs.fullscreen = !prefs.fullscreen
                4 -> showText("Dispositivo", deviceInfo())
            }
        }.setPositiveButton("Cerrar", null).show()
    }

    private fun deviceInfo(): String {
        val wv = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this)
        return "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "WebView: ${wv?.packageName} ${wv?.versionName}\nPantalla: ${perf.displayInfo()} [${perf.supportedRates()}]\n" +
            "Cores: ${Runtime.getRuntime().availableProcessors()}"
    }

    private fun showText(title: String, body: String, copy: Boolean = false) {
        val b = AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK", null)
        if (copy) b.setNeutralButton("Copiar") { _, _ ->
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("bench", body))
        }
        b.setOnDismissListener { if (gaming) applyImmersive(prefs.fullscreen) }.show()
    }

    // ------------------------------------------------------------ Sistema
    private fun applyImmersive(on: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 3 dedos = menú de Gaming Mode (el evento sigue llegando al juego)
        if (gaming && ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount == 3) showGameMenu()
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (gaming && keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.repeatCount == 0) {
            setOverlay(overlay.visibility != View.VISIBLE); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && gaming) applyImmersive(prefs.fullscreen)
    }

    override fun onResume() { super.onResume(); webView?.onResume() }
    override fun onPause() { webView?.onPause(); super.onPause() }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) stab.log("onTrimMemory level=$level (avail=${perf.availMb}MB)")
    }

    override fun onDestroy() {
        if (gaming) stab.log("Activity destruida durante la partida")
        bench.cancel(); perf.stop(); destroyWebView()
        super.onDestroy()
    }
}
