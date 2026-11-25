package com.geanalves.transmiplay

import java.net.URI

/**
 * Bloqueador de anúncios do navegador (no estilo do escudo do Brave):
 * 1) corta pedidos pra servidores de propaganda e rastreio,
 * 2) esconde os espaços de anúncio que sobram na página,
 * 3) tira camadas invisíveis que abrem propaganda quando você toca no vídeo.
 */
object AdBlock {

    /** servidores de propaganda, pop-up e rastreio mais comuns */
    val HOSTS: Set<String> = hashSetOf(
        // Google / grandes redes
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "imasdk.googleapis.com", "googletagservices.com", "google-analytics.com",
        "googletagmanager.com", "adsense.com", "2mdn.net", "admob.com", "app-measurement.com",
        "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net", "pubmatic.com",
        "rubiconproject.com", "openx.net", "casalemedia.com", "indexww.com", "smartadserver.com", "adform.net",
        "yieldmo.com", "sharethrough.com", "triplelift.com", "3lift.com", "media.net", "contextweb.com",
        "outbrain.com", "outbrainimg.com", "taboola.com", "taboolasyndication.com", "revcontent.com", "mgid.com",
        "zergnet.com", "teads.tv", "connatix.com", "primis.tech", "unrulymedia.com", "spotxchange.com",
        "spotx.tv", "springserve.com", "aniview.com", "vidoomy.com", "a-mo.net", "adcolony.com",
        "applovin.com", "unityads.unity3d.com", "moatads.com", "scorecardresearch.com", "quantserve.com",
        "chartbeat.com", "hotjar.com", "mouseflow.com", "crazyegg.com", "facebook.net", "connect.facebook.net",
        "ads-twitter.com", "ads.linkedin.com", "analytics.tiktok.com", "ads.yahoo.com", "advertising.com",
        "bidswitch.net", "adroll.com", "lijit.com", "sovrn.com", "33across.com", "gumgum.com", "teads.com",
        "id5-sync.com", "agkn.com", "bluekai.com", "krxd.net", "exelator.com", "demdex.net", "everesttech.net",
        "rlcdn.com", "tapad.com", "mathtag.com", "turn.com", "serving-sys.com", "flashtalking.com",
        "innovid.com", "tremorhub.com", "yieldlab.net", "adition.com", "zemanta.com", "adsafeprotected.com",
        "doubleverify.com", "ias.net", "permutive.com", "onesignal.com", "pushwoosh.com", "pushengage.com",
        // pop-ups, pop-unders e redes de sites de vídeo
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "adsterra.com", "adsterratools.com",
        "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net", "trafficjunky.com", "onclickads.net",
        "onclickmax.com", "onclkds.com", "hilltopads.net", "hilltopads.com", "adcash.com", "clickadu.com",
        "a-ads.com", "monetag.com", "highperformanceformat.com", "highperformancedformats.com",
        "profitablegatecpm.com", "profitablecpmgate.com", "effectivegatecpm.com", "realsrv.com", "pemsrv.com",
        "tsyndicate.com", "dtscout.com", "zeydoo.com", "galaksion.com", "richads.com", "pushground.com",
        "evadav.com", "rollerads.com", "adspyglass.com", "adskeeper.com", "adskeeper.co.uk", "admaven.com",
        "ad-maven.com", "bebi.com", "betteradsystem.com", "clickaine.com", "dolohen.com", "acint.net",
        "adxadserv.com", "trafficstars.com", "tsyndicate.com", "magsrv.com", "mnaspm.com", "ylx-1.com",
        "ylx-2.com", "ylx-3.com", "ylx-4.com", "displayvertising.com", "go.oclasrv.com", "oclasrv.com",
        "notifpush.com", "push-sdk.com", "pushnami.com", "lunio.net", "whos.amung.us", "histats.com",
        "statcounter.com", "yandex.ru/ads", "an.yandex.ru", "mc.yandex.ru", "adriver.ru", "begun.ru",
        "bidvertiser.com", "infolinks.com", "chitika.com", "clicksor.com", "adbuff.com", "admixer.net",
        "adhitzads.com", "plugrush.com", "ero-advertising.com", "adxpansion.com", "streamtape-ads.com",
        "cpmstar.com", "cpmrocket.com", "adtng.com", "aclktrk.com", "clkrev.com", "clksite.com",
        "jads.co", "jsdelivr-ads.com", "adf.ly", "shorte.st", "ouo.io", "linkvertise.com", "bc.vc",
        "vidazoo.com", "ezoic.net", "ezojs.com", "mediavine.com", "adthrive.com", "raptive.com",
        "fundingchoicesmessages.google.com", "quantcast.com", "cookielaw.org",
    )

