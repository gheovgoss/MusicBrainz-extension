package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLDecoder

fun String?.toEchoDate(): Date? {
    val p = this?.split('-') ?: return null
    val year = p.getOrNull(0)?.toIntOrNull() ?: return null
    return Date(year, p.getOrNull(1)?.toIntOrNull(), p.getOrNull(2)?.toIntOrNull())
}

// Album

private class AlbumData(
    val tracks: List<Track>,
    val durationMs: Long,
    val label: String?,
    val releaseDate: String?,
)

private fun pickRelease(releases: JsonArray): String? {
    val list = releases.mapNotNull { it as? JsonObject }
    val pool = list.filter { it["status"].str() == "Official" }.ifEmpty { list }
    return pool.minByOrNull { it["date"].str().orEmpty().ifEmpty { "9999" } }?.get("id").str()
}

private fun releaseTracks(rel: JsonObject, album: Album): List<Track> {
    val out = mutableListOf<Track>()
    for (m in rel.arr("media")) {
        val medium = m as? JsonObject ?: continue
        val disc = (medium["position"] as? JsonPrimitive)?.longOrNull
        for (t in medium.arr("tracks")) {
            val tr = t as? JsonObject ?: continue
            val rec = tr.obj("recording") ?: continue
            val id = rec["id"].str() ?: continue
            val isrc = rec.arr("isrcs").firstNotNullOfOrNull { e ->
                e.str() ?: (e as? JsonObject)?.get("id").str()
            }
            out += Track(
                id = id,
                title = tr["title"].str() ?: rec["title"].str() ?: "?",
                cover = album.cover,
                artists = artistsOf(tr).ifEmpty { artistsOf(rec) }.ifEmpty { album.artists },
                album = album,
                duration = (tr["length"] as? JsonPrimitive)?.longOrNull
                    ?: (rec["length"] as? JsonPrimitive)?.longOrNull,
                isrc = isrc,
                albumOrderNumber = (tr["position"] as? JsonPrimitive)?.longOrNull,
                albumDiscNumber = disc,
                extras = mapOf("mbid" to id),
                isRadioSupported = false,
                isSaveable = false,
                isHideable = false,
                isLikeable = false,
            )
        }
    }
    return out
}

private suspend fun MBClient.albumData(album: Album): AlbumData? {
    val rg = get("release-group/${album.id}", mapOf("inc" to "artist-credits+releases")) ?: return null
    val relId = pickRelease(rg.arr("releases")) ?: return null
    val rel = get(
        "release/$relId",
        mapOf("inc" to "recordings+artist-credits+labels+isrcs")
    ) ?: return null

    val tracks = releaseTracks(rel, album)
    val label = rel.arr("label-info").firstNotNullOfOrNull { (it as? JsonObject)?.obj("label")?.get("name").str() }
    return AlbumData(
        tracks = tracks,
        durationMs = tracks.sumOf { it.duration ?: 0L },
        label = label,
        releaseDate = rg["first-release-date"].str() ?: rel["date"].str(),
    )
}

suspend fun MBClient.loadAlbumDetails(album: Album): Album {
    val data = albumData(album) ?: return album
    val big = cover("release-group/${album.id}/front-500")
    return album.copy(
        cover = big,
        background = big,
        trackCount = data.tracks.size.toLong(),
        duration = data.durationMs.takeIf { it > 0 },
        releaseDate = data.releaseDate.toEchoDate(),
        label = data.label,
    )
}

suspend fun MBClient.albumTracks(album: Album): List<Track> = albumData(album)?.tracks.orEmpty()

// Artists

