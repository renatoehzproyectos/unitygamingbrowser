(function () {
  'use strict';
  if (window.__gb) return;
  var G = window.__gb = { unityFps: null, tCanvas: null, tFrame: null };

  function send(o) { try { window.gbBridge.postMessage(JSON.stringify(o)); } catch (e) {} }

  // ---- errores (con tope por tipo para no inundar el puente) ----
  var counts = { js: 0, load: 0, http: 0, gl: 0 }, seen = {};
  function err(kind, msg) {
    msg = String(msg).slice(0, 300);
    var k = kind + '|' + msg;
    if (seen[k] || (counts[kind] || 0) >= 40) return;
    seen[k] = 1; counts[kind] = (counts[kind] || 0) + 1;
    send({ t: 'err', kind: kind, msg: msg });
  }
  window.addEventListener('error', function (e) {
    var tg = e.target;
    if (tg && tg !== window && (tg.src || tg.href)) err('load', (tg.tagName || '') + ' ' + (tg.src || tg.href));
    else err('js', (e.message || 'error') + ' @' + (e.filename || '') + ':' + (e.lineno || 0));
  }, true);
  window.addEventListener('unhandledrejection', function (e) {
    var r = e.reason; err('js', 'unhandledrejection: ' + (r && (r.message || r)));
  });
  if (window.fetch) {
    var ofetch = window.fetch;
    window.fetch = function () {
      var a = arguments, u = (a[0] && a[0].url) || a[0];
      var p = ofetch.apply(this, a);
      p.then(function (r) { if (!r.ok) err('http', r.status + ' ' + u); },
             function (e) { err('load', 'fetch ' + u + ' ' + (e && e.message)); });
      return p;
    };
  }

  // ---- WebGL: se observa el contexto que crea el juego (no se crea ninguno extra) ----
  var gl0 = null;
  var origGC = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function (type) {
    var c = origGC.apply(this, arguments);
    var isGL = (typeof type === 'string') && (type.indexOf('webgl') === 0 || type === 'experimental-webgl');
    if (isGL) {
      if (!c) err('gl', 'getContext(' + type + ') devolvio null');
      else if (!gl0) { gl0 = c; G.tCanvas = performance.now(); probe(c); }
    }
    return c;
  };
  function probe(c) {
    try {
      var dbg = c.getExtension('WEBGL_debug_renderer_info');
      var exts = c.getSupportedExtensions() || [];
      var want = ['EXT_texture_filter_anisotropic', 'WEBGL_compressed_texture_astc', 'WEBGL_compressed_texture_etc',
                  'EXT_color_buffer_float', 'EXT_color_buffer_half_float', 'OES_texture_float_linear',
                  'WEBGL_multi_draw', 'KHR_parallel_shader_compile'];
      var attrs = c.getContextAttributes() || {};
      send({
        t: 'gl',
        type: (typeof WebGL2RenderingContext !== 'undefined' && c instanceof WebGL2RenderingContext) ? 'webgl2' : 'webgl1',
        renderer: dbg ? c.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : c.getParameter(c.RENDERER),
        vendor: dbg ? c.getParameter(dbg.UNMASKED_VENDOR_WEBGL) : c.getParameter(c.VENDOR),
        version: c.getParameter(c.VERSION),
        maxTex: c.getParameter(c.MAX_TEXTURE_SIZE),
        extCount: exts.length,
        extOk: want.filter(function (x) { return exts.indexOf(x) >= 0; }).join(','),
        extMissing: want.filter(function (x) { return exts.indexOf(x) < 0; }).join(','),
        aa: !!attrs.antialias, w: c.drawingBufferWidth, h: c.drawingBufferHeight
      });
    } catch (e) { err('gl', 'probe: ' + e); }
  }
  // captura: el evento se dispara en el canvas y no burbujea. No se llama a preventDefault (lo gestiona Unity).
  window.addEventListener('webglcontextlost', function () { send({ t: 'ctxlost' }); }, true);
  window.addEventListener('webglcontextrestored', function () { send({ t: 'ctxrestored' }); }, true);
  document.addEventListener('visibilitychange', function () { send({ t: 'vis', v: document.visibilityState === 'visible' }); });

  // ---- cadencia de requestAnimationFrame (estimacion de FPS de pagina, NO FPS internos de Unity) ----
  var raf = window.requestAnimationFrame.bind(window);
  var lastTs = -1, win = [], bench = null;
  function tick(ts) {
    if (ts === lastTs) return;                       // varios callbacks en el mismo frame
    if (lastTs >= 0) { var dt = ts - lastTs; win.push(dt); if (bench) bench.push(dt); }
    else if (G.tFrame === null) G.tFrame = performance.now();
    lastTs = ts;
  }
  window.requestAnimationFrame = function (cb) { return raf(function (ts) { tick(ts); return cb(ts); }); };

  setInterval(function () {
    var n = win.length, vis = document.visibilityState === 'visible';
    if (n === 0) { send({ t: 'stats', n: 0, vis: vis, tc: G.tCanvas, tf: G.tFrame }); return; }
    var sum = 0, mx = 0, i;
    for (i = 0; i < n; i++) { sum += win[i]; if (win[i] > mx) mx = win[i]; }
    var s = win.slice().sort(function (a, b) { return a - b; });
    var p99 = s[Math.min(n - 1, Math.floor(n * 0.99))];
    send({ t: 'stats', n: n, fps: n / (sum / 1000), avg: sum / n, p99: p99, max: mx, vis: vis,
           unity: G.unityFps, w: gl0 ? gl0.drawingBufferWidth : 0, h: gl0 ? gl0.drawingBufferHeight : 0,
           tc: G.tCanvas, tf: G.tFrame });
    win.length = 0;
  }, 1000);

  // ---- benchmark: calcula en JS y envia solo el resumen ----
  G.benchStart = function () { bench = []; };
  G.benchStop = function () {
    var d = bench || []; bench = null;
    var n = d.length;
    if (n < 2) { send({ t: 'bench', n: n }); return; }
    var sum = 0, i, o33 = 0, o50 = 0;
    for (i = 0; i < n; i++) { sum += d[i]; if (d[i] > 33.4) o33++; if (d[i] > 50) o50++; }
    var avg = sum / n, v = 0;
    for (i = 0; i < n; i++) v += (d[i] - avg) * (d[i] - avg);
    var s = d.slice().sort(function (a, b) { return a - b; });
    var k = Math.max(1, Math.floor(n * 0.01)), w = 0;
    for (i = n - k; i < n; i++) w += s[i];
    send({ t: 'bench', n: n, durMs: sum, fps: n / (sum / 1000), avgMs: avg, stdMs: Math.sqrt(v / n),
           p99Ms: s[Math.min(n - 1, Math.floor(n * 0.99))], maxMs: s[n - 1],
           low1Fps: 1000 / (w / k), over33: o33, over50: o50 });
  };

  // ---- entorno ----
  function env() {
    send({ t: 'env', coi: window.crossOriginIsolated === true, sab: typeof SharedArrayBuffer !== 'undefined',
           cores: navigator.hardwareConcurrency || 0, mem: navigator.deviceMemory || 0,
           dpr: window.devicePixelRatio, sw: screen.width, sh: screen.height });
  }
  document.addEventListener('DOMContentLoaded', env);
  window.addEventListener('load', env);
  env();
})();
