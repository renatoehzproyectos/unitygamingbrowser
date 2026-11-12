package dev.gamingbrowser

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.view.FrameMetrics
import android.view.Window
import android.view.WindowManager
import java.io.File
import kotlin.math.abs

/**
 * Gestión adaptativa: modo de pantalla, PerformanceHintManager, estado térmico y memoria.
 * NO fuerza frecuencias de CPU/GPU ni toca la protección térmica: solo informa al sistema (hints) y mide.
 */
class PerfEngine(private val act: Activity, private val prefs: Prefs) {
    private val pm = act.getSystemService(PowerManager::class.java)
    private val am = act.getSystemService(ActivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    // ---- estado publicado (lectura desde UI) ----
    @Volatile var thermalStatus = PowerManager.THERMAL_STATUS_NONE; private set
    @Volatile var thermalMax = PowerManager.THERMAL_STATUS_NONE; private set
    @Volatile var headroom = Float.NaN; private set
    @Volatile var headroomMax = Float.NaN; private set
    @Volatile var batteryTemp = Float.NaN; private set
    @Volatile var batteryTempMax = Float.NaN; private set
    @Volatile var pssMb = 0L; private set
    @Volatile var pssMaxMb = 0L; private set
    @Volatile var availMb = 0L; private set
    @Volatile var lowMemory = false; private set
    @Volatile var nativeFrames = 0L; private set
    @Volatile var nativeSlow = 0L; private set
    @Volatile var hintActive = false; private set
    @Volatile var sampling = false

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { s ->
        thermalStatus = s; if (s > thermalMax) thermalMax = s
    }

    private val ht = HandlerThread("gb-perf").apply { start() }
    private val bg = Handler(ht.looper)
    @Volatile private var hint: PerformanceHintManager.Session? = null
    private val budgetNs get() = 1_000_000_000L / prefs.targetFps

    // Duración real de cada frame de la ventana (UI + dibujo del WebView) -> hint + estadística nativa
    private val fm = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        val t = m.getMetric(FrameMetrics.TOTAL_DURATION)
        nativeFrames++
        if (t > budgetNs) nativeSlow++
        try { hint?.reportActualWorkDuration(t) } catch (_: Exception) {}
    }

    private val sampler = object : Runnable {
        override fun run() {
            if (sampling) sampleOnce()
            bg.postDelayed(this, 3000)
        }
    }

    fun start() {
        applyRefreshRate()
        nativeFrames = 0; nativeSlow = 0; resetMaxima()
        try { pm.addThermalStatusListener(act.mainExecutor, thermalListener) } catch (_: Exception) {}
        act.window.addOnFrameMetricsAvailableListener(fm, bg)
        bg.postDelayed({ createHint() }, 1500)     // el RenderThread existe tras el primer dibujo
        bg.postDelayed(sampler, 3000)
        if (Build.VERSION.SDK_INT >= 33) try {
            val gm = act.getSystemService(android.app.GameManager::class.java)
            gm.setGameState(android.app.GameState(false, android.app.GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE))
        } catch (_: Exception) {}
    }

    fun stop() {
        bg.removeCallbacksAndMessages(null)
        try { act.window.removeOnFrameMetricsAvailableListener(fm) } catch (_: Exception) {}
        try { pm.removeThermalStatusListener(thermalListener) } catch (_: Exception) {}
        try { hint?.close() } catch (_: Exception) {}
        hint = null; hintActive = false
        if (Build.VERSION.SDK_INT >= 33) try {
            val gm = act.getSystemService(android.app.GameManager::class.java)
            gm.setGameState(android.app.GameState(false, android.app.GameState.MODE_NONE))
        } catch (_: Exception) {}
    }

    fun resetMaxima() {
        thermalMax = thermalStatus; headroomMax = Float.NaN; batteryTempMax = Float.NaN; pssMaxMb = 0
    }

    fun sampleOnce() {
        val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi)      // costoso: por eso va en hilo aparte cada 3 s
        pssMb = mi.totalPss / 1024L
        if (pssMb > pssMaxMb) pssMaxMb = pssMb
        val s = ActivityManager.MemoryInfo(); am.getMemoryInfo(s)
        availMb = s.availMem / (1024 * 1024); lowMemory = s.lowMemory
        if (Build.VERSION.SDK_INT >= 30) {
            val h = pm.getThermalHeadroom(10)
            headroom = h
            if (!h.isNaN() && (headroomMax.isNaN() || h > headroomMax)) headroomMax = h
        }
        val i = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        batteryTemp = if (t == Int.MIN_VALUE) Float.NaN else t / 10f
        if (!batteryTemp.isNaN() && (batteryTempMax.isNaN() || batteryTemp > batteryTempMax)) batteryTempMax = batteryTemp
    }

    // ---- pantalla ----
    @Suppress("DEPRECATION")
    private fun display() = act.windowManager.defaultDisplay

    fun applyRefreshRate() {
        val d = display()
        val cur = d.mode
        val cands = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        if (cands.isEmpty()) return
        val target = prefs.targetFps.toFloat()
        val pick = if (prefs.refreshMode == 1) cands.maxBy { it.refreshRate }
                   else cands.minBy { abs(it.refreshRate - target) }
        val lp: WindowManager.LayoutParams = act.window.attributes
        lp.preferredDisplayModeId = pick.modeId
        act.window.attributes = lp
    }

    fun displayInfo(): String {
        val m = display().mode
        return "${m.physicalWidth}x${m.physicalHeight} @ ${"%.1f".format(display().refreshRate)} Hz"
    }

    fun supportedRates(): String = try {
        val cur = display().mode
        display().supportedModes.filter { it.physicalWidth == cur.physicalWidth }
            .map { "%.0f".format(it.refreshRate) }.distinct().joinToString("/") + " Hz"
    } catch (_: Exception) { "?" }

    // ---- PerformanceHintManager: hilos de UI + RenderThread de esta app (el renderer web es otro proceso) ----
    private fun createHint() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val mgr = act.getSystemService(PerformanceHintManager::class.java) ?: return
            val tids = intArrayOf(Process.myPid()) + renderThreadTids()
            hint = mgr.createHintSession(tids, budgetNs)
            hintActive = hint != null
        } catch (_: Exception) { hintActive = false }
    }

    private fun renderThreadTids(): IntArray = try {
        File("/proc/self/task").listFiles()
            ?.filter { runCatching { File(it, "comm").readText().trim() == "RenderThread" }.getOrDefault(false) }
            ?.mapNotNull { it.name.toIntOrNull() }?.toIntArray() ?: IntArray(0)
    } catch (_: Exception) { IntArray(0) }

    fun thermalName(s: Int) = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "?"
    }
}