suspend fun MBClient.loadArtistDetails(artist: Artist): Artist {
    val mbid = artist.extras["mbid"] ?: return artist
    val a = get("artist/$mbid", mapOf("inc" to "genres+url-rels")) ?: return artist
    val rels = a.arr("relations").mapNotNull { it as? JsonObject }

    val span = a.obj("life-span")
    val begin = span?.get("begin").str()?.take(4)
    val end = span?.get("end").str()?.take(4)
    val ended = (span?.get("ended") as? JsonPrimitive)?.booleanOrNull == true
    val area = a.obj("area")?.get("name").str()
    val genres = a.arr("genres").mapNotNull { it as? JsonObject }
        .sortedByDescending { (it["count"] as? JsonPrimitive)?.intOrNull ?: 0 }
        .mapNotNull { it["name"].str() }
        .take(6)

    val subtitle = listOfNotNull(a["type"].str(), a["country"].str())
        .joinToString(" • ").ifEmpty { null }

    val qid = rels.firstOrNull { it["type"].str() == "wikidata" }
        ?.obj("url")?.get("resource").str()?.substringAfterLast('/')
    val entity = qid?.let { wikidataEntity(it) }
    val img = artistImage(rels, entity)
    val extract = entity?.let { wikipediaExtract(it) }

    val facts = listOfNotNull(
        a["disambiguation"].str()?.takeIf { it.isNotBlank() },
        begin?.let { "Active: $it–${if (ended) end ?: "?" else "present"}" },
        area?.let { "Area: $it" },
        genres.takeIf { it.isNotEmpty() }?.let { "Genres: ${it.joinToString(", ")}" },
    ).joinToString("\n")

    val bio = (if (extract != null) "$extract\n\nSource: Wikipedia" else facts).ifEmpty { null }

    return artist.copy(
        subtitle = subtitle ?: artist.subtitle,
        bio = bio,
        cover = img ?: artist.cover,
        background = img ?: artist.background,
    )
}

private fun bucketOf(rg: JsonObject): String {
    val secondary = rg.arr("secondary-types").mapNotNull { it.str() }
    return when {
        "Live" in secondary -> "live"
        "Compilation" in secondary -> "compilation"
        secondary.isNotEmpty() -> "other"
        rg["primary-type"].str() == "Album" -> "album"
        rg["primary-type"].str() == "EP" -> "ep"
        rg["primary-type"].str() == "Single" -> "single"
        else -> "other"
    }
}

suspend fun MBClient.artistShelves(artist: Artist): List<Shelf> {
    val res = get("release-group", mapOf("artist" to artist.id, "limit" to "100")) ?: return emptyList()
    val groups = res.arr("release-groups").mapNotNull { it as? JsonObject }.groupBy { bucketOf(it) }

    val order = listOf(
        "album" to "Albums", "ep" to "EPs", "single" to "Singles",
        "live" to "Live", "compilation" to "Compilations", "other" to "Other",
    )
    return order.mapNotNull { (key, title) ->
        val items = (groups[key] ?: return@mapNotNull null)
            .sortedByDescending { it["first-release-date"].str().orEmpty() }
            .mapNotNull { rg ->
                rg.toAlbum()?.let { if (it.artists.isEmpty()) it.copy(artists = listOf(artist)) else it }
            }
        if (items.isEmpty()) null else Shelf.Lists.Items(id = "disc:$key", title = title, list = items)
    }
}

suspend fun MBClient.loadTrackDetails(track: Track): Track {
    if (track.extras["mbid"] == null) return track
    val rec = get(
        "recording/${track.id}",
        mapOf("inc" to "isrcs+artist-credits+releases+release-groups")
    ) ?: return track
    val full = rec.toTrack() ?: return track

    return track.copy(
        isrc = full.isrc ?: track.isrc,
        duration = full.duration ?: track.duration,
        cover = track.cover ?: full.cover,
        album = track.album ?: full.album,
        artists = track.artists.ifEmpty { full.artists },
    )
}

private val WIKI_LANGS = listOf("en")

private suspend fun MBClient.wikidataEntity(qid: String): JsonObject? =
    getExternal("https://www.wikidata.org/wiki/Special:EntityData/$qid.json")
        ?.obj("entities")?.values?.firstOrNull() as? JsonObject

private fun JsonObject.p18(): String? =
    obj("claims")?.arr("P18")?.firstOrNull()?.let { it as? JsonObject }
        ?.obj("mainsnak")?.obj("datavalue")?.get("value").str()

private suspend fun MBClient.artistImage(rels: List<JsonObject>, entity: JsonObject?): ImageHolder? {
    val fromRel = rels.firstOrNull { it["type"].str() == "image" }
        ?.obj("url")?.get("resource").str()
        ?.substringAfter("File:", "")?.takeIf { it.isNotBlank() }
        ?.let { URLDecoder.decode(it, "UTF-8") }
    val file = fromRel ?: entity?.p18()
    return file?.let { commonsThumb(it) }
}

