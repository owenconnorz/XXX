package com.owencz1998

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

class PornHubProvider : MainAPI() {
    private val globalTvType = TvType.NSFW
    override var mainUrl = "https://www.pornhub.com"
    override var name = "PornHub"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val cookies = mapOf(
        "hasVisited" to "1",
        "accessAgeDisclaimerPH" to "1",
        "platform" to "pc"
    )

    private val htmlHeaders = mapOf(
        "Referer" to mainUrl,
        "User-Agent" to UA,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "text/html"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/video?o=mr&hd=1&ajax=1&page="           to "Recently Featured",
        "$mainUrl/video?o=tr&t=w&hd=1&ajax=1&page="       to "Top Rated",
        "$mainUrl/video?o=mv&t=w&hd=1&ajax=1&page="       to "Most Viewed",
        "$mainUrl/video?o=ht&t=w&hd=1&ajax=1&page="       to "Hottest",
        "$mainUrl/video?p=professional&hd=1&ajax=1&page=" to "Professional",
        "$mainUrl/video?o=lg&hd=1&ajax=1&page="           to "Longest",
        "$mainUrl/video?p=homemade&hd=1&ajax=1&page="     to "Homemade",
        "$mainUrl/video?o=cm&t=w&hd=1&ajax=1&page="       to "Newest",
        "$mainUrl/video?c=35&ajax=1&page="                to "Anal",
        "$mainUrl/video?c=27&ajax=1&page="                to "Lesbian",
        "$mainUrl/video?c=98&ajax=1&page="                to "Arab",
        "$mainUrl/video?c=1&ajax=1&page="                 to "Asian",
        "$mainUrl/video?c=89&ajax=1&page="                to "Babysitter",
        "$mainUrl/video?c=6&ajax=1&page="                 to "BBW",
        "$mainUrl/video?c=141&ajax=1&page="               to "Behind The Scenes",
        "$mainUrl/video?c=4&ajax=1&page="                 to "Big Ass",
        "$mainUrl/video?c=7&ajax=1&page="                 to "Big Dick",
        "$mainUrl/video?c=8&ajax=1&page="                 to "Big Tits",
        "$mainUrl/video?c=13&ajax=1&page="                to "Blowjob",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + (if (page > 0) page else 1)
        val doc = app.get(url, cookies = cookies, headers = htmlHeaders).document

        val cards = doc.select("li.pcVideoListItem, li.videoBox, ul.videos li.videoBox")
        if (cards.isEmpty()) throw ErrorLoadingException("No homepage data found.")

        val list = cards.mapNotNull { el ->
            val a = el.selectFirst("a[href][title], a[href][data-title], a.js-link[href]") ?: return@mapNotNull null
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.attr("data-title") }.ifBlank { a.text().trim() }
            val imgEl = el.selectFirst("img[data-thumb_url], img[data-mediumthumb], img[data-src], img[src]")
            val poster = imgEl?.attr("data-thumb_url")
                ?: imgEl?.attr("data-mediumthumb")
                ?: imgEl?.attr("data-src")
                ?: imgEl?.attr("src")
            newMovieSearchResponse(title, href, globalTvType) { posterUrl = fixUrlNull(poster) }
        }

