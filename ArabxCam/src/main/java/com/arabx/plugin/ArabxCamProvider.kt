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
            Log.d(TAG, "getMainPage url=$url")
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim() ?: a.attr("title")
                    val poster = extractPoster(item)
                    val rating = item.selectFirst("div.rating")?.text()?.trim()?.replace("%", "")
                    newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = poster
                        if (!rating.isNullOrBlank()) this.score = Score.from(rating, 100)
                    }
                } catch (_: Exception) { null }
            }
            Log.d(TAG, "getMainPage items=${items.size}")
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
                    val poster = extractPoster(item)
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

            // Diagnostic: log page structure to find where the real player is
            Log.d(TAG, "iframes: ${doc.select("iframe[src]").joinToString(" | ") { it.attr("src") }}")
            Log.d(TAG, "embeds: ${doc.select("embed[src]").joinToString(" | ") { it.attr("src") }}")
            Log.d(TAG, "og:video: ${doc.select("meta[property=og:video], meta[property='og:video:url'], meta[property=og:video:secure_url]").joinToString(" | ") { it.attr("content") }}")
            Log.d(TAG, "tw:player: ${doc.select("meta[name='twitter:player']").joinToString(" | ") { it.attr("content") }}")
            Log.d(TAG, "playerEls: ${doc.select("[class*=player], [id*=player], .player-wrap, .video-player, #player_wrapper, #player").joinToString(" | ") { it.tagName() + ":" + (it.attr("class") ?: it.attr("id")) }}")
            Log.d(TAG, "ptitle: ${doc.select(".player, .video-info, .video-title, h1").firstOrNull()?.text()?.take(60)}")

            // Method 1: flashvars on the detail page itself (KVS pages embed flashvars inline)
            val flashScripts = doc.select("script").filter { it.html().contains("flashvars") }
            Log.d(TAG, "flashvars scripts=${flashScripts.size}")
            flashScripts.forEach { element ->
                val script = element.html()
                if (script.contains("flashvars")) {
                    listOf(
                        rgx(script, "video_url") to (rgx(script, "video_url_text") ?: "360p"),
                        rgx(script, "video_alt_url") to (rgx(script, "video_alt_url_text") ?: "480p"),
                        rgx(script, "video_alt_url2") to (rgx(script, "video_alt_url2_text") ?: "720p"),
                        rgx(script, "video_hd_url") to (rgx(script, "video_hd_url_text") ?: "1080p")
                    ).forEach { (url, q) ->
                        if (url != null && url.isNotBlank() && isWorkingGetFile(url)) {
                            val quality = if (q.matches(Regex("\\d+"))) "${q}p" else q
                            lnk(url, quality, callback)
                            found = true
                        }
                    }
                }
            }

            // Method 2a: cookie destroyed get_file links (actual video host) — skip for now,
            // handled via embed page below.

            // Method 2: iframe embed (not limited to div.embed-wrap) / twitter:player →
            //      fetch the embed page, parse its flashvars, emit get_file links.
            //      local /embed/<id> -> KVS player (video_url/video_alt_url/video_hd_url)
            //      remote embed (playeriz etc.) -> delegate to PlayerIzExtractor.
            val embedUrl = doc.select("iframe[src]").map { it.attr("src") }
                .firstOrNull { it.isNotBlank() }
                ?: doc.selectFirst("meta[name='twitter:player']")?.attr("content")
            if (embedUrl != null) {
                Log.d(TAG, "embedUrl=$embedUrl")
                val resolved = fixUrl(embedUrl)
                if (resolved.contains("/embed/") && resolved.contains(mainUrl.removePrefix("https://").substringBefore("."))) {
                    // local KVS embed page (https://www.arabx.cam/embed/<id>)
                    try {
                        val embedDoc = app.get(resolved, referer = data).document
                        val eScripts = embedDoc.select("script").filter { it.html().contains("flashvars") }
                        Log.d(TAG, "embed flashvars=${eScripts.size}")
                        eScripts.forEach { element ->
                            val script = element.html()
                            if (script.contains("flashvars")) {
                                listOf(
                                    rgx(script, "video_url") to (rgx(script, "video_url_text") ?: "360p"),
                                    rgx(script, "video_alt_url") to (rgx(script, "video_alt_url_text") ?: "480p"),
                                    rgx(script, "video_alt_url2") to (rgx(script, "video_alt_url2_text") ?: "720p"),
                                    rgx(script, "video_hd_url") to (rgx(script, "video_hd_url_text") ?: "1080p")
                                ).forEach { (url, q) ->
                                    if (url != null && url.isNotBlank() && isWorkingGetFile(url)) {
                                        val quality = if (q.matches(Regex("\\d+"))) "${q}p" else q
                                        lnk(url, quality, callback)
                                        found = true
                                    }
                                }
                            }
                        }
                        if (found) return true
                    } catch (e: Exception) {
                        Log.d(TAG, "embed fetch exception: ${e.message}")
                    }
                } else {
                    // remote embed (playeriz etc.) → extractor
                    Log.d(TAG, "delegating to extractor: $resolved")
                    loadExtractor(resolved, data, subtitleCallback, callback)
                    return true
                }
            } else {
                Log.d(TAG, "no iframe/twitter:player on detail page")
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
            // Scan script contents only (not full HTML) to avoid picking up broken links.
            val scriptTexts = doc.select("script").joinToString("\n") { it.data() }
            val directUrls = Regex("""https?://[^\s"'<>]+(?:\.mp4|m3u8)[^\s"'<>]*""")
                .findAll(scriptTexts).map { it.value }.distinct()
                .filter { !it.contains("get_file") }
            Log.d(TAG, "direct mp4/m3u8 in scripts=${directUrls.count()}")
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

    /** A get_file link works when it carries the md5 hash (get_file/1/<md5>/3000/...) — those 302-redirect to max.arabx.cam MP4s. */
    private fun isWorkingGetFile(url: String): Boolean {
        return !url.contains("get_file") || Regex("""get_file/1/[a-f0-9]{32}/3000/""").containsMatchIn(url)
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

    private fun fixUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$mainUrl$url"
        else -> url
    }

    private fun extractPoster(item: org.jsoup.nodes.Element): String? {
        // Try multiple img selectors and attributes
        val img = item.selectFirst("img.thumb")
            ?: item.selectFirst("img[data-original]")
            ?: item.selectFirst("img[data-src]")
            ?: item.selectFirst("img.lazy")
            ?: item.selectFirst("img")
        if (img == null) {
            Log.d(TAG, "extractPoster: no img found in item")
            return null
        }
        val poster = listOf("data-original", "data-src", "data-webp", "src")
            .firstNotNullOfOrNull { attr ->
                img.attr(attr).takeIf { it.isNotBlank() && !it.contains("placeholder") && !it.contains("data:image") }
            }
        if (poster.isNullOrBlank()) {
            Log.d(TAG, "extractPoster: all attrs blank. HTML=${item.html().take(200)}")
            return null
        }
        return fixUrl(poster)
    }
}