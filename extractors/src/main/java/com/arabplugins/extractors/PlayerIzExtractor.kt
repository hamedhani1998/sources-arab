package com.arabplugins.extractors

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class PlayerIzExtractor : ExtractorApi() {
    override var name = "PlayerIz"
    override var mainUrl = "https://playeriz.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("PlayerIz", "getUrl url=$url referer=$referer")
            val ref = safeReferer(referer)
            val res = app.get(url, referer = ref)
            val html = res.text
            val doc = res.document
            Log.d("PlayerIz", "fetch htmlLen=${html.length} head=${html.take(160).replace("\n", " ")}")
            Log.d("PlayerIz", "challenge? cf=${html.contains("cf-challenge", true) || html.contains("just a moment", true) || html.contains("captcha", true)} cloudflare=${html.contains("cloudflare", true)}")

            // Method 0: Decode packed/obfuscated eval JS (used by playeriz.com)
            val decoded = unpackEval(html)
            Log.d("PlayerIz", "unpackEval decoded=${decoded != null} decLen=${decoded?.length}")
            if (decoded != null) {
                val m3u8Urls = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(decoded)
                var n = 0
                for (m in m3u8Urls) {
                    Log.d("PlayerIz", "m3u8[${n}]=${m.value}")
                    n++
                    callback(newExtractorLink(
                        source = name, name = name, url = m.value,
                        type = ExtractorLinkType.M3U8
                    ) { this.referer = ref })
                    return
                }
                Log.d("PlayerIz", "decoded m3u8 count=$n mp4Count=${Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(decoded).count()}")
                val mp4Urls = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(decoded)
                for (m in mp4Urls) {
                    callback(newExtractorLink(
                        source = name, name = name, url = decodeUrl(m.value),
                        type = ExtractorLinkType.VIDEO
                    ) { this.referer = ref })
                    return
                }
            }

            // Method 1: Look for video_url in plain scripts
            val allScript = doc.select("script").joinToString("\n") { it.data() }
            val videoUrlMatch = Regex("""video_url\s*[:=]\s*['"]([^'"]+)['"]""").find(allScript)
            if (videoUrlMatch != null) {
                callback(newExtractorLink(
                    source = name, name = name, url = decodeUrl(videoUrlMatch.groupValues[1]),
                    type = ExtractorLinkType.VIDEO
                ) { this.referer = ref })
                return
            }

            // Method 2: m3u8 URL in plain text
            val m3u8Match = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(allScript)
            if (m3u8Match != null) {
                callback(newExtractorLink(
                    source = name, name = name, url = m3u8Match.groupValues[1],
                    type = ExtractorLinkType.M3U8
                ) { this.referer = ref })
                return
            }

            // Method 3: mp4 URL in plain text
            val mp4Match = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(allScript)
            if (mp4Match != null) {
                callback(newExtractorLink(
                    source = name, name = name, url = mp4Match.groupValues[1],
                    type = ExtractorLinkType.VIDEO
                ) { this.referer = ref })
                return
            }

            // Method 4: video source tags
            doc.select("video source").forEach { source ->
                val srcUrl = source.attr("src")
                if (srcUrl.isNotBlank() && (srcUrl.contains(".mp4") || srcUrl.contains(".m3u8"))) {
                    val type = if (srcUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(newExtractorLink(
                        source = name, name = name, url = srcUrl, type = type
                    ) { this.referer = ref })
                    return
                }
            }

            // Method 5: iframe delegation
            val iframe = doc.selectFirst("iframe[src]")
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.isNotBlank()) {
                    Log.d("PlayerIz", "delegating to iframe $iframeUrl")
                    loadExtractor(iframeUrl, ref, subtitleCallback, callback)
                    return
                }
            }
            Log.d("PlayerIz", "**** NO LINKS: all methods failed, htmlLen=${html.length}")
        } catch (e: Exception) {
            Log.d("PlayerIz", "getUrl EXCEPTION ${e::class.simpleName}: ${e.message}")
        }
    }

    /**
     * Decode `eval(function(p,a,c,k,e,d){...}('packed',base,count,'dict'))`.
     *
     * Uses an index/bracket-based parser instead of a single big regex — the
     * regex form causes a StackOverflowError in Android's regex engine on the
     * large packed strings served by playeriz.com, which made playback fail.
     */
    private fun unpackEval(html: String): String? {
        return try {
            val marker = "eval(function(p,a,c,k,e,d)"
            val start = html.indexOf(marker)
            if (start < 0) {
                Log.d("PlayerIz", "unpack: marker NOT found")
                return null
            }
            Log.d("PlayerIz", "unpack: marker found at $start")

            // Opening paren of the whole eval(...) is the '(' right after "eval"
            val open = start + 4
            // Walk to the matching close paren, skipping over quoted strings,
            // so any ( or ) inside the packed string/dict is ignored.
            var depth = 0
            var inString = false
            var end = -1
            var i = open
            while (i < html.length) {
                val ch = html[i]
                if (inString) {
                    if (ch == '\\') i++                 // skip escaped char
                    else if (ch == '\'') inString = false
                } else {
                    when (ch) {
                        '\'' -> inString = true
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) { end = i; break }
                        }
                    }
                }
                i++
            }
            if (end < 0) { Log.d("PlayerIz", "unpack: no matching close paren"); return null }

            // The call args sit in the final "(...)" group, right after "...}("
            val callText = html.substring(start, end + 1)
            val funcEnd = callText.lastIndexOf("}(")
            if (funcEnd < 0) { Log.d("PlayerIz", "unpack: no '}(' boundary"); return null }
            val argsStr = callText.substring(funcEnd + 2, callText.length - 1)

            val args = splitTopLevel(argsStr)
            if (args.size < 4) { Log.d("PlayerIz", "unpack: args too few=${args.size}"); return null }

            val packed = unescapeEval(args[0])
            val base = args[1].trim().toInt()
            val count = args[2].trim().toInt()
            val dict = unescapeEval(args[3]).split('|')
            Log.d("PlayerIz", "unpack: packedLen=${packed.length} base=$base count=$count dictSize=${dict.size}")

            // Unpacking algorithm: for each index from count-1 down to 0,
            // convert index to base-N string, replace \b{baseN}\b with dict[index]
            var result = packed
            for (j in count - 1 downTo 0) {
                if (j >= dict.size || dict[j].isEmpty()) continue
                val key = j.toString(base)
                result = result.replace(Regex("\\b" + Regex.escape(key) + "\\b"), dict[j])
            }
            Log.d("PlayerIz", "unpack: done resultLen=${result.length}")
            result
        } catch (e: Exception) {
            Log.d("PlayerIz", "unpack: EXCEPTION ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    /** Split a comma-separated arg list on top-level commas (not inside single quotes). */
    private fun splitTopLevel(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inString = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (inString) {
                cur.append(ch)
                if (ch == '\\' && i + 1 < s.length) cur.append(s[++i])
                else if (ch == '\'') inString = false
            } else {
                when (ch) {
                    '\'' -> { inString = true; cur.append(ch) }
                    ',' -> { out.add(cur.toString()); cur.setLength(0) }
                    else -> cur.append(ch)
                }
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    /** Strip surrounding quotes and unescape \' and \\ separators. */
    private fun unescapeEval(q: String): String {
        var s = q
        if (s.length >= 2 && s.startsWith("'") && s.endsWith("'"))
            s = s.substring(1, s.length - 1)
        return s.replace("\\'", "'").replace("\\\\", "\\")
    }

    /** Pin referer to the ASCII-safe origin (scheme://host). Arabic paths in the
     *  referer crash the HTTP stack (IllegalArgumentException) before any request
     *  is made, which made getUrl return no links. scheme://host is enough for
     *  playeriz.com / s1.playiri.com. */
    private fun safeReferer(r: String?): String {
        val s = r ?: mainUrl
        return Regex("https?://[^/]+").find(s)?.value ?: mainUrl
    }

    private fun decodeUrl(url: String): String {
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
}