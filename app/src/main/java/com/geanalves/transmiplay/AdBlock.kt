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

    /** espaços de anúncio que o escudo esconde (o site continua achando que estão lá) */
    val SELECTORS = listOf(
        "ins.adsbygoogle", ".adsbygoogle", "[id^=\"google_ads_\"]", "[id^=\"div-gpt-ad\"]", "[data-google-query-id]",
        "iframe[src*=\"doubleclick.net\"]", "iframe[src*=\"googlesyndication\"]", "iframe[id^=\"google_ads_iframe\"]",
        "iframe[src*=\"adsterra\"]", "iframe[src*=\"exoclick\"]", "iframe[src*=\"juicyads\"]", "iframe[src*=\"popads\"]",
        "[id^=\"taboola-\"]", ".trc_rbox_container", ".OUTBRAIN", "[data-widget-id^=\"outbrain\"]", "[id^=\"mgid_\"]",
        "[id*=\"ScriptRoot\"]", ".ad-container", ".ad-slot", ".ad-banner", ".ad_banner", ".adbanner", ".banner-ad",
        ".ads-banner", ".advertisement", ".advertising", ".adsbox", ".ad-box", ".adBox", ".adwrapper", ".ad-wrapper",
        ".ad-placeholder", ".sponsored-content", ".sponsor-box", "#ad", "#ads", "#ad-banner", "#adbanner", "#banner-ad",
        "#publicidade", ".publicidade", ".anuncio", ".anuncios", ".ads-area", "[aria-label=\"Advertisement\"]",
        "[aria-label=\"Publicidade\"]", "[aria-label=\"Anúncio\"]", "div[id^=\"adsterra\"]", "div[class*=\"popunder\"]",
        "a[href*=\"adsterra\"]", "a[href*=\"/aff_c?\"]", "a[href*=\"popads\"]", "div[id^=\"ad_position\"]",
        "div[class^=\"adunit\"]", "div[class*=\" adunit\"]",
    )

    @Volatile private var cached: String? = null

    /** script que roda em cada página (e dentro dos quadros) */
    fun script(ctx: android.content.Context): String =
        cached ?: build(ctx.assets.open("shield.js").bufferedReader().readText()).also { cached = it }

    fun build(template: String): String {
        val hosts = HOSTS.filter { !it.contains('/') }.joinToString(",", "[", "]") { jsString(it) }
        return template.replace("__HOSTS__", hosts).replace("__SEL__", jsString(SELECTORS.joinToString(",")))
    }

    /**
     * Em vez de dar erro (que o site percebe), devolve uma resposta falsa e vazia:
     * script que "carrega", imagem de 1 pixel, página em branco.
     */
    fun fakeResponse(url: String, accept: String?): android.webkit.WebResourceResponse {
        val path = url.lowercase().substringBefore('?')
        val acc = accept?.lowercase() ?: ""
        val (mime, body) = when {
            path.endsWith(".js") || acc.contains("javascript") || path.contains("adsbygoogle") || path.contains("gpt") ->
                "application/javascript" to "/* tp */".toByteArray()
            path.endsWith(".gif") || path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".webp") || acc.startsWith("image/") -> "image/gif" to GIF
            path.endsWith(".css") || acc.contains("text/css") -> "text/css" to ByteArray(0)
            path.endsWith(".json") || acc.contains("json") -> "application/json" to "{}".toByteArray()
            path.contains("vast") || path.endsWith(".xml") -> "text/xml" to "<VAST version=\"3.0\"></VAST>".toByteArray()
            else -> "text/html" to ByteArray(0)
        }
        return android.webkit.WebResourceResponse(mime, "utf-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            java.io.ByteArrayInputStream(body))
    }

    /** gif transparente de 1 pixel */
    private val GIF = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00,
        0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
    )

    fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
