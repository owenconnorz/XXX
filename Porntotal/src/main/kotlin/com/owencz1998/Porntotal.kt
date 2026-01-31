package com.Owencz1998

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray

class PornTotal : MainAPI() {
    override var mainUrl              = "https://www.porntotal.com"
    override var name                 = "PornTotal"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Hottest New Videos",
        "content" to "All Videos",
        "content?category=anal" to "Anal",
        "content?category=amateur" to "Amateur",
        "content?category=asian" to "Asian",
        "content?category=big-ass" to "Big Ass",
        "content?category=big-tits" to "Big Tits",
        "content?category=blowjob" to "Blowjob",
        "content?category=creampie" to "Creampie",
        "content?category=ebony" to "Ebony",
        "content?category=latina" to "Latina",
        "content?category=milf" to "MILF",
        "content?category=teen" to "Teen",
        "content?category=lesbian" to "Lesbian",
        "content?category=bbw" to "BBW",
        "content?category=mature" to "Mature",
        "content?category=gangbang" to "Gangbang",
        "content?category=interracial" to "Interracial",
        "content?category=vr" to "VR",
        "content?category=cosplay" to "Cosplay",
        "content?category=cartoon" to "Cartoon",
        "content?category=college" to "College",
        "content?category=bisexual" to "Bisexual",
        "content?category=pissing" to "Pissing",
        // Add even more if needed
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val url = if (page == 1) {
            if (data.isEmpty()) mainUrl else "$mainUrl/$data"
        } else {
            if (data.isEmpty()) {
                "$mainUrl?page=$page"
            } else if (data.contains("?")) {
                "$mainUrl/$data&page=$page"
            } else {
                "$mainUrl/$data?page=$page"
            }
        }
        val document = app.get(url).document
        val items = document.select("a[href^='/video/']")
        val home = items.chunked(2).mapNotNull { chunk ->
            if (chunk.size < 2) return@mapNotNull null
            val titleElem = chunk[1]
            val title = titleElem.text().trim()
            val href = fixUrl(titleElem.attr("href"))
            val posterUrl: String? = null // No posters
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }

        return newHomePageResponse(
            HomePageList(
                request.name,
                home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty() // Assume has next if items present
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Not used, but if needed
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList() // No search
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val description = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        val iframeSrc = document.selectFirst("iframe[src*=\"pornhub.com/embed\"]")?.attr("src") ?: return false

        val embedDoc = app.get(iframeSrc, referer = data).document

        val scripts = embedDoc.select("script")
        val flashvarScript = scripts.firstOrNull { it.html().contains("flashvars_") }?.html() ?: return false

        // Use regex to extract the JSON string
        val regex = """var flashvars = (.*?);""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(flashvarScript) ?: return false
        val jsonStr = match.groupValues[1].trim()

        val flashvars = parseJson<JSONObject>(jsonStr)

        val mediaDefinitions = flashvars.getJSONArray("mediaDefinitions")

        for (i in 0 until mediaDefinitions.length()) {
            val obj = mediaDefinitions.getJSONObject(i)
            val format = obj.optString("format")
            if (format == "mp4") {
                val qualityStr = obj.optString("quality")
                val quality = qualityStr.toIntOrNull() ?: continue
                val videoUrl = obj.optString("videoUrl")
                if (videoUrl.isNotBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = this.name + " ${qualityStr}p",
                            url = videoUrl,
                            referer = iframeSrc,
                            quality = quality,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        }

        return true
    }
}