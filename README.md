# Rocket World – lanzador Android (Trusted Web Activity)

**Por qué ya no es un WebView:** el juego exige `crossOriginIsolated` (COOP/COEP) y Android WebView no soporta COOP, así que SharedArrayBuffer/WASM con hilos nunca funcionan allí. Esta app lanza `https://rocket-world.vercel.app/?anticrash=0` en el Chrome real mediante una Trusted Web Activity (inmersiva, horizontal).

## Compilar
Sube esta carpeta a la raíz de un repo (`main`): el workflow `build-apk.yml` publica el APK en Releases.

## Quitar la barra de URL (obligatorio para pantalla completa real)
Sin verificación, Chrome abre el juego como Custom Tab (barra visible; el juego sí funciona). Para TWA completa:
1. Copia `.well-known-for-your-game/.well-known/assetlinks.json` a la raíz del **repo del juego** (`/.well-known/assetlinks.json`) y despliega en Vercel.
2. Comprueba que `https://rocket-world.vercel.app/.well-known/assetlinks.json` carga.
3. Reinstala la app (o borra datos de Chrome para el sitio) para que Chrome revalide.
Firma fija: `debug.keystore` (clave personal, pública en el repo; no uses esta app para nada sensible). SHA-256: ver el resumen de cada build.

## Limitaciones
- No hay overlay, benchmark ni hooks JS (Chrome no deja inyectar código en una TWA). Usa el HUD del juego (`DebugHud`) y `?bench=1`.
- No controlamos la frecuencia de pantalla ni FLAG_KEEP_SCREEN_ON: pon el tiempo de pantalla al máximo, o que el juego pida `navigator.wakeLock`.
- Antes de medir, comprueba en Chrome `chrome://gpu` (WebGL2 acelerado por hardware).

`legacy-webview/` conserva el código del WebView anterior (no se compila).
