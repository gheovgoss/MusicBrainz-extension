package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Helper JSON

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun cover(path: String): ImageHolder =
    ImageHolder.NetworkRequestImageHolder(NetworkRequest(url = "https://coverartarchive.org/$path"), false)

private fun releaseGroupCover(mbid: String) = cover("release-group/$mbid/front-250")

fun artistsOf(o: JsonObject): List<Artist> =
    o.arr("artist-credit").mapNotNull { credit ->
        val c = credit as? JsonObject ?: return@mapNotNull null
        val a = c.obj("artist") ?: return@mapNotNull null
        val id = a["id"].str() ?: return@mapNotNull null
        Artist(
            id = id,
            name = a["name"].str() ?: c["name"].str() ?: "?",
            extras = mapOf("mbid" to id),
            isRadioSupported = true,
            isFollowable = false,
            isSaveable = false,
        )
    }

fun JsonObject.toArtist(): Artist? {
    val id = this["id"].str() ?: return null
    val name = this["name"].str() ?: return null
    val subtitle = listOfNotNull(
        this["type"].str(),
        this["country"].str(),
        this["disambiguation"].str()?.takeIf { it.isNotBlank() },
    ).joinToString(" • ").ifEmpty { null }
    return Artist(
        id = id,
        name = name,
        subtitle = subtitle,
        extras = mapOf("mbid" to id),
        isRadioSupported = true,
        isFollowable = false,
        isSaveable = false,
    )
}

fun JsonObject.toAlbum(): Album? {
    val id = this["id"].str() ?: return null
    val title = this["title"].str() ?: return null
    val kinds = listOfNotNull(this["primary-type"].str()) + arr("secondary-types").mapNotNull { it.str() }
    val year = this["first-release-date"].str()?.take(4)?.takeIf { it.length == 4 }
    return Album(
        id = id,
        title = title,
        cover = releaseGroupCover(id),
        artists = artistsOf(this),
        subtitle = listOfNotNull(kinds.joinToString(" + ").ifEmpty { null }, year)
            .joinToString(" • ").ifEmpty { null },
        extras = mapOf("mbid" to id, "kind" to "release-group"),
        isRadioSupported = true,
        isSaveable = false,
    )
}

suspend fun MBClient.recordingsByMbids(mbids: List<String>): List<Track> {
    if (mbids.isEmpty()) return emptyList()
    val res = get(
        "recording",
        mapOf("query" to "rid:(${mbids.joinToString(" OR ")})", "limit" to mbids.size.toString())
    ) ?: return emptyList()
    val byId = res.arr("recordings")
        .mapNotNull { (it as? JsonObject)?.toTrack() }
        .associateBy { it.id }
    return mbids.mapNotNull { byId[it] }
}

fun JsonObject.toTrack(): Track? {
    val id = this["id"].str() ?: return null
    val title = this["title"].str() ?: return null

    val release = arr("releases").firstOrNull() as? JsonObject
    val rgId = release?.obj("release-group")?.get("id").str()
    val artists = artistsOf(this)

    val album = if (release != null && rgId != null) {
        Album(
            id = rgId,
            title = release["title"].str() ?: "",
            cover = releaseGroupCover(rgId),
            artists = artists,
            extras = mapOf("mbid" to rgId, "kind" to "release-group"),
            isRadioSupported = true,
            isSaveable = false,
        )
    } else null

    val isrc = arr("isrcs").firstNotNullOfOrNull { e -> e.str() ?: (e as? JsonObject)?.get("id").str() }

    return Track(
        id = id,
        title = title,
        cover = rgId?.let { releaseGroupCover(it) },
        artists = artists,
        album = album,
        duration = (this["length"] as? JsonPrimitive)?.longOrNull,
        isrc = isrc,
        extras = mapOf("mbid" to id),
        isRadioSupported = true,
        isSaveable = false,
        isHideable = false,
        isLikeable = false,
    )
}

private val LUCENE_SPECIAL = Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)""")

fun luceneEscape(s: String): String = s.replace(LUCENE_SPECIAL, "\\\\$1")

suspend fun MBClient.searchAll(query: String): List<Shelf> {
    val q = luceneEscape(query.trim())
    if (q.isEmpty()) return emptyList()

    val artists = get("artist", mapOf("query" to q, "limit" to "5"))
        ?.arr("artists")?.mapNotNull { (it as? JsonObject)?.toArtist() }.orEmpty()
    val albums = get("release-group", mapOf("query" to q, "limit" to "8"))
        ?.arr("release-groups")?.mapNotNull { (it as? JsonObject)?.toAlbum() }.orEmpty()
    val tracks = get("recording", mapOf("query" to q, "limit" to "15"))
        ?.arr("recordings")?.mapNotNull { (it as? JsonObject)?.toTrack() }.orEmpty()

    return artists.map { Shelf.Item(it) } +
            albums.map { Shelf.Item(it) } +
            tracks.map { Shelf.Item(it) }
}