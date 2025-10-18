package com.Camsoda

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class CamsodaProvider : MainAPI() {
    override var mainUrl              = "https://www.camsoda.com"
    override var name                 = "Camsoda"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "/api/v1/browse/react?gender-hide=m,t,c&perPage=98" to "Girls",
        "/api/v1/browse/react?gender-hide=c,f,t&perPage=98" to "Male",
        "/api/v1/browse/react?gender-hide=c,f,m&perPage=98" to "Transgender",
        "/api/v1/browse/react?gender-hide=m,f,t&perPage=98" to "Couples",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = app.get("$mainUrl${request.data}&p=$page")
            .parsedSafe<Response>()!!
            .userList.map { user ->
                newLiveSearchResponse(
                    name = user.username,
                    url  = "$mainUrl/${user.username}",
                    type = TvType.Live,
                ).apply {
                    posterUrl = if (user.offlinePictureUrl.isNullOrEmpty()) user.thumbUrl else user.offlinePictureUrl
                    lang = null
                }
            }

        return newHomePageResponse(
            HomePageList(request.name, list, isHorizontalImages = true),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (i in 0..3) {
            val page = app.get("$mainUrl/api/v1/browse/react/search/$query?p=$i&perPage=98")
                .parsedSafe<Response>()?.userList.orEmpty()

            if (page.isEmpty()) break

            out += page.map { user ->
                newLiveSearchResponse(
                    name = user.username,
                    url  = "$mainUrl/${user.username}",
                    type = TvType.Live,
                ).apply {
                    posterUrl = if (user.offlinePictureUrl.isNullOrEmpty()) user.thumbUrl else user.offlinePictureUrl
                    lang = null
                }
            }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        val poster = fixUrlNull(doc.selectFirst("[property='og:image']")?.attr("content"))
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newLiveStreamLoadResponse(
            name    = title,
            url     = url,
            dataUrl = url,
        ).apply {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val script = doc.select("script").find { it.html().contains("window.__PRELOADED_STATE__") }
        val json = JSONObject(script?.html()?.replace("window.__PRELOADED_STATE__ = ", "") ?: "{}")

        val username = json.getJSONObject("chatPage").getString("username")
        val streamJson = json.getJSONObject("chatRoom")
            .getJSONObject("roomByUsername")
            .getJSONObject(username)
            .getJSONObject("stream")

        val servers = streamJson.getJSONArray("edge_servers")
        val streamName = streamJson.getString("stream_name")
        val token = streamJson.getString("token")
        val template = "https://streamServer/${streamName}_v1/index.m3u8?token=$token"

        for (i in 0 until servers.length()) {
            val finalUrl = template.replace("streamServer", servers.getString(i))
            callback(
                newExtractorLink(
                    source = "$name Server ${i + 1}",
                    name   = "$name Server ${i + 1}",
                    url    = finalUrl,
                    type   = ExtractorLinkType.M3U8
                )
            )
        }
        return true
    }

    data class User(
        @JsonProperty("username")           val username: String = "",
        @JsonProperty("offlinePictureUrl")  val offlinePictureUrl: String = "",
        @JsonProperty("thumbUrl")           val thumbUrl: String = "",
    )

    data class Response(
        @JsonProperty("userList") val userList: List<User> = emptyList()
    )
}