/* Acha o vídeo de verdade, mesmo quando o player esconde o endereço (roda em todos os quadros). */
(function () {
  if (window.__tpSniff) return;
  window.__tpSniff = 1;
  var seen = {};

  function report(url, type) {
    try {
      if (!url || typeof url !== 'string') return;
      var abs = new URL(url, location.href).href;
      if (abs.indexOf('http') !== 0) return;
      var key = abs + '|' + type;
      if (seen[key]) return;
      seen[key] = 1;
      TP.foundType(abs, type, location.href, document.title || '');
    } catch (e) {}
  }

  function typeFromText(t) {
    if (!t) return null;
    var s = t.slice(0, 200).trim();
    if (s.indexOf('#EXTM3U') === 0) {
      // lista principal ou de qualidade: as duas servem
      return 'm3u8';
    }
    if (s.indexOf('<MPD') >= 0 || (s.indexOf('<?xml') === 0 && t.slice(0, 600).indexOf('<MPD') >= 0)) return 'mpd';
    return null;
  }

  function typeFromCT(ct) {
    ct = (ct || '').toLowerCase();
    if (ct.indexOf('mpegurl') >= 0) return 'm3u8';
    if (ct.indexOf('dash+xml') >= 0) return 'mpd';
    if (ct.indexOf('video/mp4') === 0) return 'mp4';
    if (ct.indexOf('video/webm') === 0) return 'webm';
    return null;
  }

  // ---- fetch
  try {
    var of = window.fetch;
    if (of) window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      return of.apply(this, arguments).then(function (res) {
        try {
          var ct = res.headers.get('content-type') || '';
          var t = typeFromCT(ct);
          if (t === 'm3u8' || t === 'mpd') report(res.url || url, t);
          else if (!t && /text|octet|json|^$/.test(ct) && (+(res.headers.get('content-length') || 0) < 3e6)) {
            res.clone().text().then(function (txt) { var k = typeFromText(txt); if (k) report(res.url || url, k); }).catch(function () {});
          }
        } catch (e) {}
        return res;
      });
    };
  } catch (e) {}

  // ---- XMLHttpRequest
  try {
    var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) { this.__tpUrl = u; return XO.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function () {
      var x = this;
      x.addEventListener('load', function () {
        try {
          var url = x.responseURL || x.__tpUrl;
          var t = typeFromCT(x.getResponseHeader('content-type'));
          if (t === 'm3u8' || t === 'mpd') return report(url, t);
          if (x.responseType === '' || x.responseType === 'text') { var k = typeFromText(x.responseText); if (k) report(url, k); }
        } catch (e) {}
      });
      return XS.apply(this, arguments);
    };
  } catch (e) {}

  // ---- <video src="..."> definido por código
  try {
    var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
      configurable: true, enumerable: d.enumerable,
      get: function () { return d.get.call(this); },
      set: function (v) {
        try { if (typeof v === 'string' && v.indexOf('http') === 0) report(v, guess(v)); } catch (e) {}
        return d.set.call(this, v);
      },
    });
  } catch (e) {}

  function guess(u) {
    var p = u.toLowerCase().split('?')[0];
    if (p.slice(-5) === '.m3u8') return 'm3u8';
    if (p.slice(-4) === '.mpd') return 'mpd';
    if (p.slice(-5) === '.webm') return 'webm';
    return 'mp4';
  }

  // ---- players conhecidos (hls.js, dash.js, Shaka, JW Player, Video.js)
  function hookPlayers() {
    try {
      var H = window.Hls;
      if (H && H.prototype && !H.prototype.__tp) {
        var ls = H.prototype.loadSource;
        H.prototype.loadSource = function (u) { report(u, 'm3u8'); return ls.apply(this, arguments); };
        H.prototype.__tp = 1;
      }
    } catch (e) {}
    try {
      var dj = window.dashjs && window.dashjs.MediaPlayer;
      if (dj && !dj.__tp) {
        var orig = dj; dj.__tp = 1;
        window.dashjs.MediaPlayer = function () {
          var f = orig.apply(this, arguments);
          var cr = f.create;
          f.create = function () {
            var p = cr.apply(this, arguments);
            var att = p.attachSource, ini = p.initialize;
            p.attachSource = function (u) { report(u, 'mpd'); return att.apply(this, arguments); };
            p.initialize = function (v, u) { if (u) report(u, 'mpd'); return ini.apply(this, arguments); };
            return p;
          };
          return f;
        };
      }
    } catch (e) {}
    try {
      var sp = window.shaka && window.shaka.Player && window.shaka.Player.prototype;
      if (sp && !sp.__tp) {
        var ld = sp.load; sp.__tp = 1;
        sp.load = function (u) { report(u, /\.mpd/i.test(u) ? 'mpd' : 'm3u8'); return ld.apply(this, arguments); };
      }
    } catch (e) {}
    try {
      if (window.jwplayer && !window.jwplayer.__tp) {
        var jw = window.jwplayer;
        window.jwplayer = function () {
          var inst = jw.apply(this, arguments);
          try {
            var item = inst && inst.getPlaylistItem && inst.getPlaylistItem();
            if (item && item.file) report(item.file, /m3u8/i.test(item.file) ? 'm3u8' : guess(item.file));
            if (inst && inst.on && !inst.__tp) {
              inst.__tp = 1;
              inst.on('playlistItem', function (e) { try { var f = e.item.file; report(f, /m3u8/i.test(f) ? 'm3u8' : guess(f)); } catch (x) {} });
            }
          } catch (e) {}
          return inst;
        };
        for (var k in jw) try { window.jwplayer[k] = jw[k]; } catch (e) {}
        window.jwplayer.__tp = 1;
      }
    } catch (e) {}
    try {
      var vj = window.videojs;
      if (vj && !vj.__tp && vj.getPlayers) {
        vj.__tp = 1;
        setInterval(function () {
          var ps = vj.getPlayers();
          for (var id in ps) try {
            var p = ps[id]; if (!p) continue;
            var s = p.currentSource && p.currentSource();
            if (s && s.src) report(s.src, /mpegurl/i.test(s.type || '') || /m3u8/i.test(s.src) ? 'm3u8' : guess(s.src));
          } catch (e) {}
        }, 2000);
      }
    } catch (e) {}
  }
  hookPlayers();
  var n = 0;
  var iv = setInterval(function () { hookPlayers(); if (++n > 60) clearInterval(iv); }, 500);
})();
