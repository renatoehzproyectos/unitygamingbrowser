package dev.gamingbrowser

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Benchmark reproducible: misma duración y metadatos de configuración guardados con cada resultado. */
class Bench(
    private val ctx: Context, private val perf: PerfEngine, private val tel: Telemetry,
    private val stab: Stability, private val prefs: Prefs
) {
    private val h = Handler(Looper.getMainLooper())
    var running = false; private set
    private var done: ((String) -> Unit)? = null
    private var url = ""; private var seconds = 60
    private var thermStart = 0; private var battStart = Float.NaN
    private var ctxLost0 = 0; private var js0 = 0; private var rend0 = 0
    private var ui0 = 0L; private var uiSlow0 = 0L
    private var timeout: Runnable? = null

    fun start(wv: WebView, gameUrl: String, secs: Int, onDone: (String) -> Unit) {
        if (running) return
        running = true; done = onDone; url = gameUrl; seconds = secs
        perf.sampling = true; perf.sampleOnce(); perf.resetMaxima()
        thermStart = perf.thermalStatus; battStart = perf.batteryTemp
        ctxLost0 = tel.contextLost; js0 = tel.jsErrors; rend0 = stab.rendererLosses
        ui0 = perf.nativeFrames; uiSlow0 = perf.nativeSlow
        tel.onBench = { r -> finish(r) }
        wv.evaluateJavascript("window.__gb&&window.__gb.benchStart()", null)
        h.postDelayed({
            wv.evaluateJavascript("window.__gb&&window.__gb.benchStop()", null)
            timeout = Runnable { abort("sin respuesta de la página (¿hook JS inactivo?)") }
            h.postDelayed(timeout!!, 4000)
        }, secs * 1000L)
    }

    fun cancel() { h.removeCallbacksAndMessages(null); tel.onBench = null; running = false }

    private fun abort(why: String) { running = false; tel.onBench = null; done?.invoke("Benchmark fallido: $why") }

    private fun finish(r: JSONObject) {
        timeout?.let { h.removeCallbacks(it) }
        tel.onBench = null; running = false
        perf.sampleOnce()
        if (r.optInt("n") < 2) { done?.invoke("Benchmark fallido: sin frames medidos"); return }
        val wvVer = WebViewCompat.getCurrentWebViewPackage(ctx)?.versionName ?: "?"
        val j = JSONObject().apply {
            put("schema", 1)
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            put("url", url)
            put("device", JSONObject().put("model", "${Build.MANUFACTURER} ${Build.MODEL}").put("sdk", Build.VERSION.SDK_INT)
                .put("webview", wvVer).put("app", BuildConfig.VERSION_NAME))
            put("config", JSONObject().put("durationS", seconds).put("targetFps", prefs.targetFps)
                .put("refreshMode", prefs.refreshMode).put("display", perf.displayInfo())
                .put("buffer", "${tel.bufW}x${tel.bufH}").put("dpr", tel.dpr).put("gl", tel.glType ?: "?")
                .put("renderer", tel.renderer ?: "?"))
            put("frames_raf", r)   // fps, low1Fps, avgMs, p99Ms, maxMs, over33, over50, stdMs
            put("memory", JSONObject().put("appPssMaxMb", perf.pssMaxMb)
                .put("note", "PSS de la app; el proceso renderer del WebView no es medible desde la app"))
            put("thermal", JSONObject().put("statusStart", perf.thermalName(thermStart))
                .put("statusEnd", perf.thermalName(perf.thermalStatus)).put("statusMax", perf.thermalName(perf.thermalMax))
                .put("headroomMax", perf.headroomMax.toDouble().takeIf { !it.isNaN() })
                .put("batteryTempStartC", battStart.toDouble().takeIf { !it.isNaN() })
                .put("batteryTempMaxC", perf.batteryTempMax.toDouble().takeIf { !it.isNaN() }))
            put("load", JSONObject().put("pageFinishedMs", tel.pageFinishedMs)
                .put("firstCanvasMs", tel.tFirstCanvasMs).put("firstRafMs", tel.tFirstFrameMs))
            put("stability", JSONObject().put("rendererLosses", stab.rendererLosses - rend0)
                .put("contextLosses", tel.contextLost - ctxLost0).put("jsErrors", tel.jsErrors - js0))
            put("native_ui", JSONObject().put("frames", perf.nativeFrames - ui0).put("slowFrames", perf.nativeSlow - uiSlow0))
        }
        val dir = File(ctx.getExternalFilesDir(null), "benchmarks").apply { mkdirs() }
        val f = File(dir, "bench_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json")
        f.writeText(j.toString(2))
        stab.log("benchmark guardado ${f.name}")
        val fr = r
        done?.invoke(
            "FPS prom (rAF): %.1f\n1%% low: %.1f fps\nFrame time prom: %.2f ms\nP99: %.1f ms · pico: %.1f ms\nFrames >33 ms: %d · >50 ms: %d\nPSS máx app: %d MB\nTérmico: %s → %s (máx %s)\nPérdidas ctx: %d · errores JS: %d · renderer: %d\n\nGuardado: %s".format(
                fr.optDouble("fps"), fr.optDouble("low1Fps"), fr.optDouble("avgMs"), fr.optDouble("p99Ms"), fr.optDouble("maxMs"),
                fr.optInt("over33"), fr.optInt("over50"), perf.pssMaxMb,
                perf.thermalName(thermStart), perf.thermalName(perf.thermalStatus), perf.thermalName(perf.thermalMax),
                tel.contextLost - ctxLost0, tel.jsErrors - js0, stab.rendererLosses - rend0, f.absolutePath)
        )
    }
}
