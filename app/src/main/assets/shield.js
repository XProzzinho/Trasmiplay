/* Escudo do Transmiplay: bloqueia anúncios sem o site perceber. */
(function () {
  try { if (window.TP && TP.adsOn && !TP.adsOn()) return; } catch (e) {}
  if (window.__tpShield) return;
  window.__tpShield = 1;

  var BAD = __HOSTS__;
  var SEL = __SEL__;
  var MARK = 'data-tpx';

  function isBad(u) {
    try {
      var h = new URL(u, location.href).hostname;
      for (var i = 0; i < BAD.length; i++) { var b = BAD[i]; if (h === b || h.endsWith('.' + b)) return true; }
    } catch (e) {}
    return false;
  }
  function bump() { try { TP.blocked(); } catch (e) {} }
  function noop() {}

  // ------------------------------------------------------------------
  // 1) ferramentas de anúncio FALSAS: o site acha que o anúncio carregou
  try {
    var ads = window.adsbygoogle && window.adsbygoogle.length ? window.adsbygoogle : [];
    ads.loaded = true;
    ads.push = function () { return 1; };
    Object.defineProperty(window, 'adsbygoogle', { configurable: true, get: function () { return ads; }, set: function () {} });
    window.google_ad_status = 1;
    window.google_ad_client = window.google_ad_client || 'ca-pub-0';
  } catch (e) {}

  try {
    var slot = {};
    ['addService', 'defineSizeMapping', 'setTargeting', 'clearTargeting', 'setCollapseEmptyDiv', 'setClickUrl',
     'setForceSafeFrame', 'setSafeFrameConfig', 'updateTargetingFromMap', 'setConfig']
      .forEach(function (m) { slot[m] = function () { return slot; }; });
    slot.getSlotElementId = function () { return ''; };
    slot.getAdUnitPath = function () { return ''; };
    slot.getTargeting = function () { return []; };
    slot.getTargetingKeys = function () { return []; };
    slot.getResponseInformation = function () { return null; };
    var pubads = {};
    ['addEventListener', 'removeEventListener', 'enableSingleRequest', 'enableLazyLoad', 'collapseEmptyDivs',
     'disableInitialLoad', 'setTargeting', 'clearTargeting', 'setRequestNonPersonalizedAds', 'setPrivacySettings',
     'setCentering', 'enableAsyncRendering', 'setForceSafeFrame', 'set', 'refresh', 'clear', 'setLocation',
     'setPublisherProvidedId', 'setSafeFrameConfig', 'enableVideoAds', 'updateCorrelator']
      .forEach(function (m) { pubads[m] = function () { return pubads; }; });
    pubads.getSlots = function () { return []; };
    pubads.getTargeting = function () { return []; };
    pubads.getTargetingKeys = function () { return []; };
    pubads.isInitialLoadDisabled = function () { return false; };
    var cmd = [];
    cmd.push = function (f) { try { if (typeof f === 'function') f(); } catch (e) {} return 1; };
    var gt = {
      apiReady: true, pubadsReady: true, _loaded_: true, cmd: cmd,
      pubads: function () { return pubads; },
      companionAds: function () { return pubads; },
      content: function () { return pubads; },
      defineSlot: function () { return slot; },
      defineOutOfPageSlot: function () { return slot; },
      sizeMapping: function () { var s = { addSize: function () { return s; }, build: function () { return []; } }; return s; },
      enableServices: noop, display: noop, destroySlots: function () { return true; },
      setAdIframeTitle: noop, openConsole: noop, getVersion: function () { return '1'; },
    };
    var old = window.googletag;
    window.googletag = gt;
    if (old && old.cmd && old.cmd.length) for (var k = 0; k < old.cmd.length; k++) cmd.push(old.cmd[k]);
  } catch (e) {}

  // detectores de bloqueador mais usados: sempre respondem "não tem bloqueador"
  try {
    function Detector() {}
    Detector.prototype.onDetected = function () { return this; };
    Detector.prototype.onNotDetected = function (f) { try { setTimeout(f, 1); } catch (e) {} return this; };
    Detector.prototype.on = function (detected, f) { if (!detected) this.onNotDetected(f); return this; };
    Detector.prototype.check = function () { return false; };
    Detector.prototype.emitEvent = function () { return this; };
    Detector.prototype.clearEvent = noop;
    Detector.prototype.setOption = function () { return this; };
    ['fuckAdBlock', 'blockAdBlock', 'sniffAdBlock'].forEach(function (n) { window[n] = new Detector(); });
    window.FuckAdBlock = window.BlockAdBlock = Detector;
    window.canRunAds = true;
    window.isAdBlockActive = false;
    window.adblockDetected = false;
    window.adBlockEnabled = false;
    window.adblock = false;
  } catch (e) {}

  // ------------------------------------------------------------------
  // 2) esconder anúncios mas fingir que continuam lá (tamanho e estilo de antes)
  var real = {};
  try {
    var EP = Element.prototype, HP = HTMLElement.prototype;
    var dims = ['offsetHeight', 'offsetWidth', 'clientHeight', 'clientWidth'];
    dims.forEach(function (p) {
      var proto = Object.getOwnPropertyDescriptor(HP, p) ? HP : EP;
      var d = Object.getOwnPropertyDescriptor(proto, p);
      if (!d || !d.get) return;
      Object.defineProperty(proto, p, {
        configurable: true, enumerable: d.enumerable,
        get: function () {
          if (this.__tpSize) return this.__tpSize[p] || 1;
          return d.get.call(this);
        },
      });
    });
    var gbr = EP.getBoundingClientRect;
    EP.getBoundingClientRect = function () {
      var s = this.__tpSize;
      if (!s) return gbr.call(this);
      var r = { x: 0, y: 0, top: 0, left: 0, width: s.offsetWidth || 1, height: s.offsetHeight || 1 };
      r.right = r.width; r.bottom = r.height; r.toJSON = function () { return r; };
      return r;
    };
    var gcs = window.getComputedStyle;
    window.getComputedStyle = function (el, pseudo) {
      var cs = gcs.call(window, el, pseudo);
      if (!el || !el.__tpSize || pseudo) return cs;
      var fake = { display: 'block', visibility: 'visible', opacity: '1', height: (el.__tpSize.offsetHeight || 1) + 'px' };
      return new Proxy(cs, {
        get: function (t, prop) {
          if (prop in fake) return fake[prop];
          if (prop === 'getPropertyValue') return function (n) { return n in fake ? fake[n] : t.getPropertyValue(n); };
          var v = t[prop];
          return typeof v === 'function' ? v.bind(t) : v;
        },
      });
    };
    real.ok = true;
  } catch (e) {}

  function hide(el) {
    if (el.hasAttribute(MARK)) return;
    // guarda o tamanho de antes pra mostrar pro site
    var s = {};
    try {
      s.offsetHeight = el.offsetHeight || 1; s.offsetWidth = el.offsetWidth || 1;
      s.clientHeight = el.clientHeight || 1; s.clientWidth = el.clientWidth || 1;
    } catch (e) {}
    el.__tpSize = s;
    el.setAttribute(MARK, '');
    bump();
  }

  function addCss() {
    if (document.getElementById('__tpcss')) return;
    var st = document.createElement('style');
    st.id = '__tpcss';
    st.textContent = '[' + MARK + ']{display:none!important}';
    (document.head || document.documentElement).appendChild(st);
  }

  // ------------------------------------------------------------------
  // 3) avisos de "desative seu bloqueador"
  var NAG = /(ad ?block|bloqueador de an[uú]ncio|bloqueador detectado|desativ\w* (o |seu )?bloqueador|disable (your )?ad.?block|turn off (your )?ad.?block|whitelist|lista de permiss)/i;

  function killNags() {
    var cand = document.querySelectorAll('div,section,aside,dialog');
    for (var i = 0; i < cand.length; i++) {
      var el = cand[i];
      if (el.__tpNag === false) continue;
      var st = getComputedStyle(el);
      if (st.position !== 'fixed' && st.position !== 'absolute' && el.tagName !== 'DIALOG') { el.__tpNag = false; continue; }
      var txt = (el.textContent || '').slice(0, 600);
      if (!NAG.test(txt)) { el.__tpNag = false; continue; }
      if (el.querySelector('video')) { el.__tpNag = false; continue; }
      el.remove();
      try {
        document.documentElement.style.setProperty('overflow', 'auto', 'important');
        document.body.style.setProperty('overflow', 'auto', 'important');
        document.body.style.removeProperty('position');
      } catch (e) {}
    }
  }

  // ------------------------------------------------------------------
  // 4) limpeza da página
  function vw() { return Math.max(document.documentElement.clientWidth, window.innerWidth || 0); }
  function vh() { return Math.max(document.documentElement.clientHeight, window.innerHeight || 0); }

  function clean() {
    addCss();
    try { var list = document.querySelectorAll(SEL); for (var i = 0; i < list.length; i++) hide(list[i]); } catch (e) {}
    var fr = document.querySelectorAll('iframe,embed,object');
    for (var j = 0; j < fr.length; j++) {
      var f = fr[j], src = f.src || f.data || '';
      if (src && isBad(src) && !f.hasAttribute(MARK)) hide(f);
    }
    // camadas invisíveis por cima do vídeo que abrem propaganda
    var all = document.querySelectorAll('a,div,span,ins');
    var W = vw(), H = vh();
    for (var k = 0; k < all.length; k++) {
      var el = all[k];
      if (el.__tpok || el.hasAttribute(MARK)) continue;
      var st = getComputedStyle(el);
      if (st.position !== 'fixed' && st.position !== 'absolute') { el.__tpok = 1; continue; }
      var r = el.getBoundingClientRect();
      if (r.width < W * 0.5 || r.height < H * 0.3) { el.__tpok = 1; continue; }
      if (el.querySelector('video,iframe[allowfullscreen]')) { el.__tpok = 1; continue; }
      var z = parseInt(st.zIndex) || 0;
      var see = parseFloat(st.opacity);
      var bg = st.backgroundColor;
      var clear = (bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent') && !el.textContent.trim() && !el.querySelector('img,svg,canvas');
      var link = el.tagName === 'A' && el.target === '_blank' && el.href && (new URL(el.href, location.href).hostname !== location.hostname);
      if ((z >= 100 && (see < 0.05 || clear)) || (link && z >= 10) || (el.href && isBad(el.href))) { el.remove(); bump(); }
      else el.__tpok = 1;
    }
    killNags();
  }

  // links pra propaganda não abrem
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (a && a.href && isBad(a.href)) { e.preventDefault(); e.stopPropagation(); bump(); }
  }, true);

  var pending = false;
  function soon() {
    if (pending) return;
    pending = true;
    (window.requestAnimationFrame || setTimeout)(function () { pending = false; try { clean(); } catch (e) {} });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', clean); else clean();
  try { new MutationObserver(soon).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
  setTimeout(clean, 1500); setTimeout(clean, 4000); setTimeout(clean, 9000);
})();
