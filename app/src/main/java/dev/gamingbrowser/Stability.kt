package dev.gamingbrowser

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro de causas de cierre y control anti-bucle de recuperación. */
class Stability(private val ctx: Context) {
    private val file = File(ctx.filesDir, "stability.log")
    private val goneTimes = ArrayList<Long>()
    var rendererLosses = 0
        private set

    fun log(msg: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            if (file.exists() && file.length() > 256 * 1024) file.writeText("")   // tope de tamaño
            file.appendText("$ts  $msg\n")
        } catch (_: Exception) {}
    }

    fun beginSession() { goneTimes.clear(); rendererLosses = 0; log("sesión iniciada") }

    /** Devuelve true si el fallo es repetido (≥2 en 3 min): se avisa y NUNCA se recarga solo. */
    fun onRendererGone(didCrash: Boolean, extra: String): Boolean {
        rendererLosses++
        val now = SystemClock.elapsedRealtime()
        goneTimes.add(now)
        goneTimes.removeAll { now - it > 180_000 }
        log("renderer perdido: ${if (didCrash) "CRASH" else "matado por el sistema (memoria)"} · $extra")
        return goneTimes.size >= 2
    }

    /** Causas del cierre anterior del proceso (API 30+). */
    fun logPreviousExits() {
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 3).forEach {
                log("salida previa: ${reasonName(it.reason)} · imp=${it.importance} · ${it.description ?: "-"}")
            }
        } catch (_: Exception) {}
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        else -> "reason=$r"
    }

    fun tail(lines: Int = 25): String = try {
        file.readLines().takeLast(lines).joinToString("\n").ifEmpty { "(vacío)" }
    } catch (_: Exception) { "(sin log)" }
}
