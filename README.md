# Unity Gaming Browser (Android)

WebView único a pantalla completa para juegos Unity WebGL. Kotlin, minSdk 29, Android Studio (Ladybug+). **Sin compilar todavía** (no hay Android SDK en mi entorno): espera una pequeña iteración de errores de compilación.

## Compilar
Abrir la carpeta en Android Studio → Sync → Run en el dispositivo (o `./gradlew assembleDebug`; si falta el wrapper: `gradle wrapper --gradle-version 8.9`).

## Uso
URL → Play. En partida: **gesto de 3 dedos** o **Atrás** = menú; **Volumen +** = Performance Monitor. Menú → Benchmark (mismo juego/escena/duración).
Resultados: `/sdcard/Android/data/dev.gamingbrowser/files/benchmarks/*.json` (adb pull) → `python tools/compare_bench.py a.json b.json`.
Para Rocket World usa `?anticrash=0` (el "Anti crash" del juego arranca con escala/DoF/motion blur al mínimo y los modula) y revisa `?scale=` (por defecto 2.0 = supersampling x2).
Depuración remota: build debug + `chrome://inspect`.

## Qué mide cada cosa (honesto)
- **FPS de página (rAF)**: cadencia de requestAnimationFrame; estimación de presentación. Los FPS internos de Unity solo aparecen si el juego escribe `window.__gb.unityFps`.
- **UI nativa**: FrameMetrics de la ventana de la app (no del proceso renderer).
- **Memoria**: PSS de la app; el proceso renderer (donde vive el heap WASM) no es medible desde fuera.
- **Térmico**: estado oficial + headroom (API 30+) + temperatura de batería (aproximación).
- **Frecuencia de pantalla**: por defecto se iguala al FPS objetivo (60 Hz) para un ritmo de vsync estable; "máximo" usa el panel completo (el juego irá a 144 si puede).
- WebView no permite elegir backend gráfico ni flags de Chromium; eso queda para el Chromium propio (la capa `GameWebView` aísla ese cambio).