        return newHomePageResponse(HomePageList(request.name, list, isHorizontalImages = true), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val url = "$mainUrl/video/search?search=$q&ajax=1&page=$page"
            val doc = app.get(url, cookies = cookies, headers = htmlHeaders).document
            val cards = doc.select("li.pcVideoListItem, li.videoBox, ul.videos li.videoBox")
            if (cards.isEmpty()) break
            cards.forEach { el ->
                val a = el.selectFirst("a[href][title], a[href][data-title], a.js-link[href]") ?: return@forEach
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                val title = a.attr("title").ifBlank { a.attr("data-title") }.ifBlank { a.text().trim() }
                val imgEl = el.selectFirst("img[data-thumb_url], img[data-mediumthumb], img[data-src], img[src]")
                val poster = imgEl?.attr("data-thumb_url")
                    ?: imgEl?.attr("data-mediumthumb")
                    ?: imgEl?.attr("data-src")
                    ?: imgEl?.attr("src")
                out += newMovieSearchResponse(title, href, globalTvType) { posterUrl = fixUrlNull(poster) }
            }
        }
        if (out.isEmpty()) throw ErrorLoadingException("No search results.")
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, cookies = cookies, headers = mapOf("Referer" to mainUrl, "User-Agent" to UA)).document
        val title = doc.selectFirst(".title span")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val poster = fixUrlNull(
            doc.selectFirst("div.video-wrapper .mainPlayerDiv img")?.attr("src")
                ?: doc.selectFirst("head meta[property=og:image]")?.attr("content")
        )
        val tags = doc.select("div.categoriesWrapper a").map { it.text().trim().replace(", ", "") }
        val actors = doc
            .select("div.video-wrapper div.video-info-row.userRow div.userInfo div.usernameWrap a")
            .map { it.text() }

        val related = doc.select("li.fixedSizeThumbContainer").map {
            val rTitle = it.selectFirst("div.phimage a")?.attr("title") ?: ""
            val rUrl = fixUrl(it.selectFirst("div.phimage a")?.attr("href").toString())
            val rPoster = fixUrl(it.selectFirst("div.phimage img.js-videoThumb")?.attr("src").toString())
            newMovieSearchResponse(rTitle, rUrl) { posterUrl = rPoster }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            plot = title
            this.tags = tags
            addActors(actors)
            recommendations = related
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(url = data, cookies = cookies, headers = mapOf("Referer" to mainUrl, "User-Agent" to UA)).document

        val scriptsJoined = doc.select("script").joinToString("\n") { it.data() }
        val mediaArrayStr =
            Regex("\"mediaDefinitions\"\\s*:\\s*(\\[.*?\\])", RegexOption.DOT_MATCHES_ALL)
                .find(scriptsJoined)?.groupValues?.get(1)
                ?: run {
                    val legacy = doc.selectXpath("//script[contains(text(),'flashvars')]")
                        .firstOrNull()?.data()
                        ?.substringAfter("=")?.substringBefore(";")
                        ?: ""
                    if (legacy.isNotEmpty())
                        JSONObject(legacy).optJSONArray("mediaDefinitions")?.toString()
                    else null
                }
        if (mediaArrayStr.isNullOrEmpty()) return false

        val mediaDefinitions = org.json.JSONArray(mediaArrayStr)
        for (i in 0 until mediaDefinitions.length()) {
            val obj = mediaDefinitions.getJSONObject(i)
            var mUrl = obj.optString("videoUrl")
            if (mUrl.isNullOrEmpty()) continue

            if (mUrl.endsWith(".json")) {
                runCatching {
                    val j = JSONObject(app.get(mUrl, headers = mapOf("User-Agent" to UA)).text)
                    mUrl = j.optString("videoUrl", mUrl)
                }
            }

            val qStr = obj.optString("quality")
            val links = mutableListOf<ExtractorLink>()
            M3u8Helper().m3u8Generation(M3u8Helper.M3u8Stream(mUrl), true).amap { stream ->
                links += newExtractorLink(
                    source = name,
                    name = name,
                    url = stream.streamUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    quality = getQualityFromName(Regex("(\\d+)").find(qStr)?.groupValues?.getOrNull(1))
                    referer = mainUrl
                }
            }
            links.forEach(callback)
        }
        return true
    }

    private fun fetchImgUrl(img: Element?): String? =
        try {
            img?.attr("src")
                ?: img?.attr("data-src")
                ?: img?.attr("data-mediabook")
                ?: img?.attr("alt")
                ?: img?.attr("data-mediumthumb")
                ?: img?.attr("data-thumb_url")
        } catch (_: Exception) { null }
}
