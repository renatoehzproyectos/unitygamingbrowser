package dev.gamingbrowser

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("gb", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "https://") ?: "https://"
        set(v) = sp.edit().putString("url", v).apply()

    /** FPS objetivo (referencia para el presupuesto de frame y el modo de pantalla). No limita el juego. */
    var targetFps: Int
        get() = sp.getInt("targetFps", 60)
        set(v) = sp.edit().putInt("targetFps", v).apply()

    /** 0 = igualar al objetivo (p. ej. 60 Hz, ritmo de vsync estable) · 1 = máximo del panel */
    var refreshMode: Int
        get() = sp.getInt("refreshMode", 0)
        set(v) = sp.edit().putInt("refreshMode", v).apply()

    var overlay: Boolean
        get() = sp.getBoolean("overlay", false)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    var benchSeconds: Int
        get() = sp.getInt("benchSeconds", 60)
        set(v) = sp.edit().putInt("benchSeconds", v).apply()

    var fullscreen: Boolean
        get() = sp.getBoolean("fullscreen", true)
        set(v) = sp.edit().putBoolean("fullscreen", v).apply()
}
