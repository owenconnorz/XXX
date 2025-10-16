package com.owencz1998

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import org.json.JSONObject

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

    override val mainPage = mainPageOf(
        "$mainUrl/video?o=mr&hd=1&page="           to "Recently Featured",
        "$mainUrl/video?o=tr&t=w&hd=1&page="       to "Top Rated",
        "$mainUrl/video?o=mv&t=w&hd=1&page="       to "Most Viewed",
        "$mainUrl/video?o=ht&t=w&hd=1&page="       to "Hottest",
        "$mainUrl/video?p=professional&hd=1&page=" to "Professional",
        "$mainUrl/video?o=lg&hd=1&page="           to "Longest",
        "$mainUrl/video?p=homemade&hd=1&page="     to "Homemade",
        "$mainUrl/video?o=cm&t=w&hd=1&page="       to "Newest",
        "$mainUrl/video?c=35&page="                to "Anal",
        "$mainUrl/video?c=27&page="                to "Lesbian",
        "$mainUrl/video?c=98&page="                to "Arab",
        "$mainUrl/video?c=1&page="                 to "Asian",
        "$mainUrl/video?c=89&page="                to "Babysitter",
        "$mainUrl/video?c=6&page="                 to "BBW",
        "$mainUrl/video?c=141&page="               to "Behind The Scenes",
        "$mainUrl/video?c=4&page="                 to "Big Ass",
        "$mainUrl/video?c=7&page="                 to "Big Dick",
        "$mainUrl/video?c=8&page="                 to "Big Tits",
        "$mainUrl/video?c=13&page="                to "Blowjob",
    )

    private val cookies = mapOf(
        "hasVisited" to "1",
        "accessAgeDisclaimerPH" to "1"
    )
    private val commonHeaders = mapOf("Referer" to mainUrl)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pagedLink = if (page > 0) request.data + page else request.data
        val doc = app.get(pagedLink, cookies = cookies, headers = commonHeaders).document
        val items = doc.select("div.sectionWrapper div.wrap").mapNotNull { el ->
            val title = el.selectFirst("span.title a")?.text()?.trim().orEmpty()
            val link = fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val img = fetchImgUrl(el.selectFirst("img"))
            newMovieSearchResponse(
                name = title,
                url = link,
                type = globalTvType
            ) {
                posterUrl = img
            }
        }
        if (items.isEmpty()) throw ErrorLoadingException("No homepage data found.")
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/video/search?search=$query"
        val doc = app.get(url, cookies = cookies, headers = commonHeaders).document
        return doc.select("div.sectionWrapper div.wrap").mapNotNull { el ->
            val title = el.selectFirst("span.title a")?.text()?.trim() ?: return@mapNotNull null
            val link = fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val img = fetchImgUrl(el.selectFirst("img"))
            newMovieSearchResponse(
                name = title,
                url = link,
                type = globalTvType
            ) {
                posterUrl = img
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, cookies = cookies, headers = commonHeaders).document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst(".title span")?.text().orEmpty()
        val poster = fixUrlNull(
            doc.selectFirst("head meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.video-wrapper .mainPlayerDiv img")?.attr("src")
        )
        val tags = doc.select("div.categoriesWrapper a").map { it.text().trim() }
        val actors = doc.select("div.userInfo .usernameWrap a").map { it.text().trim() }

        val recs = doc.select("li.fixedSizeThumbContainer").mapNotNull { el ->
            val rTitle = el.selectFirst("div.phimage a")?.attr("title")?.trim().orEmpty()
            val rUrl = fixUrlNull(el.selectFirst("div.phimage a")?.attr("href")) ?: return@mapNotNull null
            val rPoster = fixUrlNull(el.selectFirst("div.phimage img.js-videoThumb")?.attr("src"))
            newMovieSearchResponse(rTitle, rUrl) { posterUrl = rPoster }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            plot = title
            this.tags = tags
            addActors(actors)
            recommendations = recs
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, cookies = cookies, headers = commonHeaders).document

        // Try modern player data first
        val playerData = doc.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("var player_", ignoreCase = true) || it.contains("\"mediaDefinitions\"") }
            ?: ""

        val jsonStr = playerData
            .substringAfter("\"mediaDefinitions\":", missingDelimiterValue = "")
            .let { s -> if (s.isNotEmpty()) s.substringBefore("]") + "]" else "" }

        val mediaDefs = if (jsonStr.isNotEmpty()) {
            JSONObject("""{"mediaDefinitions":$jsonStr}""").getJSONArray("mediaDefinitions")
        } else {
            // Fallback to legacy flashvars block
            val legacy = doc.selectXpath("//script[contains(text(),'flashvars')]").first()?.data()
                ?.substringAfter("=")?.substringBefore(";").orEmpty()
            if (legacy.isNotEmpty()) JSONObject(legacy).getJSONArray("mediaDefinitions") else null
        } ?: return false

        for (i in 0 until mediaDefs.length()) {
            val obj = mediaDefs.getJSONObject(i)
            val videoUrl = obj.optString("videoUrl").orEmpty()
            if (videoUrl.isEmpty()) continue
            // Some entries embed M3U8 directly, others point to a .m3u8 manifest list
            M3u8Helper().m3u8Generation(M3u8Helper.M3u8Stream(videoUrl), true).forEach { stream ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = stream.streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = getQualityFromName(
                            Regex("(\\d+)").find(obj.optString("quality"))?.groupValues?.getOrNull(1)
                        )
                        referer = mainUrl
                    }
                )
            }
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