private suspend fun MBClient.wikipediaExtract(entity: JsonObject): String? {
    val sitelinks = entity.obj("sitelinks") ?: return null
    for (lang in WIKI_LANGS) {
        val title = sitelinks.obj("${lang}wiki")?.get("title").str() ?: continue
        val url = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "extracts")
            .addQueryParameter("exintro", "1")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("titles", title)
            .build().toString()
        val page = getExternal(url)?.obj("query")?.obj("pages")?.values?.firstOrNull() as? JsonObject
        val text = page?.get("extract").str()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        mbLog("desc: $lang.wikipedia \"$title\" (${text.length} car.)")
        return text
    }
    return null
}
private suspend fun MBClient.commonsThumb(file: String): ImageHolder? {
    val api = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
        .addQueryParameter("action", "query")
        .addQueryParameter("titles", "File:$file")
        .addQueryParameter("prop", "imageinfo")
        .addQueryParameter("iiprop", "url")
        .addQueryParameter("iiurlwidth", "800")
        .addQueryParameter("format", "json")
        .build().toString()
    val page = getExternal(api)?.obj("query")?.obj("pages")?.values?.firstOrNull() as? JsonObject
    val info = page?.arr("imageinfo")?.firstOrNull() as? JsonObject
    val url = info?.get("thumburl").str() ?: info?.get("url").str()
    mbLog("immagine artista: file=$file url=$url")
    return url?.let {
        ImageHolder.NetworkRequestImageHolder(
            NetworkRequest(url = it, headers = mapOf("User-Agent" to currentUserAgent())),
            false
        )
    }
}

//EXTERNAL LINKS

private val LINK_LABELS = linkedMapOf(
    "spotify.com" to "Spotify", "deezer.com" to "Deezer",
    "music.youtube.com" to "YouTube Music", "youtube.com" to "YouTube",
    "music.apple.com" to "Apple Music", "itunes.apple.com" to "Apple Music",
    "tidal.com" to "Tidal", "bandcamp.com" to "Bandcamp", "soundcloud.com" to "SoundCloud",
    "instagram.com" to "Instagram", "facebook.com" to "Facebook",
    "twitter.com" to "X", "x.com" to "X",
    "wikipedia.org" to "Wikipedia", "discogs.com" to "Discogs",
    "last.fm" to "Last.fm", "allmusic.com" to "AllMusic",
)

fun externalLinks(rels: List<JsonObject>): List<Pair<String, String>> =
    rels.mapNotNull { r ->
        val url = r.obj("url")?.get("resource").str() ?: return@mapNotNull null
        val host = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
            ?: return@mapNotNull null
        val label = LINK_LABELS.entries.firstOrNull { (k, _) -> host == k || host.endsWith(".$k") }?.value
            ?: if (r["type"].str() == "official homepage") "Website" else return@mapNotNull null
        label to url
    }.distinctBy { it.second }
        .sortedBy { (label, _) -> LINK_LABELS.values.distinct().indexOf(label).let { if (it < 0) 99 else it } }

private val artistImageCache = java.util.Collections.synchronizedMap(HashMap<String, ImageHolder?>())

suspend fun MBClient.searchArtistImage(artist: Artist): ImageHolder? {
    val mbid = artist.extras["mbid"] ?: return null
    if (artistImageCache.containsKey(mbid)) return artistImageCache[mbid]

    val a = get("artist/$mbid", mapOf("inc" to "genres+url-rels")) ?: return null
    val rels = a.arr("relations").mapNotNull { it as? JsonObject }
    val qid = rels.firstOrNull { it["type"].str() == "wikidata" }
        ?.obj("url")?.get("resource").str()?.substringAfterLast('/')
    val entity = qid?.let { wikidataEntity(it) }
    val img = artistImage(rels, entity)
    artistImageCache[mbid] = img
    return img
}

suspend fun MBClient.withImages(artists: List<Artist>, max: Int = 6): List<Artist> = coroutineScope {
    val head = artists.take(max).map { a ->
        async {
            val img = withTimeoutOrNull(10_000) { searchArtistImage(a) }
            if (img != null) a.copy(cover = img, background = img) else a
        }
    }.awaitAll()
    head + artists.drop(max)
}