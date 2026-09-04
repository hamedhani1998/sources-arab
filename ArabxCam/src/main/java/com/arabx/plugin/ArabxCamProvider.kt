package com.arabx.plugin

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ArabxCamProvider : MainAPI() {
    private val TAG = "ArabxCam"
    override var name = "ArabX"
    override var mainUrl = "https://www.arabx.cam"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest-updates/" to "احدث الافلام",
        "top-rated/" to "افضل الافلام",
        "most-popular/" to "الاعلى مشاهدة",
        "categories/سكس-مترجم/" to "مترجم",
        "categories/سكس-امهات-مترجم/" to "أمهات",
        "categories/سكس-محارم/" to "محارم",
        "categories/سكس-اخوات/" to "اخوات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim() ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb")?.let {
                        it.attr("data-original").ifBlank { it.attr("data-webp").ifBlank { it.attr("src") } }
                    }
                    val rating = item.selectFirst("div.rating")?.text()?.trim()?.replace("%", "")
                    newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = poster
                        if (!rating.isNullOrBlank()) this.score = Score.from(rating, 100)
                    }
                } catch (_: Exception) { null }
            }
            newHomePageResponse(request.name, items)
        } catch (_: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/search/?q=$query", referer = mainUrl).document
            doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim() ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb")?.let {
                        it.attr("data-original").ifBlank { it.attr("data-webp").ifBlank { it.attr("src") } }
                    }
                    newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1.htitle")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.map { it.trim() }?.take(6)
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } catch (_: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            Log.d(TAG, "loadLinks data=$data")
            val doc = app.get(data, referer = mainUrl).document
            var found = false

            // Method 1: flashvars (for KVS-based sites)
            val flashScripts = doc.select("script").filter { it.html().contains("flashvars") }
            Log.d(TAG, "flashvars scripts=${flashScripts.size}")
            flashScripts.forEach { element ->
                val script = element.html()
                if (script.contains("flashvars")) {
                    val v1 = rgx(script, "video_url")
                    val v2 = rgx(script, "video_alt_url")
                    val v3 = rgx(script, "video_alt_url2")
                    val q1 = rgx(script, "video_url_text") ?: "360p"
                    val q2 = rgx(script, "video_alt_url_text") ?: "480p"
                    val q3 = rgx(script, "video_alt_url2_text") ?: "720p"
                    v1?.let { lnk(it, q1, callback); found = true }
                    v2?.let { lnk(it, q2, callback); found = true }
                    v3?.let { lnk(it, q3, callback); found = true }
                }
            }
            if (found) return true

            // Method 2: iframe embed → delegate to PlayerIzExtractor (handles obfuscated eval JS)
            val iframe = doc.selectFirst("div.embed-wrap iframe")
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                Log.d(TAG, "iframe src=$iframeUrl")
                if (iframeUrl.isNotBlank()) {
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                    return true
                }
            } else {
                Log.d(TAG, "no div.embed-wrap iframe")
            }

            // Method 3: HTML5 video sources
            val vidSrcs = doc.select("video source")
            Log.d(TAG, "video source tags=${vidSrcs.size}")
            vidSrcs.forEach { src ->
                val srcUrl = src.attr("src")
                if (srcUrl.isNotBlank()) {
                    val quality = when {
                        srcUrl.contains("1080p") -> "1080p"
                        srcUrl.contains("720p") -> "720p"
                        srcUrl.contains("480p") -> "480p"
                        srcUrl.contains("360p") -> "360p"
                        else -> "360p"
                    }
                    val type = if (srcUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(newExtractorLink(
                        source = name, name = name, url = srcUrl, type = type
                    ) { this.referer = mainUrl; this.quality = getQualityFromName(quality) })
                    found = true
                }
            }
            if (found) return true

            // Method 4: Direct mp4/m3u8 URLs in page text (max.arabx.cam / other hosts)
            val allText = doc.select("script").joinToString("\n") { it.data() } +
                "\n" + doc.html()
            val directUrls = Regex("""https?://[^\s"'<>]+(?:\.mp4|m3u8)[^\s"'<>]*""")
                .findAll(allText).map { it.value }.distinct()
            Log.d(TAG, "direct mp4/m3u8 in text=${directUrls.count()}")
            directUrls.forEach { url ->
                val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val quality = when {
                    url.contains(".m3u8") -> null
                    else -> Regex("""([0-9]{3,4}[piKk])""").find(url)?.groupValues?.get(1)
                        ?: Regex("""(?:360|480|720|1080)p""").find(url)?.groupValues?.get(0)
                        ?: "360p"
                }
                val qName = quality ?: ""
                callback(newExtractorLink(
                    source = name, name = name, url = url, type = type
                ) {
                    this.referer = mainUrl
                    if (qName.isNotBlank()) this.quality = getQualityFromName(qName)
                })
                found = true
            }
            Log.d(TAG, "loadLinks done found=$found")
            found
        } catch (e: Exception) { Log.d(TAG, "loadLinks EXCEPTION ${e::class.simpleName}: ${e.message}"); false }
    }

    private fun rgx(script: String, key: String): String? {
        val match = Regex("""$key\s*[:=]\s*['"]([^'"]+)['"]""").find(script) ?: return null
        return match.groupValues[1].ifBlank { null }
    }

    private fun cln(url: String): String {
        val decoded = when {
            url.startsWith("function/0/") -> {
                try {
                    val base64 = url.removePrefix("function/0/")
                    Base64.decode(base64, Base64.DEFAULT).toString(Charsets.UTF_8)
                } catch (_: Exception) { url.removePrefix("function/0/") }
            }
            else -> url
        }
        return when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("https/") -> "https://${decoded.removePrefix("https/")}"
            else -> decoded
        }
    }

    private suspend fun lnk(url: String, quality: String, callback: (ExtractorLink) -> Unit) {
        callback(newExtractorLink(
            source = name, name = name, url = cln(url), type = ExtractorLinkType.VIDEO
        ) { this.referer = mainUrl; this.quality = getQualityFromName(quality) })
    }
}