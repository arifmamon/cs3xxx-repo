package com.jacekun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.XStreamCdn
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class OpJav : MainAPI() {
    private val globalTvType = TvType.NSFW
    override var name = "OpJAV"
    override var mainUrl = "https://opjav.com"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val prefix = "Watch JAV"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        val all = ArrayList<HomePageList>()
        val body = document.getElementsByTag("body")
        val rows = mutableListOf<Pair<String, Element>>()
        val selectorSimple = "div.list-film-simple > div.item"
        val selectorRows = "div.list-film.row > div"

        body?.select("div.content")?.forEach {
            if (it != null) {
                if (it.select(selectorRows).isNullOrEmpty()) {
                    rows.add(Pair(selectorSimple, it))
                } else {
                    rows.add(Pair(selectorRows, it))
                }
            }
        }

        var count = 0
        rows.forEach { row ->
            count++
            val title = "Row $count"
            val isSimple = row.first == selectorSimple
            val entries = row.second.select(row.first)
            val elements = entries.mapNotNull {
                if (it == null) { return@mapNotNull null }
                val link: String
                val name: String
                val image : String?
                var year : Int? = null

                if (isSimple) {
                    //Simple load
                    val inner = it.select("div.info") ?: return@mapNotNull null
                    link = fixUrlNull(inner.select("a").get(0)?.attr("href")) ?: return@mapNotNull null
                    name = inner.text().trim()
                    val imgsrc = it.select("img")
                    image = imgsrc.attr("src") ?: imgsrc.attr("data-src")
                } else {
                    val inner = it.select("div.inner") ?: return@mapNotNull null
                    val poster = inner.select("a.poster") ?: return@mapNotNull null
                    link = fixUrlNull(poster.attr("href")) ?: return@mapNotNull null
                    name = it.text().trim().removePrefix("HD")
                    image = poster.select("img")?.attr("src")
                    year = inner.select("dfn")?.get(1)?.text()?.toIntOrNull()
                }
                MovieSearchResponse(
                    name = name,
                    url = link,
                    apiName = this.name,
                    type = globalTvType,
                    posterUrl = image,
                    year = year
                )
            }.distinctBy { a -> a.url }
            if (elements.isNotEmpty()) {
                all.add(
                    HomePageList(
                        name = title,
                        list = elements
                    )
                )
            }
        }
        return HomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query}/"
        val document = app.get(url).document
            .select("div.block-body > div.list-film.row > div")
        //.select("div.item.col-lg-3.col-md-3.col-sm-6.col-xs-6")
        //Log.i(this.name, "Result => (document) ${document}")
        return document.mapNotNull {
            val inner = it.select("div.inner") ?: return@mapNotNull null
            val innerPost = inner.select("a.poster") ?: return@mapNotNull null

            val link = fixUrlNull(innerPost.attr("href")) ?: return@mapNotNull null
            val title = innerPost.attr("title").trim().removePrefix(prefix).trim()
            val imgsrc = innerPost.select("img")
            val image = fixUrlNull(imgsrc.attr("src") ?: imgsrc.attr("data-src"))
            val year = inner.select("dfn").last()?.text()?.trim()?.toIntOrNull()

            //Log.i(this.name, "Result => $")
            MovieSearchResponse(
                name = title,
                url = link,
                apiName = this.name,
                type = globalTvType,
                posterUrl = image,
                year = year
            )
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        //Log.i(this.name, "Result => (url) ${url}")
        val poster = fixUrlNull(doc.select("meta[itemprop=image]").get(1)?.attr("content")?.trim())
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content").toString().removePrefix(prefix).trim()
        val descript = "Title: $title ${System.lineSeparator()}" + doc.selectFirst("meta[name=keywords]")?.attr("content")?.trim()
        val year = doc.selectFirst("meta[itemprop=dateCreated]")?.attr("content")?.toIntOrNull()

        val tags = doc.select("dl > dd").get(1)?.select("a")?.mapNotNull {
            //Log.i(this.name, "Result => (tag) $it")
            it?.text()?.trim() ?: return@mapNotNull null
        }

        //Fetch server links
        val watchlink = ArrayList<String>()
        val mainLink = doc.select("div.buttons.row a").attr("href") ?: ""
        //Log.i(this.name, "Result => (mainLink) $mainLink")

        //Fetch episode links from mainlink
        if (mainLink.isNotBlank()) {
            app.get(url = mainLink, referer = mainUrl).document.let { epsDoc ->
                //Fetch filmId
                /*var filmId = ""
        val epLinkDoc = epsDoc.getElementsByTag("head").select("script").toString()
        //Log.i(this.name, "Result => (epLinkDoc) $epLinkDoc")
        try {
            val epTextTemp = epLinkDoc.substring(epLinkDoc.indexOf("filmID = parseInt"))
            val epText = epTextTemp.substring(1, epTextTemp.indexOf("</script>")).trim()
                .filterNot { a -> a.isWhitespace() }
            if (epText.isNotEmpty()) {
                filmId = try {
                    "(?<=filmID=parseInt\\(')(.*)(?='\\);)".toRegex().find(epText)?.groupValues?.get(0) ?: ""
                } catch (e: Exception) { "" }
                Log.i(this.name, "Result => (filmId) $filmId")
            }
        } catch (e: Exception) { }*/
                //Fetch server links
                epsDoc.select("div.block.servers li").mapNotNull {
                    val inner = it?.selectFirst("a") ?: return@mapNotNull null
                    val linkUrl = inner.attr("href") ?: return@mapNotNull null
                    val linkId = inner.attr("id") ?: return@mapNotNull null
                    Pair(linkUrl, linkId)
                }.apmap {
                    //First = Url, Second = EpisodeID
                    //Log.i(this.name, "Result => (eplink-Id) $it")
                    val ajaxHead = mapOf(
                        Pair("Origin", mainUrl),
                        Pair("Referer", it.first)
                    )
                    //https://opjav.com/movie/War%20of%20the%20Roses-64395/watch-movie.html
                    //EpisodeID, 442671
                    //filmID, 64395
                    val ajaxData = mapOf(
                        Pair("NextEpisode", "1"),
                        Pair("EpisodeID", it.second)
                        //Pair("filmID", filmId)
                    )
                    app.post("$mainUrl/ajax", headers = ajaxHead, data = ajaxData)
                        .document.select("iframe").forEach { iframe ->
                        val serverLink = iframe?.attr("src")?.trim()
                        if (!serverLink.isNullOrBlank()) {
                            watchlink.add(serverLink)
                            Log.i(this.name, "Result => (serverLink) $serverLink")
                        }
                    }
                }
            }
        }
        val streamUrl = watchlink.distinct().toJson()
        Log.i(this.name, "Result => (streamUrl) $streamUrl")
        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = globalTvType,
            dataUrl = streamUrl,
            posterUrl = poster,
            year = year,
            plot = descript,
            tags = tags
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        //NNN
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "${this.name} VIP HD",
                url = "https://biblescreen.faithlifecdn.com/biblescreen/bibleScreen/playlist.m3u8",//"https://files.catbox.moe/9czzyk.mp4",
                referer = data,
                quality = Qualities.P2160.value,
                isM3u8 = true
            )
        )
        var count = 0
        tryParseJson<List<String>>(data)?.forEach { link ->
            val url = fixUrl(link.trim())
            Log.i(this.name, "Result => (url) $url")
            when {
                url.contains("opmovie.xyz") -> {
                    XStreamCdn().let {
                        it.domainUrl = "opmovie.xyz"
                        it.getSafeUrl(
                            url = url,
                            referer = url,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    }
                    count++
                }
                else -> {
                    val success = loadExtractor(
                        url = url,
                        referer = mainUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    if (success) {
                        count++
                    }
                }
            }
        }
        return count > 0
    }
}