    /** pedaços de endereço que só aparecem em propaganda (vale pra sites de fora) */
    private val PATHS = listOf(
        "/ads/", "/ad/", "/adv/", "/advert", "/adserver", "/adframe", "/adbanner", "/banners/ad",
        "preroll", "pre-roll", "midroll", "/vast", "vast.xml", "vpaid", "ima3", "adtag", "/pagead/",
        "/popunder", "/popup.js", "pop.js", "/clickunder", "prebid", "/gampad/", "show_ads", "adsbygoogle",
    )

    private fun hostOf(url: String): String = try { URI(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }

    /** "a.b.exemplo.com.br" -> "exemplo.com.br" (pra saber se é do mesmo site) */
    fun site(host: String): String {
        val p = host.split('.')
        if (p.size <= 2) return host
        val two = p.takeLast(2).joinToString(".")
        val secondLevel = setOf("com.br", "net.br", "org.br", "co.uk", "com.au", "com.ar", "com.mx", "co.jp", "gov.br", "blog.br")
        return if (two in secondLevel && p.size >= 3) p.takeLast(3).joinToString(".") else two
    }

    fun isAdHost(host: String): Boolean {
        var h = host.lowercase()
        while (true) {
            if (h in HOSTS) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
            if (!h.contains('.')) return h in HOSTS
        }
    }

    /** decide se um pedido da página deve ser cortado */
    fun shouldBlock(url: String, pageUrl: String): Boolean {
        if (!url.startsWith("http")) return false
        val host = hostOf(url)
        if (host.isEmpty()) return false
        if (isAdHost(host)) return true
        val pageHost = hostOf(pageUrl)
        if (pageHost.isNotEmpty() && site(host) == site(pageHost)) return false // não mexe no próprio site
        val path = url.lowercase().substringAfter(host)
        return PATHS.any { path.contains(it) }
    }

    /** CSS que some com os espaços de anúncio */
    private const val CSS = """
ins.adsbygoogle,.adsbygoogle,[id^="google_ads_"],[id^="div-gpt-ad"],[data-google-query-id],
iframe[src*="doubleclick.net"],iframe[src*="googlesyndication"],iframe[id^="google_ads_iframe"],
iframe[src*="adsterra"],iframe[src*="exoclick"],iframe[src*="juicyads"],iframe[src*="popads"],
[id^="taboola-"],.trc_rbox_container,.OUTBRAIN,[data-widget-id^="outbrain"],[id^="mgid_"],[id*="ScriptRoot"],
.ad-container,.ad-slot,.ad-banner,.ad_banner,.adbanner,.banner-ad,.ads-banner,.advertisement,.advertising,
.adsbox,.ad-box,.adBox,.adwrapper,.ad-wrapper,.ad-placeholder,.sponsored-content,.sponsor-box,
#ad,#ads,#ad-banner,#adbanner,#banner-ad,#publicidade,.publicidade,.anuncio,.anuncios,.ads-area,
[aria-label="Advertisement"],[aria-label="Publicidade"],[aria-label="Anúncio"],
div[id^="adsterra"],div[class*="popunder"],a[href*="adsterra"],a[href*="/aff_c?"],a[href*="popads"],
div[id^="ad_position"],div[class^="adunit"],div[class*=" adunit"]
{display:none!important;visibility:hidden!important;height:0!important;min-height:0!important;}
"""

    /**
     * Script que roda em cada página (e dentro dos quadros):
     * aplica o CSS, tira quadros de propaganda e camadas invisíveis que roubam o toque.
     */
    val SCRIPT: String = """
(function(){
  try{ if(window.TP && TP.adsOn && !TP.adsOn()) return; }catch(e){}
  if(window.__tpShield) return; window.__tpShield=1;
  var bad=${HOSTS.filter { !it.contains('/') }.joinToString(",", "[", "]") { "\"$it\"" }};
  function isBad(u){ try{ var h=new URL(u,location.href).hostname; for(var i=0;i<bad.length;i++){var b=bad[i]; if(h===b||h.endsWith('.'+b)) return true;} }catch(e){} return false; }
  function css(){ if(document.getElementById('__tpcss')) return; var s=document.createElement('style'); s.id='__tpcss';
    s.textContent=${'$'}{JSON.stringify(CSS_PLACEHOLDER)}; (document.head||document.documentElement).appendChild(s); }
  var count=0;
  function bump(){ count++; try{ TP.blocked(); }catch(e){} }
  function vw(){return Math.max(document.documentElement.clientWidth,window.innerWidth||0);}
  function vh(){return Math.max(document.documentElement.clientHeight,window.innerHeight||0);}
  function clean(){
    css();
    var fr=document.querySelectorAll('iframe,embed,object');
    for(var i=0;i<fr.length;i++){ var f=fr[i]; var src=f.src||f.data||''; if(src&&isBad(src)){ f.remove(); bump(); } }
    // camadas invisíveis por cima do vídeo que abrem propaganda
    var all=document.querySelectorAll('a,div,span,ins');
    var W=vw(),H=vh();
    for(var j=0;j<all.length;j++){
      var el=all[j]; if(el.__tpok) continue;
      var st=getComputedStyle(el); if(st.position!=='fixed'&&st.position!=='absolute') { el.__tpok=1; continue; }
      var r=el.getBoundingClientRect(); if(r.width<W*0.5||r.height<H*0.3) { el.__tpok=1; continue; }
      if(el.querySelector('video,iframe[allowfullscreen]')) { el.__tpok=1; continue; }
      var z=parseInt(st.zIndex)||0;
      var see=parseFloat(st.opacity);
      var bg=st.backgroundColor; var clear=(bg==='rgba(0, 0, 0, 0)'||bg==='transparent')&&!el.textContent.trim()&&!el.querySelector('img,svg,canvas');
      var link=el.tagName==='A'&&el.target==='_blank'&&el.href&&(new URL(el.href,location.href).hostname!==location.hostname);
      if((z>=100&&(see<0.05||clear)) || (link&&z>=10) || (el.href&&isBad(el.href))){ el.remove(); bump(); }
      else el.__tpok=1;
    }
  }
  // links com target=_blank pra propaganda
  document.addEventListener('click',function(e){
    var a=e.target&&e.target.closest?e.target.closest('a'):null;
    if(a&&a.href&&isBad(a.href)){ e.preventDefault(); e.stopPropagation(); bump(); }
  },true);
  var t=null;
  function later(){ if(t) return; t=setTimeout(function(){t=null; try{clean();}catch(e){}},300); }
  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',clean); else clean();
  try{ new MutationObserver(later).observe(document.documentElement,{childList:true,subtree:true}); }catch(e){}
  setTimeout(clean,1500); setTimeout(clean,4000);
})();
""".replace("\${JSON.stringify(CSS_PLACEHOLDER)}", jsString(CSS.trim().replace("\n", "")))

    private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
