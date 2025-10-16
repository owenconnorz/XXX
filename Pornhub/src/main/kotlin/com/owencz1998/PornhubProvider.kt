package com.owencz1998

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
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

    private val cookies = mapOf(
        "hasVisited" to "1",
        "accessAgeDisclaimerPH" to "1"
    )
    private val commonHeaders = mapOf("Referer" to mainUrl)

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val pagedLink = if (page > 0) request.data + page else request.data
            val soup = app.get(pagedLink, cookies = cookies, headers = commonHeaders).document
            val home = soup.select("div.sectionWrapper div.wrap").mapNotNull {
                val title = it.selectFirst("span.title a")?.text() ?: ""
                val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val img = fetchImgUrl(it.selectFirst("img"))
                newMovieSearchResponse(
                    name = title,
                    url = link,
                    type = globalTvType,
                ) {
                    posterUrl = img
                }
            }
            if (home.isEmpty()) throw ErrorLoadingException("No homepage data found!")
            return newHomePageResponse(
                HomePageList(request.name, home, isHorizontalImages = true),
                hasNext = true
            )
        } catch (e: Exception) {
            logError(e)
            throw ErrorLoadingException()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/video/search?search=$query"
        val document = app.get(url, cookies = cookies, headers = commonHeaders).document
        return document.select("div.sectionWrapper div.wrap").mapNotNull {
            val title = it.selectFirst("span.title a")?.text() ?: return@mapNotNull null
            val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val image = fetchImgUrl(it.selectFirst("img"))
            newMovieSearchResponse(
                name = title,
                url = link,
                type = globalTvType,
            ) { posterUrl = image }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val soup = app.get(url, cookies = cookies, headers = commonHeaders).document
        val title = soup.selectFirst(".title span")?.text() ?: ""
        val poster: String? = soup.selectFirst("div.video-wrapper .mainPlayerDiv img")?.attr("src")
            ?: soup.selectFirst("head meta[property=og:image]")?.attr("content")
        val tags = soup.select("div.categoriesWrapper a")
            .map { it.text().trim().replace(", ", "") }

        val actors = soup
            .select("div.video-wrapper div.video-info-row.userRow div.userInfo div.usernameWrap a")
            .map { it.text() }

        val related = soup.select("li.fixedSizeThumbContainer").map {
            val rTitle = it.selectFirst("div.phimage a")?.attr("title") ?: ""
            val rUrl = fixUrl(it.selectFirst("div.phimage a")?.attr("href").toString())
            val rPoster = fixUrl(it.selectFirst("div.phimage img.js-videoThumb")?.attr("src").toString())
            newMovieSearchResponse(name = rTitle, url = rUrl) { posterUrl = rPoster }
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
        val doc = app.get(data, cookies = cookies, headers = commonHeaders).document

        // Try modern player first
        val scriptsJoined = doc.select("script").joinToString("\n") { it.data() }
        val mediaArrayStr =
            Regex("\"mediaDefinitions\"\\s*:\\s*(\\[.*?\\])", RegexOption.DOT_MATCHES_ALL)
                .find(scriptsJoined)?.groupValues?.get(1)
                ?: run {
                    // Fallback to legacy flashvars JSON
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

            // Handle JSON indirection
            if (mUrl.endsWith(".json")) {
                runCatching {
                    val j = JSONObject(app.get(mUrl, headers = commonHeaders).text)
                    mUrl = j.optString("videoUrl", mUrl)
                }
            }

            val qualityStr = obj.optString("quality")
            val extlinkList = mutableListOf<ExtractorLink>()
            M3u8Helper().m3u8Generation(M3u8Helper.M3u8Stream(mUrl), true).amap { stream ->
                extlinkList.add(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = stream.streamUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.quality = Regex("(\\d+)").find(qualityStr)?.groupValues?.getOrNull(1)
                            .let { getQualityFromName(it) }
                        referer = mainUrl
                    }
                )
            }
            extlinkList.forEach(callback)
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
