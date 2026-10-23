package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

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
    if (artist.extras["mbid"] == null) return artist
    val a = get("artist/${artist.id}", mapOf("inc" to "genres+url-rels")) ?: return artist

    val span = a.obj("life-span")
    val begin = span?.get("begin").str()?.take(4)
    val end = span?.get("end").str()?.take(4)
    val ended = (span?.get("ended") as? JsonPrimitive)?.booleanOrNull == true
    val area = a.obj("area")?.get("name").str()
    val genres = a.arr("genres").mapNotNull { it as? JsonObject }
        .sortedByDescending { (it["count"] as? JsonPrimitive)?.intOrNull ?: 0 }
        .mapNotNull { it["name"].str() }
        .take(6)

    val bio = listOfNotNull(
        a["disambiguation"].str()?.takeIf { it.isNotBlank() },
        begin?.let { "Active: $it–${if (ended) end ?: "?" else "present"}" },
        area?.let { "Area: $it" },
        genres.takeIf { it.isNotEmpty() }?.let { "Genres: ${it.joinToString(", ")}" },
    ).joinToString("\n").ifEmpty { null }

    val subtitle = listOfNotNull(a["type"].str(), a["country"].str())
        .joinToString(" • ").ifEmpty { null }

    val qid = a.arr("relations").mapNotNull { it as? JsonObject }
        .firstOrNull { it["type"].str() == "wikidata" }
        ?.obj("url")?.get("resource").str()
        ?.substringAfterLast('/')
    val img = qid?.let { wikidataImage(it) }

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

private suspend fun MBClient.wikidataImage(qid: String): ImageHolder? {
    val e = getExternal("https://www.wikidata.org/wiki/Special:EntityData/$qid.json") ?: return null
    val file = e.obj("entities")?.obj(qid)?.obj("claims")?.arr("P18")
        ?.firstOrNull()?.let { it as? JsonObject }
        ?.obj("mainsnak")?.obj("datavalue")?.get("value").str() ?: return null
    val encoded = withContext(Dispatchers.IO) {
        URLEncoder.encode(file, "UTF-8")
    }.replace("+", "%20")
    return ImageHolder.NetworkRequestImageHolder(
        NetworkRequest(url = "https://commons.wikimedia.org/wiki/Special:FilePath/$encoded?width=600"),
        false
    )
}