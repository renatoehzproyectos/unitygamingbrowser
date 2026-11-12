package dev.gamingbrowser

import org.json.JSONObject

/** Datos que reporta la página (hook JS). Solo hilo principal (WebMessageListener corre ahí). */
class Telemetry {
    // Cadencia de requestAnimationFrame de la página: ESTIMACIÓN de presentación, no FPS internos de Unity.
    var rafFps = Double.NaN; var avgMs = Double.NaN; var p99Ms = Double.NaN; var maxMs = Double.NaN
    var unityFps = Double.NaN                      // solo si el juego escribe window.__gb.unityFps
    var visible = true

    var glType: String? = null; var renderer: String? = null; var vendor: String? = null; var glVersion: String? = null
    var extCount = 0; var extOk = ""; var extMissing = ""; var maxTex = 0; var aa = false
    var bufW = 0; var bufH = 0

    var isolated: Boolean? = null; var sab: Boolean? = null
    var cores = 0; var devMem = 0.0; var dpr = 0.0

    var contextLost = 0; var contextRestored = 0
    var jsErrors = 0; var loadErrors = 0; var httpErrors = 0
    var tFirstCanvasMs = -1.0; var tFirstFrameMs = -1.0
    var pageFinishedMs = -1L

    val log = ArrayDeque<String>()
    var onBench: ((JSONObject) -> Unit)? = null

    fun reset() {
        rafFps = Double.NaN; avgMs = Double.NaN; p99Ms = Double.NaN; maxMs = Double.NaN; unityFps = Double.NaN
        glType = null; renderer = null; vendor = null; glVersion = null; extCount = 0; extOk = ""; extMissing = ""
        maxTex = 0; bufW = 0; bufH = 0; isolated = null; sab = null
        contextLost = 0; contextRestored = 0; jsErrors = 0; loadErrors = 0; httpErrors = 0
        tFirstCanvasMs = -1.0; tFirstFrameMs = -1.0; pageFinishedMs = -1L
        log.clear()
    }

    fun addLog(kind: String, msg: String) {
        log.addLast("[$kind] $msg")
        while (log.size > 40) log.removeFirst()
    }

    fun handle(raw: String) {
        val o = try { JSONObject(raw) } catch (e: Exception) { return }
        when (o.optString("t")) {
            "stats" -> {
                visible = o.optBoolean("vis", true)
                if (o.optInt("n") > 0) {
                    rafFps = o.optDouble("fps"); avgMs = o.optDouble("avg"); p99Ms = o.optDouble("p99"); maxMs = o.optDouble("max")
                } else rafFps = 0.0
                unityFps = if (o.isNull("unity")) Double.NaN else o.optDouble("unity", Double.NaN)
                bufW = o.optInt("w", bufW); bufH = o.optInt("h", bufH)
                if (!o.isNull("tc")) tFirstCanvasMs = o.optDouble("tc")
                if (!o.isNull("tf")) tFirstFrameMs = o.optDouble("tf")
            }
            "gl" -> {
                glType = o.optString("type"); renderer = o.optString("renderer"); vendor = o.optString("vendor")
                glVersion = o.optString("version"); extCount = o.optInt("extCount"); extOk = o.optString("extOk")
                extMissing = o.optString("extMissing"); maxTex = o.optInt("maxTex"); aa = o.optBoolean("aa")
                bufW = o.optInt("w"); bufH = o.optInt("h")
                if (glType == "webgl1") addLog("gl", "El juego usa WebGL 1 (WebGL 2 no se obtuvo)")
            }
            "env" -> {
                isolated = o.optBoolean("coi"); sab = o.optBoolean("sab")
                cores = o.optInt("cores"); devMem = o.optDouble("mem"); dpr = o.optDouble("dpr")
            }
            "ctxlost" -> { contextLost++; addLog("gl", "webglcontextlost #$contextLost") }
            "ctxrestored" -> { contextRestored++; addLog("gl", "webglcontextrestored") }
            "err" -> {
                val kind = o.optString("kind"); val msg = o.optString("msg")
                when (kind) { "js" -> jsErrors++; "load" -> loadErrors++; "http" -> httpErrors++ }
                addLog(kind, msg)
            }
            "bench" -> onBench?.invoke(o)
        }
    }
}
