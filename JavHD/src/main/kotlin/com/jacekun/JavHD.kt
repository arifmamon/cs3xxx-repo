package com.jacekun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class JavHD : MainAPI() {
    private val globalTvType = TvType.NSFW
    override var name = "JavHD"
    override var mainUrl = "https://javhd.icu"
    override val supportedTypes: Set<TvType> get() = setOf(TvType.NSFW)
    override val hasDownloadSupport: Boolean get() = true
    override val hasMainPage: Boolean get() = true
    override val hasQuickSearch: Boolean get() = false

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Main Page",
    )

    private val prefix = "JAV HD"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = mutableListOf<HomePageList>()
        val pagedlink = if (page > 0) request.data + page else request.data
        val document = app.get(pagedlink).document
        val mainbody = document.getElementsByTag("body").select("div.container")
        //Log.i(this.name, "Result => (mainbody) ${mainbody}")

        var count = 0
        //Log.i(this.name, "Titles => ${mainbody.select("div.section-header")}")
        val titles = mainbody.select("div.section-header").mapNotNull {
            val text = it?.text() ?: return@mapNotNull null
            count++
            Pair(count, text)
        }

        //Log.i(this.name, "Result => (titles) ${titles}")
        count = 0
        //Log.i(this.name, "Body => ${mainbody.select("div#video-widget-3016")}")
        mainbody.select("div#video-widget-3016").forEach { it2 ->
            count++
            // Fetch row title
            val pair = titles.filter { aa -> aa.first == count }
            val title = if (pair.isNotEmpty()) { pair[0].second } else { "<No Name Row>" }
            // Fetch list of items and map
            val inner = it2.select("div.col-md-3.col-sm-6.col-xs-6.item.responsive-height.post")
            val elements: List<SearchResponse> = inner.mapNotNull {
                // Inner element
                //Log.i(this.name, "Result => ${it.selectFirst("div.item-img > a")}")
                val aa = it.selectFirst("div.item-img > a") ?: return@mapNotNull null
                // Video details
                val link = aa.attr("href") ?: return@mapNotNull null
                val name = aa.attr("title").cleanTitle()
                var image = aa.select("img").attr("src")
                //Get another image from 'srcset' element
                if (image.isNullOrBlank()) {
                    run breaking@ {
                        aa.select("img").attr("srcset").split("\\s+".toRegex())
                        .forEach { imgItem ->
                            image = imgItem.trim()
                            if (image.startsWith("https")) {
                                return@breaking
                            }
                        }
                    }
                }
                val year = null
                //Log.i(this.name, "Result => (link) ${link}")
                //Log.i(this.name, "Result => (image) ${image}")

                MovieSearchResponse(
                    name = name,
                    url = link,
                    apiName = this.name,
                    type = globalTvType,
                    posterUrl = image,
                    year = year,
                    id = null,
                )
            }.distinctBy { a -> a.url }

            if (elements.isNotEmpty()) {
                homePageList.add(
                    HomePageList(
                        name = title,
                        list = elements,
                        isHorizontalImages = true
                    )
                )
            }
        }
        if (homePageList.isNotEmpty()) {
            return HomePageResponse(
                items = homePageList,
                hasNext = homePageList.any{ it.list.isNotEmpty() }
            )
        }
        throw ErrorLoadingException("No homepage data found!")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document.getElementsByTag("body")
            .select("div.container > div.row")
            .select("div.col-md-8.col-sm-12.main-content")
            .select("div.row.video-section.meta-maxwidth-230")
            .select("div.item.responsive-height.col-md-4.col-sm-6.col-xs-6")
        //Log.i(this.name, "Result => $document")
        return document.mapNotNull {
            val content = it.selectFirst("div.item-img > a") ?: return@mapNotNull null
            //Log.i(this.name, "Result => $content")
            val link = fixUrlNull(content.attr("href")) ?: return@mapNotNull null
            val imgContent = content.select("img")
            val title = imgContent.attr("alt").cleanTitle()
            val image = imgContent.attr("src").trim('\'')
            val year = null
            //Log.i(this.name, "Result => Title: ${title}, Image: ${image}")

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
        val document = app.get(url).document
        val body = document.getElementsByTag("body")
            .select("div.container > div.row")
            .select("div.col-md-8.col-sm-12.main-content")
            .firstOrNull()
        //Log.i(this.name, "Result => ${body}")
        val videoDetailsEl = body?.select("div.video-details")
        val innerBody = videoDetailsEl?.select("div.post-entry")
        val innerDiv = innerBody?.select("div")?.firstOrNull()

        // Video details
        val poster = innerDiv?.select("img")?.attr("src")
        val title = innerDiv?.selectFirst("p.wp-caption-text")?.text()?.cleanTitle() ?: "<No Title>"
        //Log.i(this.name, "Result => (title) $title")
        val descript = innerBody?.select("p")?.get(0)?.text()?.cleanTitle()
        //Log.i(this.name, "ApiError => (innerDiv) ${innerBody?.select("p")}")

        val re = Regex("[^0-9]")
        var yearString = videoDetailsEl?.select("span.date")?.firstOrNull()?.text()
        //Log.i(this.name, "Result => (yearString) ${yearString}")
        yearString = yearString?.let { re.replace(it, "").trim() }
        //Log.i(this.name, "Result => (yearString) ${yearString}")
        val year = yearString?.takeLast(4)?.toIntOrNull()
        val tags = mutableListOf<String>()
        videoDetailsEl?.select("span.meta")?.forEach {
            //Log.i(this.name, "Result => (span meta) $it")
            val caption = it?.selectFirst("span.meta-info")?.text()?.trim()?.lowercase() ?: ""
            when (caption) {
                "category", "tag" -> {
                    val tagtexts = it.select("a").mapNotNull { tag ->
                        tag?.text()?.trim() ?: return@mapNotNull null
                    }
                    if (tagtexts.isNotEmpty()) {
                        tags.addAll(tagtexts.filter { a -> a.isNotBlank() }.distinct())
                    }
                }
            }
        }

        val recs = body?.select("div.latest-wrapper div.item.active > div")?.mapNotNull {
            val innerAImg = it?.select("div.item-img") ?: return@mapNotNull null
            val aName = it.select("h3 > a").text().cleanTitle()
            val aImg = innerAImg.select("img").attr("src")
            val aUrl = innerAImg.select("a").get(0)?.attr("href") ?: return@mapNotNull null
            MovieSearchResponse(
                url = aUrl,
                name = aName,
                type = globalTvType,
                posterUrl = aImg,
                year = null,
                apiName = this.name
            )
        }

        // Video links, find if it contains multiple scene links
        //val sceneList = mutableListOf<TvSeriesEpisode>()
        val sceneList = body?.select("ul.pagination.post-tape > li")?.apmap { section ->
            val innerA = section?.select("a") ?: return@apmap null
            val vidlink = fixUrlNull(innerA.attr("href")) ?: return@apmap null
            Log.i(this.name, "Result => (vidlink) $vidlink")

            val sceneCount = innerA.text().toIntOrNull()
            val viddoc = app.get(vidlink).document.getElementsByTag("body").get(0)
            val streamEpLink = viddoc?.getValidLinks()?.removeInvalidLinks() ?: ""
            Episode(
                name = "Scene $sceneCount",
                season = null,
                episode = sceneCount,
                data = streamEpLink,
                posterUrl = poster,
                date = null
            )
        }?.filterNotNull() ?: listOf()
        if (sceneList.isNotEmpty()) {
            return TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = sceneList.filter { it.data.isNotBlank() },
                posterUrl = poster,
                year = year,
                plot = descript,
                tags = tags,
                recommendations = recs
            )
        }
        val videoLinks = body?.getValidLinks()?.removeInvalidLinks() ?: ""
        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = globalTvType,
            dataUrl = videoLinks,
            posterUrl = poster,
            year = year,
            plot = descript,
            tags = tags,
            recommendations = recs
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
        tryParseJson<List<String>>(data.trim())?.apmap { vid ->
            Log.i(this.name, "Result => (vid) $vid")
            if (vid.startsWith("http")) {
                count++
                when {
                    vid.startsWith("https://javhdfree.icu") -> {
                        val editedLink = vid.removePrefix("https://")
                        val idx = editedLink.indexOf('/', 0) + 1
                        val finalLink = "https://embedsito.com/${editedLink.substring(idx)}"
                        loadExtractor(
                            url = finalLink,
                            referer = vid,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    }
                    vid.startsWith("https://viewsb.com") -> {
                        val url = vid.replace("viewsb.com", "watchsb.com")
                        loadExtractor(
                            url = url,
                            referer = url,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    }
                    else -> {
                        loadExtractor(
                            url = vid,
                            referer = vid,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    }
                }
            }
        }
        return count > 0
    }

    private fun Element?.getValidLinks(): List<String>? =
        this?.select("iframe")?.mapNotNull { iframe ->
            //Log.i("debug", "Result => (iframe) $iframe")
            fixUrlNull(iframe.attr("src")) ?: return@mapNotNull null
        }?.toList()

    private fun List<String>.removeInvalidLinks(): String =
        this.filter { a -> a.isNotBlank() && !a.startsWith("https://a.realsrv.com") }.toJson()

    private fun String.cleanTitle(): String =
        this.trim().removePrefix(prefix).trim()

}