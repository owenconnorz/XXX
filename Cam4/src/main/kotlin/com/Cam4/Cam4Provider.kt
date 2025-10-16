package com.Cam4


import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class Cam4Provider : MainAPI() {
    override var mainUrl              = "https://www.cam4.com"
    override var name                 = "Cam4"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
            "/api/directoryCams?directoryJson=true&online=true&url=true&orderBy=VIDEO_QUALITY&resultsPerPage=60" to "All",
            "/api/directoryCams?directoryJson=true&online=true&url=true&orderBy=VIDEO_QUALITY&gender=female&broadcastType=female_group&broadcastType=solo&broadcastType=male_female_group&resultsPerPage=60" to "Female",
            "/api/directoryCams?directoryJson=true&online=true&url=true&orderBy=VIDEO_QUALITY&gender=male&broadcastType=male_group&broadcastType=solo&broadcastType=male_female_group&resultsPerPage=60" to "Male",
            "/api/directoryCams?directoryJson=true&online=true&url=true&orderBy=VIDEO_QUALITY&gender=shemale&resultsPerPage=60" to "Transgender",
            "/api/directoryCams?directoryJson=true&online=true&url=true&orderBy=VIDEO_QUALITY&broadcastType=male_group&broadcastType=female_group&broadcastType=male_female_group&resultsPerPage=60" to "Couples",
        )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            val responseList = app.get("$mainUrl${request.data}&page=$page").parsedSafe<Response>()!!.users.map { user->
                 LiveSearchResponse(
                    name      = user.username,
                    url       = "$mainUrl/${user.username}",
                    apiName   = this@Cam4Provider.name,
                    type      = TvType.Live,
                    posterUrl = user.snapshotImageLink,
                    lang      = null
                )
            }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)

    }


    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<LiveSearchResponse>()
        // Search is not available for this provider
        return searchResponse

    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    

         return LiveStreamLoadResponse(
            name      = title,
            url       = url,
            apiName   = this.name,
            dataUrl   = url,
            posterUrl = poster,
            plot      = description,
        )
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val username = Uri.parse(data).path?.replace("/","")
        val streamUrl = "https://www.cam4.com/rest/v1.0/profile/$username/streamInfo"
        val res = app.get(streamUrl).text
        val json = JSONObject(res)
        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = json.get("cdnURL").toString(),
                referer = "",
                Qualities.Unknown.value,
                isM3u8 = true
            )
        )

        return true
    }

    data class User(
        @JsonProperty("username")    val username: String       = "",
        @JsonProperty("snapshotImageLink")  val snapshotImageLink: String  = "",
        @JsonProperty("userId")  val userId: String  = "",
    )

    data class Response(
        @JsonProperty("users")  val users: List<User> = arrayListOf()
    )
}

